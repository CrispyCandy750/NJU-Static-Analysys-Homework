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

package pascal.taie.analysis.pta.plugin.taint;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import pascal.taie.World;
import pascal.taie.analysis.pta.PointerAnalysisResult;
import pascal.taie.analysis.pta.core.cs.context.Context;
import pascal.taie.analysis.pta.core.cs.element.CSManager;
import pascal.taie.analysis.pta.core.cs.element.CSObj;
import pascal.taie.analysis.pta.core.cs.element.Pointer;
import pascal.taie.analysis.pta.core.heap.Obj;
import pascal.taie.analysis.pta.cs.Solver;
import pascal.taie.analysis.pta.pts.PointsToSet;
import pascal.taie.analysis.pta.pts.PointsToSetFactory;
import pascal.taie.ir.exp.InvokeExp;
import pascal.taie.ir.exp.InvokeInstanceExp;
import pascal.taie.ir.exp.Var;
import pascal.taie.ir.stmt.Invoke;
import pascal.taie.language.classes.JMethod;
import pascal.taie.language.type.Type;
import pascal.taie.util.collection.Maps;
import pascal.taie.util.collection.MultiMap;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;

public class TaintAnalysiss {

    private static final Logger logger = LogManager.getLogger(TaintAnalysiss.class);

    private final TaintManager manager;

    private final TaintConfig config;

    private final Solver solver;

    private final CSManager csManager;

    private final Context emptyContext;

    private final TaintConfigProcessor configProcessor;

    private final TaintAnalysisSolver taintAnalysisSolver;

    public TaintAnalysiss(Solver solver) {
        manager = new TaintManager();
        this.solver = solver;
        csManager = solver.getCSManager();
        emptyContext = solver.getContextSelector().getEmptyContext();
        config = TaintConfig.readConfig(
                solver.getOptions().getString("taint-config"),
                World.get().getClassHierarchy(),
                World.get().getTypeSystem());
        logger.info(config);

        configProcessor = new TaintConfigProcessor(config);
        taintAnalysisSolver = new TaintAnalysisSolver();
    }

    /** Process the specific method about taint analysis: Source, Sink and TaintTransfer. */
    public void taintProcessCall(Context callerContext, Invoke callSite, JMethod callee) {
        taintAnalysisSolver.processSource(callerContext, callSite, callee);
        taintAnalysisSolver.buildTaintFlowGraph(callerContext, callSite, callee);
    }


    /** @return true if the csObj is taint obj, false otherwise. */
    public boolean isTaintObj(CSObj csObj) {
        return csObj.getContext().equals(emptyContext) && manager.isTaint(csObj.getObject());
    }

    /** Propagate the taint objects with special paths. */
    public void taintPropagate(Pointer pointer, PointsToSet delta) {
        taintAnalysisSolver.taintPropagateToTaintSuccs(pointer, delta);
    }

    public void onFinish() {
        Set<TaintFlow> taintFlows = collectTaintFlows();
        solver.getResult().storeResult(getClass().getName(), taintFlows);
    }

    private Set<TaintFlow> collectTaintFlows() {
        Set<TaintFlow> taintFlows = new TreeSet<>();
        PointerAnalysisResult result = solver.getResult();
        // You could query pointer analysis results you need via variable result.
        analyzeResult(taintFlows, result);
        return taintFlows;
    }

    /** Analyze the pointer analysis result and find the taint flows. */
    private void analyzeResult(Set<TaintFlow> taintFlows, PointerAnalysisResult result) {
        result.getCallGraph().edges().forEach(edge -> {
            analyzeInvoke(edge.getCallSite(), edge.getCallee(), taintFlows, result);
        });
    }

    /** Analyze one invoke → callee and find the taint flows. */
    private void analyzeInvoke(
            Invoke callSite, JMethod callee, Set<TaintFlow> taintFlows, PointerAnalysisResult result
    ) {
        for (Sink sink : configProcessor.getSinks(callee)) {
            Var sensitiveArg = callSite.getInvokeExp().getArgs().get(sink.index());
            for (Obj obj : result.getPointsToSet(sensitiveArg)) {
                if (manager.isTaint(obj)) {
                    taintFlows.add(
                            new TaintFlow(manager.getSourceCall(obj), callSite, sink.index()));
                }
            }
        }
    }

    /** Cache the map of TaintConfig */
    private class TaintConfigProcessor {
        private final Map<JMethod, Set<Source>> sourceGroup;
        private final Map<JMethod, Set<Sink>> sinkGroup;
        private final Map<JMethod, Set<TaintTransfer>> taintTransferGroup;

        TaintConfigProcessor(TaintConfig config) {
            sourceGroup = groupHelper(config.getSources(), Source::method);
            sinkGroup = groupHelper(config.getSinks(), Sink::method);
            taintTransferGroup = groupHelper(config.getTransfers(), TaintTransfer::method);
        }

        /** @return grouped items with given groupBy method. */
        private <Key, Item> Map<Key, Set<Item>> groupHelper(Set<Item> items,
                Function<Item, Key> groupBy
        ) {
            return items.stream().collect(Collectors.groupingBy(
                    groupBy,
                    Collectors.toSet()
            ));
        }

        /** @return all sources corresponding to the given method. */
        Set<Source> getSources(JMethod method) {
            return sourceGroup.getOrDefault(method, new HashSet<>());
        }

        /** @return all sinks corresponding to the given method. */
        Set<Sink> getSinks(JMethod method) {
            return sinkGroup.getOrDefault(method, new HashSet<>());
        }

        /** @return all taintTransfers corresponding to the given method. */
        Set<TaintTransfer> getTaintTransfers(JMethod method) {
            return taintTransferGroup.getOrDefault(method, new HashSet<>());
        }
    }

    /**
     * Represents taint flow graph in context-sensitive pointer analysis.
     */
    private class TaintFlowGraph {
        /**
         * Map from a pointer (node) to its successors in TFG.
         */
        private final MultiMap<Pointer, TaintTargetPointer> successors = Maps.newMultiMap();

        /**
         * Adds an edge (source -> target) to this TFG.
         *
         * @return true if this PFG changed as a result of the call,
         * otherwise false.
         */
        boolean addEdge(Pointer source, Pointer target, Type typeOfTaintObj) {
            return successors.put(source, new TaintTargetPointer(target, typeOfTaintObj));
        }

        /**
         * @return successors of given pointer in the TFG.
         */
        Set<TaintTargetPointer> getSuccsOf(Pointer pointer) {
            return successors.get(pointer);
        }
    }

    private record TaintTargetPointer(Pointer pointer, Type typeOfTaintObj) {
    }

    private class TaintAnalysisSolver {
        private final TaintFlowGraph taintFlowGraph;

        TaintAnalysisSolver() {
            taintFlowGraph = new TaintFlowGraph();
        }

        /**
         * Process the {@link Source}
         * If callee is Source, create taint @{link Source} the assignee of callSite, otherwise do
         * nothing.
         *
         * @param callSite must be in the call site.
         */
        void processSource(Context callerContext, Invoke callSite, JMethod callee) {
            Var result = callSite.getLValue();
            if (result == null) {
                return;
            }

            PointsToSet taints = PointsToSetFactory.make();
            for (Source source : configProcessor.getSources(callee)) {
                taints.addObject(
                        csManager.getCSObj(emptyContext,
                                manager.makeTaint(callSite, source.type())));
            }

            if (!taints.isEmpty()) {
                solver.addPtsToWL(csManager.getCSVar(callerContext, result), taints);
            }
        }

        void buildTaintFlowGraph(Context callerContext, Invoke callSite, JMethod callee) {
            for (TaintTransfer taintTransfer : configProcessor.getTaintTransfers(callee)) {
                Var from = getSpecificVar(taintTransfer.from(), callSite);
                Var to = getSpecificVar(taintTransfer.to(), callSite);
                if (from != null && to != null) {
                    addTFGEdge(csManager.getCSVar(callerContext, from),
                            csManager.getCSVar(callerContext, to),
                            taintTransfer.type());
                }
            }
        }

        /**
         * @param location arg, result or base of callSite which refers to {@link TaintTransfer}
         * @return the specific variable arg, result or base of call site according to the location.
         */
        private Var getSpecificVar(int location, Invoke callSite) {
            return switch (location) {
                case TaintTransfer.BASE -> getBaseVarOf(callSite.getInvokeExp());
                case TaintTransfer.RESULT -> callSite.getResult();
                default -> callSite.getInvokeExp().getArg(location);
            };
        }

        /** @return the base var of the invokeExp, null if the invokeExp is static invoke. */
        private Var getBaseVarOf(InvokeExp invokeExp) {
            if (invokeExp instanceof InvokeInstanceExp invokeInstanceExp) {
                return invokeInstanceExp.getBase();
            } else { // static invoke
                return null;
            }
        }

        /**
         * Adds an edge "source -> target" to the PFG.
         */
        private void addTFGEdge(Pointer sourcePointer, Pointer targetPointer, Type typeOfTaintObj) {
            if (taintFlowGraph.addEdge(sourcePointer, targetPointer, typeOfTaintObj)) {
                PointsToSet taintObjs = getTaintObjOf(sourcePointer.getPointsToSet());
                if (!taintObjs.isEmpty()) {
                    solver.addPtsToWL(targetPointer, taintObjs);
                }
            }
        }

        /**
         * Propagate the taint objects of pts to the taint successors of given pointer.
         * The pts have been added to the pts of pointer, here needn't do it again.
         */
        void taintPropagateToTaintSuccs(Pointer pointer, PointsToSet pts) {
            PointsToSet taintObjects = getTaintObjOf(pts);
            if (taintObjects.isEmpty()) {
                return;
            }

            for (TaintTargetPointer taintTargetPointer : taintFlowGraph.getSuccsOf(pointer)) {
                PointsToSet taintObjWithNewType = changeTaintType(taintObjects,
                        taintTargetPointer.typeOfTaintObj());
                solver.addPtsToWL(taintTargetPointer.pointer(), taintObjWithNewType);
            }
        }

        /** @return the pts with original invokes and specific type. */
        private PointsToSet changeTaintType(PointsToSet pts, Type type) {
            PointsToSet res = PointsToSetFactory.make();
            for (CSObj taintObj : pts) {
                Obj newTaintObj = manager.makeTaint(manager.getSourceCall(taintObj.getObject()),
                        type);
                res.addObject(csManager.getCSObj(emptyContext, newTaintObj));
            }
            return res;
        }

        /** @return all taint object in the given points-to set. */
        private PointsToSet getTaintObjOf(PointsToSet pts) {
            PointsToSet res = PointsToSetFactory.make();

            for (CSObj csObj : pts) {
                if (isTaintObj(csObj)) {
                    res.addObject(csObj);
                }
            }

            return res;
        }
    }
}
