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

package pascal.taie.analysis.pta.ci;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import pascal.taie.World;
import pascal.taie.analysis.graph.callgraph.CallGraphs;
import pascal.taie.analysis.graph.callgraph.CallKind;
import pascal.taie.analysis.graph.callgraph.DefaultCallGraph;
import pascal.taie.analysis.graph.callgraph.Edge;
import pascal.taie.analysis.pta.core.heap.HeapModel;
import pascal.taie.analysis.pta.core.heap.Obj;
import pascal.taie.ir.exp.InvokeExp;
import pascal.taie.ir.exp.Var;
import pascal.taie.ir.proginfo.MethodRef;
import pascal.taie.ir.stmt.*;
import pascal.taie.language.classes.*;
import pascal.taie.util.AnalysisException;
import pascal.taie.language.type.Type;

import java.util.List;

class Solver {

    private static final Logger logger = LogManager.getLogger(Solver.class);

    private final HeapModel heapModel;

    private DefaultCallGraph callGraph;

    private PointerFlowGraph pointerFlowGraph;

    private WorkList workList;

    private StmtProcessor stmtProcessor;

    private ClassHierarchy hierarchy;

    Solver(HeapModel heapModel) {
        this.heapModel = heapModel;
    }

    /**
     * Runs pointer analysis algorithm.
     */
    void solve() {
        initialize();
        analyze();
    }

    /**
     * Initializes pointer analysis.
     */
    private void initialize() {
        workList = new WorkList();
        pointerFlowGraph = new PointerFlowGraph();
        callGraph = new DefaultCallGraph();
        stmtProcessor = new StmtProcessor();
        hierarchy = World.get().getClassHierarchy();
        // initialize main method
        JMethod main = World.get().getMainMethod();
        callGraph.addEntryMethod(main);
        addReachable(main);
    }

    /**
     * Processes new reachable method.
     */
    private void addReachable(JMethod method) {
        if (callGraph.contains(method)) {
            return;
        }
        callGraph.addReachableMethod(method);

        for (Stmt stmt : method.getIR().getStmts()) {
            // process new statement.
            if (stmt instanceof New newStmt) {
                workList.addEntry(pointerFlowGraph.getVarPtr(newStmt.getLValue()),
                        new PointsToSet(heapModel.getObj(newStmt)));
            } else {
                // add edge for assign, static load, static store and static call statements.
                processAssignStaticLoadStoreCallStmt(stmt);
            }
        }
    }

    /**
     * Add the determined edges in the pointer-flow graph.
     * i.e. process the following statements:
     * - Copy: x = y, x ← y
     * - static StoreField: T.f = y, T.f ← y
     * - static LoadField: y = T.f, y ← T.f
     * - static invoke: x = T.m(), param ← arg, x ← ret
     *
     * @return true if add an edge in PFG, false otherwise.
     */
    private void processAssignStaticLoadStoreCallStmt(Stmt stmt) {
        if (stmt instanceof Invoke invoke) {
            if (invoke.isStatic())
                processStaticInvoke(invoke);
        } else {
            processAssignAndStaticLoadStoreStmts(stmt);
        }
    }


    /** Process the assign, static load and static store statements. */
    private void processAssignAndStaticLoadStoreStmts(Stmt stmt) {
        Pointer source = null, target = null;
        /* Copy: x = y; Edge: x ← y */
        if (stmt instanceof Copy copyStmt) {
            source = pointerFlowGraph.getVarPtr(copyStmt.getRValue());
            target = pointerFlowGraph.getVarPtr(copyStmt.getLValue());
            /* Static StoreField: T.f = y; Edge: T.f ← y */
        } else if (stmt instanceof StoreField storeFieldStmt && storeFieldStmt.isStatic()) {
            source = pointerFlowGraph.getVarPtr(storeFieldStmt.getRValue());
            target = pointerFlowGraph.getStaticField(storeFieldStmt.getFieldRef().resolve());
            /* Static LoadField: y = T.f; Edge: y ← T.f */
        } else if (stmt instanceof LoadField loadFieldStmt && loadFieldStmt.isStatic()) {
            source = pointerFlowGraph.getStaticField(loadFieldStmt.getFieldRef().resolve());
            target = pointerFlowGraph.getVarPtr(loadFieldStmt.getLValue());
        } else {
            return;
        }

        if (source == null || target == null) {
            throw new AnalysisException(
                    "The lValue or the rValue is absent in this assign statement: " + stmt);
        }
        addPFGEdge(source, target);
    }

    /** process the static invoke statement, i.e. add edges: params ← args, var ← return */
    private void processStaticInvoke(Invoke callSite) {
        // dispatch static method
        JMethod callee = resolveCallee(null, callSite);
        addCallPFGAndCGEdge(CallKind.STATIC, callSite, callee);
    }

    /** Add the PFG Edge And call edge according to given call site and callee. */
    private void addCallPFGAndCGEdge(CallKind kind, Invoke callSite, JMethod callee) {
        if (callGraph.addEdge(new Edge(kind, callSite, callee))) {
            addReachable(callee);
            // add edges from args to params
            addEdgesFromArgsToParams(callSite, callee);
            // add edge from return vars to receiver var.
            addEdgesFromRetToRecVar(callSite, callee);
        }
    }

    /** add the edges from the args to params. */
    private void addEdgesFromArgsToParams(Invoke invokeStmt, JMethod method) {
        // add edges from args to params
        List<Var> args = invokeStmt.getInvokeExp().getArgs();
        List<Var> params = method.getIR().getParams();
        if (args.size() != params.size()) {
            throw new AnalysisException("the numbers of args and params do not match!");
        }
        for (int i = 0; i < args.size(); i++) {
            addPFGEdge(pointerFlowGraph.getVarPtr(args.get(i)),
                    pointerFlowGraph.getVarPtr(params.get(i)));
        }
    }

    /** Add the edge from the return value to receiver var. */
    private void addEdgesFromRetToRecVar(Invoke invokeStmt, JMethod method) {
        Var defVar = invokeStmt.getLValue();
        if (defVar == null) {
            return;
        }
        List<Var> returnVars = method.getIR().getReturnVars();
        VarPtr recPtr = pointerFlowGraph.getVarPtr(defVar);
        for (Var returnVar : returnVars) {
            addPFGEdge(pointerFlowGraph.getVarPtr(returnVar), recPtr);
        }
    }

    /**
     * Processes statements in new reachable methods.
     */
    private class StmtProcessor implements StmtVisitor<Void> {
        // TODO - if you choose to implement addReachable()
        //  via visitor pattern, then finish me
    }

    /**
     * Adds an edge "source -> target" to the PFG.
     */
    private void addPFGEdge(Pointer source, Pointer target) {
        if (pointerFlowGraph.addEdge(source, target)) {
            PointsToSet pointsToSet = source.getPointsToSet();
            if (pointsToSet != null) {
                workList.addEntry(target, pointsToSet);
            }
        }
    }

    /**
     * Processes work-list entries until the work-list is empty.
     */
    private void analyze() {
        while (!workList.isEmpty()) {
            WorkList.Entry entry = workList.pollEntry();
            Pointer pointer = entry.pointer();

            PointsToSet ptsToPropagate = propagate(pointer, entry.pointsToSet());

            if (pointer instanceof VarPtr varPtr && !ptsToPropagate.isEmpty()) {
                processFieldAndArrayAccessAndCall(varPtr, ptsToPropagate);
            }
        }
    }

    /**
     * Update the edges corresponding to the instance load & store statements,
     * i.e. x.f = y, y = x.f, var.arr = y, y = var.arr
     */
    private void processFieldAndArrayAccessAndCall(VarPtr varPtr, PointsToSet ptsToPropagate) {
        Var var = varPtr.getVar();
        // for each obj → pts(var)
        for (Obj obj : ptsToPropagate) {
            updateEdgesOfFieldAndArrayAccess(obj, var);
            processCall(var, obj);
        }
    }

    /** obj → pts(var), update the edges. */
    private void updateEdgesOfFieldAndArrayAccess(Obj obj, Var var) {
        // process store field: var.f = y, obj.f ← y
        updateEdgesOfStoreField(obj, var);
        // process load field: y = var.f, y ← obj.f
        updateEdgesOfLoadField(obj, var);
        // hand store array: var.arr = y, obj.arr ← y
        updateEdgesOfStoreArray(obj, var);
        // hand load array: y = var.arr, y ← obj.arr
        updateEdgesOfLoadArray(obj, var);
    }

    /** obj → pts(var), then for load field y = var.f, add edge: y ← var.f */
    private void updateEdgesOfLoadField(Obj obj, Var var) {
        for (LoadField loadField : var.getLoadFields()) {
            JField field = loadField.getFieldRef().resolve();
            InstanceField loadFieldPointer = pointerFlowGraph.getInstanceField(obj, field);
            VarPtr yVarPtr = pointerFlowGraph.getVarPtr(loadField.getLValue());
            addPFGEdge(loadFieldPointer, yVarPtr);
        }
    }

    /** obj → pts(var), then for store field var.f = y, add edge: var.f ← y */
    private void updateEdgesOfStoreField(Obj obj, Var var) {
        for (StoreField storeField : var.getStoreFields()) {
            JField field = storeField.getFieldRef().resolve();
            InstanceField storeFieldPointer = pointerFlowGraph.getInstanceField(obj, field);
            VarPtr yVarPtr = pointerFlowGraph.getVarPtr(storeField.getRValue());
            addPFGEdge(yVarPtr, storeFieldPointer);
        }
    }

    /** obj → pts(var), then for store field var.arr = y, add edge: var.arr ← y */
    private void updateEdgesOfStoreArray(Obj obj, Var var) {
        List<StoreArray> storeArrays = var.getStoreArrays();
        if (storeArrays.isEmpty()) {
            return;
        }
        ArrayIndex arrayIndex = pointerFlowGraph.getArrayIndex(obj);
        for (StoreArray storeArray : storeArrays) {
            VarPtr yVarPtr = pointerFlowGraph.getVarPtr(storeArray.getRValue());
            addPFGEdge(yVarPtr, arrayIndex);
        }
    }

    /** obj → pts(var), then for load field y = var.arr, add edge: y ← var.arr */
    private void updateEdgesOfLoadArray(Obj obj, Var var) {
        List<LoadArray> loadArrays = var.getLoadArrays();
        if (loadArrays.isEmpty()) {
            return;
        }
        ArrayIndex arrayIndex = pointerFlowGraph.getArrayIndex(obj);
        for (LoadArray loadArray : loadArrays) {
            VarPtr yVarPtr = pointerFlowGraph.getVarPtr(loadArray.getLValue());
            addPFGEdge(arrayIndex, yVarPtr);
        }
    }

    /**
     * Propagates pointsToSet to pt(pointer) and its PFG successors,
     * returns the difference set of pointsToSet and pt(pointer).
     */
    private PointsToSet propagate(Pointer pointer, PointsToSet pointsToSet) {
        PointsToSet ptsInPFG = pointer.getPointsToSet();
        PointsToSet ptsToPropagate = new PointsToSet();

        for (Obj obj : pointsToSet) {
            if (ptsInPFG.addObject(obj)) {
                ptsToPropagate.addObject(obj);
            }
        }

        if (!ptsToPropagate.isEmpty()) {
            for (Pointer succ : pointerFlowGraph.getSuccsOf(pointer)) {
                workList.addEntry(succ, ptsToPropagate);
            }
        }

        return ptsToPropagate;
    }

    /**
     * Processes instance calls when points-to set of the receiver variable changes.
     *
     * @param var the variable that holds receiver objects
     * @param recv a new discovered object pointed by the variable.
     */
    private void processCall(Var var, Obj recv) {
        // invoke: l: r = x.k (a1,...,an)
        for (Invoke callSite : var.getInvokes()) {
            JMethod callee = resolveCallee(recv, callSite);
            workList.addEntry(pointerFlowGraph.getVarPtr(callee.getIR().getThis()),
                    new PointsToSet(recv));
            addCallPFGAndCGEdge(CallKind.VIRTUAL, callSite, callee);
        }
    }

    /**
     * Resolves the callee of a call site with the receiver object.
     *
     * @param recv the receiver object of the method call. If the callSite
     * is static, this parameter is ignored (i.e., can be null).
     * @param callSite the call site to be resolved.
     * @return the resolved callee.
     */
    private JMethod resolveCallee(Obj recv, Invoke callSite) {
        Type type = recv != null ? recv.getType() : null;
        return CallGraphs.resolveCallee(type, callSite);
    }

    CIPTAResult getResult() {
        return new CIPTAResult(pointerFlowGraph, callGraph);
    }
}
