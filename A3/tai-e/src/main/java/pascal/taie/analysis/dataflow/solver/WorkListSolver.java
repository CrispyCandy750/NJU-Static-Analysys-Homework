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

import java.util.LinkedList;
import java.util.Queue;

class WorkListSolver<Node, Fact> extends Solver<Node, Fact> {

    WorkListSolver(DataflowAnalysis<Node, Fact> analysis) {
        super(analysis);
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

    /*----------------------------doSolveForward()----------------------------*/

    @Override
    protected void doSolveForward(CFG<Node> cfg, DataflowResult<Node, Fact> result) {
        Queue<Node> workList = new LinkedList<>();
        initializeWorkList(cfg, workList);
        iterateWorkListForward(workList, cfg, result);
    }

    /** Iterate the node in the work list until the work list is empty. */
    private void iterateWorkListForward(Queue<Node> workList, CFG<Node> cfg,
            DataflowResult<Node, Fact> result
    ) {
        while (!workList.isEmpty()) {
            Node node = workList.poll();
            if (analysis.transferNode(node, calInFact(node, cfg, result),
                    result.getOutFact(node))) {
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
        for (Node predecessor : cfg.getPredsOf(node)) {
            analysis.meetInto(result.getOutFact(predecessor), inFact);
        }
        return inFact;
    }

    /*----------------------------doSolveBackward()----------------------------*/
    @Override
    protected void doSolveBackward(CFG<Node> cfg, DataflowResult<Node, Fact> result) {
        Queue<Node> workList = new LinkedList<>();
        initializeWorkList(cfg, workList);
        iterateWorkListBackward(workList, cfg, result);
    }

    private void iterateWorkListBackward(Queue<Node> workList, CFG<Node> cfg,
            DataflowResult<Node, Fact> result
    ) {
        while (!workList.isEmpty()) {
            Node node = workList.poll();
            if (analysis.transferNode(node, result.getInFact(node),
                    calOutFact(node, cfg, result))) {
                addAllPredsToWorkList(node, cfg, workList);
            }
        }
    }

    /** @return the recalculated out fact of given node. */
    private Fact calOutFact(Node node, CFG<Node> cfg, DataflowResult<Node, Fact> result) {
        Fact outFact = result.getOutFact(node);
        for (Node successor : cfg.getSuccsOf(node)) {
            analysis.meetInto(result.getInFact(successor), outFact);
        }
        return outFact;
    }

    /** Add all predecessors of given node to work list, ignoring the existed node. */
    private void addAllPredsToWorkList(Node node, CFG<Node> cfg, Queue<Node> workList) {
        for (Node predecessor : cfg.getPredsOf(node)) {
            if (!workList.contains(predecessor)) {
                workList.add(predecessor);
            }
        }
    }
}
