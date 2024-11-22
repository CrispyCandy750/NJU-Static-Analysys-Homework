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

package pascal.taie.analysis.dataflow.solver;

import pascal.taie.analysis.dataflow.analysis.DataflowAnalysis;
import pascal.taie.analysis.dataflow.fact.DataflowResult;
import pascal.taie.analysis.graph.cfg.CFG;

import java.util.*;

class WorkListSolver<Node, Fact> extends Solver<Node, Fact> {

    WorkListSolver(DataflowAnalysis<Node, Fact> analysis) {
        super(analysis);
    }

    protected void doSolveForward(CFG<Node> cfg, DataflowResult<Node, Fact> result) {
        Queue<Node> workList = new LinkedList<>();
        initializeWorkList(cfg, workList);
        iterateWorkList(workList, cfg, result);
    }

    /** Add all node in the work list except the entry & exit node. */
    private void initializeWorkList(CFG<Node> cfg, Queue<Node> workList) {
        for (Node node : cfg.getNodes()) {
            if (node.equals(cfg.getEntry()) || node.equals(cfg.getExit())) {
                continue;
            }
            workList.add(node);
        }
    }

    /** Add all node in the work list except the entry & exit node. */
    private void initializeWorkListForward(CFG<Node> cfg, Queue<Node> workList) {
        Set<Node> addedNode = new HashSet<>();
        Queue<Node> nodeQueue = new LinkedList<>();
        // add the sucessors of entry
        Set<Node> succsOfEntry = cfg.getSuccsOf(cfg.getEntry());
        nodeQueue.addAll(succsOfEntry);
        while (!nodeQueue.isEmpty()) {
            Node node = nodeQueue.poll();
            if (addedNode.contains(node) || node.equals(cfg.getExit())) {
                continue;
            }
            addedNode.add(node);

            workList.add(node);

            nodeQueue.addAll(cfg.getSuccsOf(node));
        }
    }

    /** Iterate the node in the work list until the work list is empty. */
    private void iterateWorkList(Queue<Node> workList, CFG<Node> cfg,
            DataflowResult<Node, Fact> result
    ) {
        while (!workList.isEmpty()) {
            Node node = workList.poll();
            if (analysis.transferNode(node, calInFact(node, cfg, result),
                    result.getOutFact(node))) {
                //                workList.addAll(cfg.getSuccsOf(node));
                addAllSuccsToWorkList(node, cfg, workList);
            }
        }
    }

    /** Add all successors of given node to work list, ignoring the existed node. */
    private void addAllSuccsToWorkList(Node node, CFG<Node> cfg, Queue<Node> workList) {
        for (Node successor : cfg.getSuccsOf(node)) {
            if (!workList.contains(successor)) {
                workList.add(successor);
            }
        }
    }

    /** @return the recalculated in fact of given node. */
    private Fact calInFact(Node node, CFG<Node> cfg, DataflowResult<Node, Fact> result) {
        Fact inFact = result.getInFact(node);
        for (Node pred : cfg.getPredsOf(node)) {
            analysis.meetInto(result.getOutFact(pred), inFact);
        }
        return inFact;
    }

    @Override
    protected void doSolveBackward(CFG<Node> cfg, DataflowResult<Node, Fact> result) {
        throw new UnsupportedOperationException();
    }
}
