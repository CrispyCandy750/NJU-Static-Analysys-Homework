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

package pascal.taie.analysis.dataflow.analysis;

import pascal.taie.analysis.MethodAnalysis;
import pascal.taie.analysis.dataflow.analysis.constprop.CPFact;
import pascal.taie.analysis.dataflow.analysis.constprop.ConstantPropagation;
import pascal.taie.analysis.dataflow.analysis.constprop.Value;
import pascal.taie.analysis.dataflow.fact.DataflowResult;
import pascal.taie.analysis.dataflow.fact.SetFact;
import pascal.taie.analysis.graph.cfg.CFG;
import pascal.taie.analysis.graph.cfg.CFGBuilder;
import pascal.taie.analysis.graph.cfg.Edge;
import pascal.taie.config.AnalysisConfig;
import pascal.taie.ir.IR;
import pascal.taie.ir.exp.*;
import pascal.taie.ir.stmt.AssignStmt;
import pascal.taie.ir.stmt.If;
import pascal.taie.ir.stmt.Stmt;
import pascal.taie.ir.stmt.SwitchStmt;

import java.util.*;

public class DeadCodeDetection extends MethodAnalysis {

    public static final String ID = "deadcode";

    public DeadCodeDetection(AnalysisConfig config) {
        super(config);
    }

    @Override
    public Set<Stmt> analyze(IR ir) {
        // obtain CFG
        CFG<Stmt> cfg = ir.getResult(CFGBuilder.ID);
        // obtain result of constant propagation
        DataflowResult<Stmt, CPFact> constants =
                ir.getResult(ConstantPropagation.ID);
        // obtain result of live variable analysis
        DataflowResult<Stmt, SetFact<Var>> liveVars =
                ir.getResult(LiveVariableAnalysis.ID);
        // keep statements (dead code) sorted in the resulting set
        Set<Stmt> deadCode = new TreeSet<>(Comparator.comparing(Stmt::getIndex));
        // Your task is to recognize dead code in ir and add it to deadCode
        fillDeadCode(deadCode, cfg, constants, liveVars);
        return deadCode;
    }

    /**
     * fill the given deadCode set (implemented by adding all statements in the set and removing
     * live codes.)
     *
     * @param deadCode an empty set of Stmt which will be filled with dead code.
     * @param cfg the control flow graph.
     * @param constPropRes the constant propagation result.
     * @param liveVarRes the live variable result.
     */
    private static void fillDeadCode(Set<Stmt> deadCode, CFG<Stmt> cfg,
            DataflowResult<Stmt, CPFact> constPropRes, DataflowResult<Stmt, SetFact<Var>> liveVarRes
    ) {
        deadCode.addAll(cfg.getIR().getStmts());
        traverseCFGAndRemoveLiveCode(deadCode, cfg, constPropRes, liveVarRes);
    }

    /**
     * Traverse the CFG and remove reachable and live assignment statements.
     *
     * @param deadCode contains all statements and need to remove live statements from this set.
     */
    private static void traverseCFGAndRemoveLiveCode(Set<Stmt> deadCode, CFG<Stmt> cfg,
            DataflowResult<Stmt, CPFact> constPropRes, DataflowResult<Stmt, SetFact<Var>> liveVarRes
    ) {
        Set<Stmt> visitedStmts = new HashSet<>();
        traverseCFGWithDFS(cfg.getEntry(), visitedStmts, cfg, deadCode, constPropRes, liveVarRes);
    }

    /**
     * Traverse the CFG with DFS according the constant propagation result.
     *
     * @param stmt current statement to be handled, this is reachable statements.
     * @param visitedStmts visited statements.
     * @param deadCode contains all statements and need to remove live statements from this set.
     */
    private static void traverseCFGWithDFS(Stmt stmt, Set<Stmt> visitedStmts, CFG<Stmt> cfg,
            Set<Stmt> deadCode, DataflowResult<Stmt, CPFact> constPropRes,
            DataflowResult<Stmt, SetFact<Var>> liveVarRes
    ) {
        /* The stmt is reachable. */
        if (visitedStmts.contains(stmt)) {
            return;
        }
        visitedStmts.add(stmt);

        /* The stmt is reachable and not dead assign statement, i.e. live code. */
        if (!isDeadAssignStmt(stmt, liveVarRes)) {
            deadCode.remove(stmt);
        }

        for (Stmt reachableSucc : getReachableSuccs(stmt, cfg, constPropRes)) {
            traverseCFGWithDFS(reachableSucc, visitedStmts, cfg, deadCode, constPropRes,
                    liveVarRes);
        }
    }

    /* ------------------ dead assignment check ------------------ */

    /** @return true if the given stmt is a dead assignment statement, false otherwise. */
    private static boolean isDeadAssignStmt(Stmt stmt,
            DataflowResult<Stmt, SetFact<Var>> liveVarRes
    ) {
        return stmt instanceof AssignStmt && isUnusedVarAssign((AssignStmt) stmt, liveVarRes)
                && allHaveNoSideEffect(stmt.getUses());
    }

    /** @return true if the assignStmt has left Var and is absent in the liveVarRes. */
    private static boolean isUnusedVarAssign(AssignStmt assignStmt,
            DataflowResult<Stmt, SetFact<Var>> liveVarRes
    ) {
        Optional<LValue> lValueOptional = assignStmt.getDef();
        if (lValueOptional.isEmpty()) {
            return false;
        }
        LValue lValue = lValueOptional.get();
        if (!(lValue instanceof Var)) {
            return false;
        }
        return !liveVarRes.getOutFact(assignStmt).contains((Var) lValue);
    }

    /** @return true if all rValues have no side effect. */
    private static boolean allHaveNoSideEffect(List<RValue> rValues) {
        if (rValues == null || rValues.isEmpty()) {
            return true;
        }
        for (RValue rValue : rValues) {
            if (!hasNoSideEffect(rValue)) {
                return false;
            }
        }
        return true;
    }

    /**
     * @return true if given RValue has no side effect, otherwise false.
     */
    private static boolean hasNoSideEffect(RValue rvalue) {
        // new expression modifies the heap
        if (rvalue instanceof NewExp ||
                // cast may trigger ClassCastException
                rvalue instanceof CastExp ||
                // static field access may trigger class initialization
                // instance field access may trigger NPE
                rvalue instanceof FieldAccess ||
                // array access may trigger NPE
                rvalue instanceof ArrayAccess) {
            return false;
        }
        if (rvalue instanceof ArithmeticExp) {
            ArithmeticExp.Op op = ((ArithmeticExp) rvalue).getOperator();
            // may trigger DivideByZeroException
            return op != ArithmeticExp.Op.DIV && op != ArithmeticExp.Op.REM;
        }
        return true;
    }

    /* ------------------ control-flow unreachable code check ------------------ */
    private static Set<Stmt> getReachableSuccs(Stmt stmt, CFG<Stmt> cfg,
            DataflowResult<Stmt, CPFact> constPropRes
    ) {
        if (stmt instanceof If) {
            return getReachableIfSuccs((If) stmt, cfg, constPropRes);
        } else if (stmt instanceof SwitchStmt) {
            return getReachableSwitchSuccs((SwitchStmt) stmt, cfg, constPropRes);
        } else {
            return cfg.getSuccsOf(stmt);
        }
    }

    /** @return the unique successor if condition is constant, all successors otherwise. */
    private static Set<Stmt> getReachableIfSuccs(If ifStmt, CFG<Stmt> cfg,
            DataflowResult<Stmt, CPFact> constPropRes
    ) {
        HashSet<Stmt> reachableIfBranches = new HashSet<>();
        Value condition = ConstantPropagation.evaluate(ifStmt.getCondition(),
                constPropRes.getInFact(ifStmt));

        if (condition.isConstant()) {
            reachableIfBranches.add(
                    selectIfBranchByCondition(condition.getConstant(), cfg.getOutEdgesOf(ifStmt)));
        } else { // condition is NAC or Undef, add all successors.
            reachableIfBranches.addAll(cfg.getSuccsOf(ifStmt));
        }

        return reachableIfBranches;
    }

    /** @return the reachable branch statements by condition. In Java, true == 1, false == 0. */
    private static Stmt selectIfBranchByCondition(int conditionBoolValue, Set<Edge<Stmt>> ifEdges) {
        for (Edge<Stmt> ifEdge : ifEdges) {
            if (ifEdge.getKind() == Edge.Kind.IF_TRUE && conditionBoolValue == 1) {
                return ifEdge.getTarget();
            } else if (ifEdge.getKind() == Edge.Kind.IF_FALSE && conditionBoolValue == 0) {
                return ifEdge.getTarget();
            }
        }
        return null;
    }

    /** @return the unique successor if switch-constant is constant, all successors otherwise. */
    private static Set<Stmt> getReachableSwitchSuccs(SwitchStmt switchStmt, CFG<Stmt> cfg,
            DataflowResult<Stmt, CPFact> constPropRes
    ) {
        HashSet<Stmt> reachableSwitchSuccs = new HashSet<>();
        Value condition = ConstantPropagation.evaluate(switchStmt.getVar(),
                constPropRes.getInFact(switchStmt));

        if (condition.isConstant()) {
            reachableSwitchSuccs.add(selectSwitchBranchByCondition(condition.getConstant(),
                    cfg.getOutEdgesOf(switchStmt)));
        } else {  // condition is NAC or undef
            reachableSwitchSuccs.addAll(cfg.getSuccsOf(switchStmt));
        }
        return reachableSwitchSuccs;
    }

    /** @return the reachable branch statements by actual condition value. */
    private static Stmt selectSwitchBranchByCondition(int actualConditionValue,
            Set<Edge<Stmt>> edges
    ) {
        Edge<Stmt> defaultEdge = null;
        for (Edge<Stmt> edge : edges) {
            if (edge.getKind() == Edge.Kind.SWITCH_CASE) {
                if (edge.getCaseValue() == actualConditionValue) {
                    return edge.getTarget();
                }
            } else { // Default case
                defaultEdge = edge;
            }
        }
        assert defaultEdge != null;
        return defaultEdge.getTarget();
    }
}
