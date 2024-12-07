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
import pascal.taie.analysis.graph.callgraph.CallGraph;
import pascal.taie.analysis.graph.callgraph.Edge;
import pascal.taie.analysis.pta.PointerAnalysisResult;
import pascal.taie.analysis.pta.core.cs.context.Context;
import pascal.taie.analysis.pta.core.cs.element.CSManager;
import pascal.taie.analysis.pta.core.cs.element.CSObj;
import pascal.taie.analysis.pta.core.cs.element.Pointer;
import pascal.taie.analysis.pta.core.heap.Obj;
import pascal.taie.analysis.pta.cs.Solver;
import pascal.taie.analysis.pta.pts.PointsToSet;
import pascal.taie.analysis.pta.pts.PointsToSetFactory;
import pascal.taie.ir.exp.Var;
import pascal.taie.ir.stmt.Invoke;
import pascal.taie.language.classes.JMethod;
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
    }

    /** Process the specific method about taint analysis: Source, Sink and TaintTransfer. */
    public void taintProcessCall(Context callerContext, Invoke callSite, JMethod callee) {
        processSource(callerContext, callSite, callee);
    }

    /**
     * Process the {@link Source}
     * If callee is Source, create taint @{link Source} the assignee of callSite, otherwise do
     * nothing.
     *
     * @param callSite must be in the call site.
     */
    private void processSource(Context callerContext, Invoke callSite, JMethod callee) {
        Var result = callSite.getLValue();
        if (result == null) {
            return;
        }

        PointsToSet taints = PointsToSetFactory.make();
        for (Source source : configProcessor.getSources(callee)) {
            taints.addObject(
                    csManager.getCSObj(emptyContext, manager.makeTaint(callSite, source.type())));
        }

        if (!taints.isEmpty()) {
            solver.addPtsToWL(csManager.getCSVar(callerContext, result), taints);
        }
    }


    /** @return true if the csObj is taint obj, false otherwise. */
    public boolean isTaintObj(CSObj csObj) {
        return csObj.getContext().equals(emptyContext) && manager.isTaint(csObj.getObject());
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
}
