/*
 * Tai-e: A Static Analysis Framework for Java
 *
 * Copyright (C) 2022 Tian Tan <tiantan@nju.edu.cn>
 * Copyright (C) 2022 Yue Li <yueli@nju.edu.cn>
 *
 * This file is part of Tai-e.
 *
 * Tai-e is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License
 * as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * Tai-e is distributed in the hope that it will be useful,but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Lesser General
 * Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with Tai-e. If not, see <https://www.gnu.org/licenses/>.
 */

package pascal.taie.analysis.dataflow.analysis.constprop;

import pascal.taie.analysis.dataflow.analysis.AbstractDataflowAnalysis;
import pascal.taie.analysis.graph.cfg.CFG;
import pascal.taie.analysis.pta.PointerAnalysisResult;
import pascal.taie.analysis.pta.core.heap.Obj;
import pascal.taie.config.AnalysisConfig;
import pascal.taie.ir.exp.*;
import pascal.taie.ir.stmt.Stmt;
import pascal.taie.ir.stmt.StoreField;
import pascal.taie.language.classes.JField;
import pascal.taie.language.type.PrimitiveType;
import pascal.taie.language.type.Type;
import pascal.taie.util.AnalysisException;
import polyglot.ast.IntLit;

import java.util.*;
import java.util.zip.Inflater;

public class ConstantPropagation extends
        AbstractDataflowAnalysis<Stmt, CPFact> {

    public static final String ID = "constprop";

    public ConstantPropagation(AnalysisConfig config) {
        super(config);
    }

    @Override
    public boolean isForward() {
        return true;
    }

    /**
     * <del>Boundary Fact is always safe condition which all variables are NAC.</del>
     * The boundary fact of entry node is always empty in total program. (not NAC or UNDEF).
     * But in the one method, the params is be always be NAC because it can be anything.
     */
    @Override
    public CPFact newBoundaryFact(CFG<Stmt> cfg) {

        CPFact boundaryFact = new CPFact();
        Value nac = Value.getNAC();

        for (Var param : cfg.getIR().getParams()) {
            if (canHoldInt(param)) {
                boundaryFact.update(param, nac);
            }
        }

        return boundaryFact;
    }

    /**
     * Constant Propagation is a must analysis, the initial fact is unsafe condition, which all
     * variables are undefined.
     */
    @Override
    public CPFact newInitialFact() {
        return new CPFact();
    }

    /**
     * Control flow merge function.
     * target ⊓= fact
     */
    @Override
    public void meetInto(CPFact fact, CPFact target) {
        if (fact == target) {
            return;
        }
        for (Var var : fact.keySet()) {
            target.update(var, meetValue(fact.get(var), target.get(var)));
        }
    }

    /**
     * Meets two Values.
     */
    public Value meetValue(Value v1, Value v2) {
        if (v1 == null || v2 == null) {
            throw new NullPointerException("v1 or v2 is null");
        }

        if (v1.isNAC() || v2.isNAC()) { // NAC ⊓ _ = NAC
            return Value.getNAC();
        } else if (v1.isUndef()) {  // Undef ⊓ _ = _
            return v2;
        } else if (v2.isUndef()) {
            return v1;
        } else {
            if (v1.equals(v2)) {   // c ⊓ c = c
                return v1;
            } else {  // c1 ⊓ c2 = NAC
                return Value.getNAC();
            }
        }
    }

    /**
     * Constant propagation is forward analysis, Out[stmt] = transferNode(In[stmt])
     *
     * @return true if the out is changed.
     */
    @Override
    public boolean transferNode(Stmt stmt, CPFact in, CPFact out) {
        if (isIntDefinitionVarDef(stmt)) {
            applyStmtFunctionToInFact(stmt, in);
        }
        return out.copyFrom(in);
    }

    /**
     * Apply the transfer function to in fact.
     */
    private void applyStmtFunctionToInFact(Stmt stmt, CPFact in) {
        Var var = (Var) stmt.getDef().get();

        List<RValue> uses = stmt.getUses();
        if (uses == null || uses.isEmpty()) {
            return;
        }

        RValue rExp = uses.get(uses.size() - 1);  // the last elem is binary expression.
        in.update(var, evaluate(rExp, in));
    }

    /** @return true if the given stmt contains defined int variable. */
    public boolean isIntDefinitionVarDef(Stmt stmt) {
        Optional<LValue> lValueOptional = stmt.getDef();
        /* No variables are updated, out is not changed. */
        if (lValueOptional.isEmpty()) {
            return false;
        }

        LValue lValue = lValueOptional.get();
        /* The variable is not Var type or is not int type (We only focus on the int variables) */
        return lValue instanceof Var && canHoldInt((Var) lValue);
    }

    /**
     * @return true if the given variable can hold integer value, otherwise false.
     */
    public static boolean canHoldInt(Var var) {
        Type type = var.getType();
        if (type instanceof PrimitiveType) {
            switch ((PrimitiveType) type) {
                case BYTE:
                case SHORT:
                case INT:
                case CHAR:
                case BOOLEAN:
                    return true;
            }
        }
        return false;
    }

    /**
     * Evaluates the {@link Value} of given expression.
     *
     * @param exp the expression to be evaluated
     * @param in IN fact of the statement
     * @return the resulting {@link Value}
     */
    public Value evaluate(Exp exp, CPFact in) {
        if (exp instanceof Var var) {
            return in.get(var);
        } else if (exp instanceof IntLiteral intLiteral) {  // exp is an integer literal
            return Value.makeConstant((intLiteral).getValue());
        } else if (exp instanceof BinaryExp binaryExp) {  // exp is BinaryExp
            return evaluateBinaryExp(binaryExp, in);
        } else if (exp instanceof FieldAccess fieldAccess) {  // a = x.f
            return evaluateFieldAccess(fieldAccess, in, null);
        } else {
            return Value.getNAC();
        }
    }

    /** @return the Value of the */
    private Value evaluateFieldAccess(FieldAccess fieldAccess, CPFact in,
            PointerAnalysisResult pointerAnalysisResult
    ) {
        // 这里如果是怎么判断fieldAccess是静态的呢？
        // 这里要in是没用的，因为CPFact是var→Value的映射
        // 计算fieldAccess的Value，需要找到所有的别名，然后找到所有别名相关的Store语句
        /*if (fieldAccess instanceof StaticFieldAccess staticFieldAccess) {

        } else if (fieldAccess instanceof InstanceFieldAccess instanceFieldAccess) {
            // 找到这个的base变量
            instanceFieldAccess.accept()
        } else {

        }*/
        ExpEvaluator expEvaluator = new ExpEvaluator(in, pointerAnalysisResult);
        return fieldAccess.accept(expEvaluator);
    }

    private class ExpEvaluator implements ExpVisitor<Value> {
        private final CPFact in;
        private final PointerAnalysisResult pointerAnalysisResult;

        /** alias.get(a) return all alias of variable a (including itself) */
        private final Map<Var, Set<Var>> aliasesCache;

        public ExpEvaluator(CPFact in, PointerAnalysisResult pointerAnalysisResult
        ) {
            this.in = in;
            this.pointerAnalysisResult = pointerAnalysisResult;
            this.aliasesCache = new HashMap<>();
        }

        /** @return the value corresponding to the var in the in fact. */
        public Value visit(Var var) {
            return in.get(var);
        }

        /** @return the true value of int literal. */
        public Value visit(IntLiteral intLiteral) {
            return Value.makeConstant(intLiteral.getValue());
        }

        /** @return the result of the binary expression. */
        public Value visit(BinaryExp binaryExp) {
            /* binaryExp only contains the symbol of operand1 and operand2, but the values of the
        operands exits in the in fact. */
            Value operand1Val = in.get(binaryExp.getOperand1());
            Value operand2Val = in.get(binaryExp.getOperand2());

            if (isNACDivOrRemZero(binaryExp, operand1Val, operand2Val)) {
                return Value.getUndef();
            } else if (operand1Val.isNAC() || operand2Val.isNAC()) {
                return Value.getNAC();
            } else if (operand1Val.isUndef() || operand2Val.isUndef()) {
                return Value.getUndef();
            } else { // operand1 and operand2 are both constants.
                return BinaryExpEvaluation.evaluateBinaryOps(binaryExp, operand1Val, operand2Val);
            }
        }

        /** @return true if the exp is (NAC / 0) or (NAC % 0), false otherwise. */
        private static boolean isNACDivOrRemZero(BinaryExp binaryExp, Value operand1, Value operand2) {
            /* Check Type. */
            if (!(binaryExp instanceof ArithmeticExp) || !operand1.isNAC() || !operand2.isConstant()) {
                return false;
            }

            /* Check Value. */
            ArithmeticExp.Op op = ((ArithmeticExp) binaryExp).getOperator();
            return (op.equals(ArithmeticExp.Op.DIV) || op.equals(ArithmeticExp.Op.REM)) // op check
                    && operand2.getConstant() == 0;
        }

        /** @return the value of the instance field access: x.f */
        @Override
        public Value visit(InstanceFieldAccess instanceFieldAccess) {
            // 首先获取base变量a,b,c，然后得到a,b,c相关的a.f, b.f, c.f的语句
            Var base = instanceFieldAccess.getBase();
            Set<Var> aliases = getAlias(base);
            // 然后得到对应的store语句
            return null;
        }

        /** @return the meet value of all aliases with given field store statement. */
        private Value meetAliasField(Set<Var> aliases, JField field) {
            Value res = Value.getUndef();
            for (Var alias : aliases) {
                res = meetValueStoredInGivenField(alias, field);
            }
            return res;
        }

        /** @return the meet value of all var.field = ... */
        private Value meetValueStoredInGivenField(Var var, JField field) {
            Value res = Value.getUndef();
            for (StoreField storeField : var.getStoreFields()) {
                if (storeField.getFieldRef().resolve().equals(field)) {
                    res = meetValue(res, storeField.getRValue().accept(this));
                }
            }
            return res;
        }

        /** @return all alias of given var, including itself. */
        private Set<Var> getAlias(Var var) {
            Set<Var> aliasOfVar = aliasesCache.getOrDefault(var, null);
            if (aliasOfVar == null) {
                aliasOfVar = findAliasOf(var);
                aliasesCache.put(var, aliasOfVar);
            }
            return aliasOfVar;
        }

        /** @return all alias of given var, including itself. */
        private Set<Var> findAliasOf(Var var) {

            HashSet<Var> aliasOfVar = new HashSet<>();
            Set<Obj> ptsOfVar = pointerAnalysisResult.getPointsToSet(var);

            for (Var otherVar : pointerAnalysisResult.getVars()) {
                if (Collections.disjoint(ptsOfVar,
                        pointerAnalysisResult.getPointsToSet(otherVar))) {
                    aliasOfVar.add(otherVar);
                }
            }

            return aliasOfVar;
        }
    }


    private static Value evaluateBinaryExp(BinaryExp binaryExp, CPFact in) {
        /* binaryExp only contains the symbol of operand1 and operand2, but the values of the
        operands exits in the in fact. */
        Value operand1Val = in.get(binaryExp.getOperand1());
        Value operand2Val = in.get(binaryExp.getOperand2());

        if (isNACDivOrRemZero(binaryExp, operand1Val, operand2Val)) {
            return Value.getUndef();
        } else if (operand1Val.isNAC() || operand2Val.isNAC()) {
            return Value.getNAC();
        } else if (operand1Val.isUndef() || operand2Val.isUndef()) {
            return Value.getUndef();
        } else { // operand1 and operand2 are both constants.
            return BinaryExpEvaluation.evaluateBinaryOps(binaryExp, operand1Val, operand2Val);
        }
    }

    /** @return true if the exp is (NAC / 0) or (NAC % 0), false otherwise. */
    private static boolean isNACDivOrRemZero(BinaryExp binaryExp, Value operand1, Value operand2) {
        /* Check Type. */
        if (!(binaryExp instanceof ArithmeticExp) || !operand1.isNAC() || !operand2.isConstant()) {
            return false;
        }

        /* Check Value. */
        ArithmeticExp.Op op = ((ArithmeticExp) binaryExp).getOperator();
        return (op.equals(ArithmeticExp.Op.DIV) || op.equals(ArithmeticExp.Op.REM)) // op check
                && operand2.getConstant() == 0;
    }


    /**
     * Util Class to evaluate the binary expression.
     * These methods should be implemented in BinaryExp class and its subclass for polymorphism.
     */
    private static class BinaryExpEvaluation {

        /** Util class can not be instantiated. */
        private BinaryExpEvaluation() {
        }

        /**
         * @param binaryExp a binary expression contains the symbols of operand1 and operand2
         * @param operand1 the Value of operand1, which is a constant Value
         * @param operand2 the Value of operand2, which is a constant Value
         * @return the operation result of operand1 & operand2.
         */
        private static Value evaluateBinaryOps(BinaryExp binaryExp, Value operand1, Value operand2
        ) {
            int operand1Val = operand1.getConstant();
            int operand2Val = operand2.getConstant();

            if (binaryExp instanceof ArithmeticExp) {
                return evaluateArithmeticOp(((ArithmeticExp) binaryExp).getOperator(), operand1Val,
                        operand2Val);
            } else if (binaryExp instanceof ConditionExp) {
                return evaluateConditionOp(((ConditionExp) binaryExp).getOperator(), operand1Val,
                        operand2Val);
            } else if (binaryExp instanceof ShiftExp) {
                return evaluateShiftOp(((ShiftExp) binaryExp).getOperator(), operand1Val,
                        operand2Val);
            } else if (binaryExp instanceof BitwiseExp) {
                return evaluateBitwiseOp(((BitwiseExp) binaryExp).getOperator(), operand1Val,
                        operand2Val);
            } else {
                throw new AnalysisException(
                        "The type of given binary expression is illegal, it must be arithmetic, "
                                + "condition, shift or bitwise operation.");
            }
        }

        /** @return the result of operand1 `arithmeticOp` operand2. */
        private static Value evaluateArithmeticOp(ArithmeticExp.Op arithmeticOp, int operand1,
                int operand2
        ) {
            return switch (arithmeticOp) {
                case ADD -> Value.makeConstant(operand1 + operand2);
                case SUB -> Value.makeConstant(operand1 - operand2);
                case MUL -> Value.makeConstant(operand1 * operand2);
                case DIV ->
                        operand2 == 0 ? Value.getUndef() : Value.makeConstant(operand1 / operand2);
                case REM ->
                        operand2 == 0 ? Value.getUndef() : Value.makeConstant(operand1 % operand2);
            };
        }

        /** @return the result of operand1 `conditionOp` operand2. */
        private static Value evaluateConditionOp(ConditionExp.Op conditionOp, int operand1,
                int operand2
        ) {
            boolean resVal = switch (conditionOp) {
                case EQ -> operand1 == operand2;
                case NE -> operand1 != operand2;
                case LT -> operand1 < operand2;
                case GT -> operand1 > operand2;
                case LE -> operand1 <= operand2;
                case GE -> operand1 >= operand2;
            };
            return resVal ? Value.makeConstant(1) : Value.makeConstant(0);
        }

        /** @return the result of operand1 `ShiftOp` operand2. */
        private static Value evaluateShiftOp(ShiftExp.Op ShiftOp, int operand1, int operand2) {
            int resVal = switch (ShiftOp) {
                case SHL -> operand1 << operand2;
                case SHR -> operand1 >> operand2;
                case USHR -> operand1 >>> operand2;
            };
            return Value.makeConstant(resVal);
        }

        /** @return the result of operand1 `bitwiseOp` operand2. */
        private static Value evaluateBitwiseOp(BitwiseExp.Op bitwiseOp, int operand1, int operand2
        ) {
            int resVal = switch (bitwiseOp) {
                case OR -> operand1 | operand2;
                case AND -> operand1 & operand2;
                case XOR -> operand1 ^ operand2;
            };
            return Value.makeConstant(resVal);
        }
    }
}
