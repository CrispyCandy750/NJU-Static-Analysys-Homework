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
import pascal.taie.config.AnalysisConfig;
import pascal.taie.ir.exp.*;
import pascal.taie.ir.stmt.Stmt;
import pascal.taie.language.type.PrimitiveType;
import pascal.taie.language.type.Type;
import pascal.taie.util.AnalysisException;

import java.util.List;
import java.util.Optional;

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
        if (isIntVarDef(stmt)) {
            updateInFact(stmt, in);
        }
        return out.copyFrom(in);
    }

    /**
     * Apply the transfer function to in fact.
     *
     * @return true if the stmt contains RValue, which means the transfer function can be applied
     * successfully.
     */
    private boolean updateInFact(Stmt stmt, CPFact in) {
        Var var = (Var) stmt.getDef().get();

        List<RValue> uses = stmt.getUses();
        if (uses == null || uses.isEmpty()) {
            return false;
        }

        RValue rExp = uses.get(uses.size() - 1);  // the last elem is binary expression.
        in.update(var, evaluate(rExp, in));
        return true;
    }

    /** @return true if the given stmt contains defined int variable. */
    private boolean isIntVarDef(Stmt stmt) {
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
    public static Value evaluate(Exp exp, CPFact in) {
        if (exp instanceof Var) {
            return in.get((Var) exp);
        } else if (exp instanceof IntLiteral) {  // exp is an integer literal
            return Value.makeConstant(((IntLiteral) exp).getValue());
        } else if (exp instanceof BinaryExp) {  // exp is BinaryExp
            return evaluateBinaryExp((BinaryExp) exp, in);
        } else {
            return Value.getNAC();
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
