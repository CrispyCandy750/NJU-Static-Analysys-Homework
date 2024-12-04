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

package pascal.taie.analysis.dataflow.inter;

import pascal.taie.analysis.dataflow.analysis.constprop.CPFact;
import pascal.taie.analysis.dataflow.fact.DataflowResult;
import pascal.taie.analysis.graph.icfg.ICFG;
import pascal.taie.analysis.graph.icfg.ICFGEdge;
import pascal.taie.util.collection.SetQueue;

import java.util.Collection;
import java.util.LinkedList;
import java.util.Queue;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Solver for inter-procedural data-flow analysis.
 * The workload of inter-procedural analysis is heavy, thus we always
 * adopt work-list algorithm for efficiency.
 */
class InterSolver<Method, Node, Fact> {

    private final InterDataflowAnalysis<Node, Fact> analysis;

    private final ICFG<Method, Node> icfg;

    private DataflowResult<Node, Fact> result;

    private Queue<Node> workList;

    InterSolver(InterDataflowAnalysis<Node, Fact> analysis,
            ICFG<Method, Node> icfg
    ) {
        this.analysis = analysis;
        this.icfg = icfg;
    }

    DataflowResult<Node, Fact> solve() {
        result = new DataflowResult<>();
        initialize();
        doSolve();
        return result;
    }

    /** Initialize the fact and boundary fact of each node. */
    private void initialize() {
        for (Node node : icfg.getNodes()) {
            result.setInFact(node, analysis.newInitialFact());
            result.setOutFact(node, analysis.newInitialFact());
        }

        // boundary fact, all params are NAC as it can be anything.
        icfg.entryMethods().forEach(entryMethod -> {
            Node entryNode = icfg.getEntryOf(entryMethod);
            result.setOutFact(entryNode, analysis.newBoundaryFact(entryNode));
        });
    }

    private void doSolve() {
        workList = new LinkedList<>(icfg.getNodes());
        while (!workList.isEmpty()) {
            Node node = workList.poll();
            if (analysis.transferNode(node, calInFact(node), result.getOutFact(node))) {
                appendAbsentNodesInWL(icfg.getSuccsOf(node)); // append successors
            }
        }
    }

    /** @return the recalculated in fact. */
    private Fact calInFact(Node node) {
        Fact inFact = result.getInFact(node);
        for (ICFGEdge<Node> inEdge : icfg.getInEdgesOf(node)) {
            Fact sourceFact = result.getOutFact(inEdge.getSource());
            analysis.meetInto(analysis.transferEdge(inEdge, sourceFact), inFact);
        }
        return inFact;
    }

    /** Append all nodes to workList, ignoring the existing nodes. */
    public void appendAbsentNodesInWL(Collection<Node> nodes) {
        for (Node node : nodes) {
            if (!workList.contains(node)) {
                workList.add(node);
            }
        }
    }

    /** @return the out fact of given node. */
    public Fact getOutFactOf(Node node) {
        return result.getOutFact(node);
    }
}
