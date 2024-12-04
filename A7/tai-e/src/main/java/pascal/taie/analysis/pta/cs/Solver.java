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

package pascal.taie.analysis.pta.cs;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import pascal.taie.World;
import pascal.taie.analysis.graph.callgraph.CallGraphs;
import pascal.taie.analysis.graph.callgraph.CallKind;
import pascal.taie.analysis.graph.callgraph.Edge;
import pascal.taie.analysis.pta.PointerAnalysisResult;
import pascal.taie.analysis.pta.PointerAnalysisResultImpl;
import pascal.taie.analysis.pta.core.cs.CSCallGraph;
import pascal.taie.analysis.pta.core.cs.context.Context;
import pascal.taie.analysis.pta.core.cs.element.*;
import pascal.taie.analysis.pta.core.cs.selector.ContextSelector;
import pascal.taie.analysis.pta.core.heap.HeapModel;
import pascal.taie.analysis.pta.core.heap.Obj;
import pascal.taie.analysis.pta.pts.PointsToSet;
import pascal.taie.analysis.pta.pts.PointsToSetFactory;
import pascal.taie.config.AnalysisOptions;
import pascal.taie.ir.exp.Var;
import pascal.taie.ir.stmt.*;
import pascal.taie.language.classes.JMethod;
import pascal.taie.language.type.Type;
import pascal.taie.util.AnalysisException;

import java.util.List;

class Solver {

    private static final Logger logger = LogManager.getLogger(Solver.class);

    private final AnalysisOptions options;

    private final HeapModel heapModel;

    private final ContextSelector contextSelector;

    private CSManager csManager;

    private CSCallGraph callGraph;

    private PointerFlowGraph pointerFlowGraph;

    private WorkList workList;

    private PointerAnalysisResult result;

    Solver(AnalysisOptions options, HeapModel heapModel,
            ContextSelector contextSelector
    ) {
        this.options = options;
        this.heapModel = heapModel;
        this.contextSelector = contextSelector;
    }

    void solve() {
        initialize();
        analyze();
    }

    private void initialize() {
        csManager = new MapBasedCSManager();
        callGraph = new CSCallGraph(csManager);
        pointerFlowGraph = new PointerFlowGraph();
        workList = new WorkList();
        // process program entry, i.e., main method
        Context defContext = contextSelector.getEmptyContext();
        JMethod main = World.get().getMainMethod();
        CSMethod csMethod = csManager.getCSMethod(defContext, main);
        callGraph.addEntryMethod(csMethod);
        addReachable(csMethod);
    }

    /**
     * Processes new reachable context-sensitive method.
     */
    private void addReachable(CSMethod csMethod) {
        if (!callGraph.addReachableMethod(csMethod)) {
            return;
        }
        StmtProcessor stmtProcessor = new StmtProcessor(csMethod);
        for (Stmt stmt : csMethod.getMethod().getIR().getStmts()) {
            stmt.accept(stmtProcessor);
        }
    }

    /**
     * Processes the statements in context-sensitive new reachable methods.
     * (Replace the previous StmtProcessor class)
     * These statements create determined edges: New, Copy, static (LoadField, StoreField & Invoke).
     */
    //  private class StmtProcessor implements StmtVisitor<Void>
    private class StmtProcessor implements StmtVisitor<Void> {

        private final CSMethod csMethod;

        private final Context context;

        private StmtProcessor(CSMethod csMethod) {
            this.csMethod = csMethod;
            this.context = csMethod.getContext();
        }

        /** Process the new statement, add (var, pts) to WL */
        public Void visit(New newStmt) {
            // the context of variables are always the context of the methods.
            CSVar csVar = csManager.getCSVar(context, newStmt.getLValue());
            Obj obj = heapModel.getObj(newStmt);
            // the context of the object need to be selected.
            CSObj csObj = csManager.getCSObj(contextSelector.selectHeapContext(csMethod, obj), obj);
            workList.addEntry(csVar, PointsToSetFactory.make(csObj));
            return null;
        }

        /** Process the assign statement x = y, add y → x to PFG. */
        public Void visit(Copy assignStmt) {
            CSVar source = csManager.getCSVar(context, assignStmt.getRValue());
            CSVar target = csManager.getCSVar(context, assignStmt.getLValue());
            addPFGEdge(source, target);
            return null;
        }

        /** Process the static loadField y = T.f, add T.f → y to PFG */
        public Void visit(LoadField loadField) {
            if (loadField.isStatic()) {
                StaticField source = csManager.getStaticField(loadField.getFieldRef().resolve());
                CSVar target = csManager.getCSVar(context, loadField.getLValue());
                addPFGEdge(source, target);
            }
            return null;
        }

        /** Process the static storeField T.f = y, add y → T.f to PFG */
        public Void visit(StoreField storeField) {
            if (storeField.isStatic()) {
                CSVar source = csManager.getCSVar(context, storeField.getRValue());
                StaticField target = csManager.getStaticField(
                        storeField.getFieldRef().resolve());
                addPFGEdge(source, target);
            }
            return null;
        }

        /** Process the static invoke. */
        public Void visit(Invoke invoke) {
            if (invoke.isStatic()) {
//                JMethod callee = invoke.getMethodRef().resolve();
                JMethod callee = resolveCallee(null, invoke);
                // select callee context
                Context calleeContext = contextSelector.selectContext(
                        csManager.getCSCallSite(context, invoke), callee);

                addPFGInvokeEdgesAndCGEdge(CallKind.STATIC, context, invoke, calleeContext, callee);
            }
            return null;
        }
    }

    /**
     * Add the call graph edges from csCallSite to csCallee.
     * Add the PFG edges from the args to params, from return variable to assignee variable.
     */
    private void addPFGInvokeEdgesAndCGEdge(CallKind kind, Context callerContext, Invoke callSite,
            Context calleeContext, JMethod callee
    ) {
        CSCallSite csCallSite = csManager.getCSCallSite(callerContext, callSite);
        CSMethod csCallee = csManager.getCSMethod(calleeContext, callee);

        if (callGraph.addEdge(new Edge(kind, csCallSite, csCallee))) {
            addReachable(csCallee);
            // args → params
            addPFGInvokeEdgesFromArgsToParams(callerContext, callSite, calleeContext, callee);
            // ret → assignee
            addPFGInvokeEdgesFromRetToAssignee(callerContext, callSite, calleeContext, callee);
        }
    }

    /** Add the edges of invoke statements from args to params. */
    private void addPFGInvokeEdgesFromArgsToParams(Context callerContext, Invoke invoke,
            Context calleeContext, JMethod callee
    ) {
        List<Var> args = invoke.getInvokeExp().getArgs();
        List<Var> params = callee.getIR().getParams();

        if (args.size() != params.size()) {
            throw new AnalysisException("the numbers of args and params do not match!");
        }

        for (int i = 0; i < args.size(); i++) {
            // add edge: caller:arg → callee:param
            addPFGEdge(csManager.getCSVar(callerContext, args.get(i)),
                    csManager.getCSVar(calleeContext, params.get(i)));
        }
    }

    /** Add the edges of invoke statements from return variables to assignee variable. */
    private void addPFGInvokeEdgesFromRetToAssignee(Context callerContext, Invoke invoke,
            Context calleeContext, JMethod callee
    ) {
        Var assignee = invoke.getLValue();
        if (assignee == null) {
            return;
        }
        List<Var> returnVars = callee.getIR().getReturnVars();
        for (Var returnVar : returnVars) {
            // add edge: callee:return → caller:assignee
            addPFGEdge(csManager.getCSVar(calleeContext, returnVar),
                    csManager.getCSVar(callerContext, assignee));
        }
    }

    /**
     * Adds an edge "source -> target" to the PFG.
     */
    private void addPFGEdge(Pointer source, Pointer target) {
        if (pointerFlowGraph.addEdge(source, target)) {
            PointsToSet pointsToSet = source.getPointsToSet();
            if (!pointsToSet.isEmpty()) {
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
            PointsToSet ptsInWL = entry.pointsToSet();

            PointsToSet ptsToPropagate = propagate(pointer, ptsInWL);

            if (pointer instanceof CSVar csVar) {
                for (CSObj csObj : ptsToPropagate) {
                    updateFieldEdges(csVar, csObj);
                    processCall(csVar, csObj);
                }
            }
        }
    }

    /** csObj → pts(cs), updates the edges of field access statements. */
    private void updateFieldEdges(CSVar csVar, CSObj csObj) {
        updateLoadFieldEdges(csVar, csObj);
        updateStoreFieldEdges(csVar, csObj);
        updateLoadArrayEdges(csVar, csObj);
        updateStoreArrayEdges(csVar, csObj);
    }

    /** obj → pts(var) & y = var.f => obj.f → y */
    private void updateLoadFieldEdges(CSVar csVar, CSObj csObj) {
        Context context = csVar.getContext();
        for (LoadField loadField : csVar.getVar().getLoadFields()) {
            InstanceField source = csManager.getInstanceField(csObj,
                    loadField.getFieldRef().resolve());
            CSVar target = csManager.getCSVar(context, loadField.getLValue());
            addPFGEdge(source, target);
        }
    }

    /** obj → pts(var) & var.f = y => y → obj.f */
    private void updateStoreFieldEdges(CSVar csVar, CSObj csObj) {
        Context context = csVar.getContext();
        for (StoreField storeField : csVar.getVar().getStoreFields()) {
            CSVar source = csManager.getCSVar(context, storeField.getRValue());
            InstanceField target = csManager.getInstanceField(csObj,
                    storeField.getFieldRef().resolve());
            addPFGEdge(source, target);
        }
    }

    /** obj → pts(var) & y = var.arr => obj.arr → y */
    private void updateLoadArrayEdges(CSVar csVar, CSObj csObj) {
        List<LoadArray> loadArrays = csVar.getVar().getLoadArrays();
        if (loadArrays.isEmpty()) {
            return;
        }
        Context context = csVar.getContext();
        ArrayIndex source = csManager.getArrayIndex(csObj);
        for (LoadArray loadArray : loadArrays) {
            CSVar target = csManager.getCSVar(context, loadArray.getLValue());
            addPFGEdge(source, target);
        }
    }

    /** obj → pts(var) & var.arr = y => y → obj.arr */
    private void updateStoreArrayEdges(CSVar csVar, CSObj csObj) {
        List<StoreArray> storeArrays = csVar.getVar().getStoreArrays();
        if (storeArrays.isEmpty()) {
            return;
        }
        Context context = csVar.getContext();
        ArrayIndex target = csManager.getArrayIndex(csObj);
        for (StoreArray storeArray : storeArrays) {
            CSVar source = csManager.getCSVar(context, storeArray.getRValue());
            addPFGEdge(source, target);
        }
    }

    /**
     * Propagates pointsToSet to pt(pointer) and its PFG successors,
     * returns the difference set of pointsToSet and pt(pointer).
     */
    private PointsToSet propagate(Pointer pointer, PointsToSet pointsToSet) {
        PointsToSet ptsInPFG = pointer.getPointsToSet();
        PointsToSet ptsToPropagate = PointsToSetFactory.make();
        for (CSObj csObj : pointsToSet) {
            if (ptsInPFG.addObject(csObj)) {
                ptsToPropagate.addObject(csObj);
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
     * @param recv the receiver variable
     * @param recvObj set of new discovered objects pointed by the variable.
     */
    private void processCall(CSVar recv, CSObj recvObj) {
        // invoke: l: r = x.k(a1,...,an)
        Context callerContext = recv.getContext();
        for (Invoke invoke : recv.getVar().getInvokes()) {
            JMethod callee = resolveCallee(recvObj, invoke);
            Context calleeContext = contextSelector.selectContext(
                    csManager.getCSCallSite(callerContext, invoke), recvObj, callee);
            // add (callee:this, recvObj) to WL
            workList.addEntry(csManager.getCSVar(calleeContext, callee.getIR().getThis()),
                    PointsToSetFactory.make(recvObj));
            addPFGInvokeEdgesAndCGEdge(selectCallKind(invoke), callerContext, invoke, calleeContext,
                    callee);
        }
    }

    /** @return the call kind of the given invoke statement. */
    private CallKind selectCallKind(Invoke invoke) {
        if (invoke.isVirtual()) {
            return CallKind.VIRTUAL;
        } else if (invoke.isInterface()) {
            return CallKind.INTERFACE;
        } else if (invoke.isDynamic()) {
            return CallKind.DYNAMIC;
        } else if (invoke.isSpecial()) {
            return CallKind.SPECIAL;
        } else {
            return CallKind.STATIC;
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
    private JMethod resolveCallee(CSObj recv, Invoke callSite) {
        Type type = recv != null ? recv.getObject().getType() : null;
        return CallGraphs.resolveCallee(type, callSite);
    }

    PointerAnalysisResult getResult() {
        if (result == null) {
            result = new PointerAnalysisResultImpl(csManager, callGraph);
        }
        return result;
    }
}
