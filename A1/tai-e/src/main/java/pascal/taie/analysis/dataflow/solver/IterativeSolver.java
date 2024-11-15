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

import java.util.HashSet;
import java.util.LinkedList;
import java.util.Queue;
import java.util.Set;

class IterativeSolver<Node, Fact> extends Solver<Node, Fact> {

    private int cnt_iter = 0;

    public IterativeSolver(DataflowAnalysis<Node, Fact> analysis) {
        super(analysis);
    }

    @Override
    protected void doSolveForward(CFG<Node> cfg, DataflowResult<Node, Fact> result) {
        throw new UnsupportedOperationException();
    }

    @Override
    protected void doSolveBackward(CFG<Node> cfg, DataflowResult<Node, Fact> result) {
        Set<Node> handedNodes = new HashSet<>();
        Node exitNode = cfg.getExit();

        // if changes to any IN occurs
        boolean changed;
        do {
            cnt_iter++;
            handedNodes.clear();
            changed = handleNodeBackward(cfg, exitNode, result, handedNodes);
            // changed = handleNodeBackwardBFS(cfg, result);
        } while (changed);
        System.out.println("cnt_iter = " + cnt_iter);
    }

    /**
     * recalculate the output of given node in the cfg.
     * needn't keeping the output state.
     * Output[Fact] = ∪ (S a successor of B) In[S]
     */
    private Fact calOutFact(CFG<Node> cfg, Node node, DataflowResult<Node, Fact> result) {
        Fact outFact = result.getOutFact(node);
        for (Node successor : cfg.getSuccsOf(node)) {
            analysis.meetInto(result.getInFact(successor), outFact);
        }
        return outFact;
    }

    /**
     * handle the given node (i.e. applying the transfer function) iteratively backwards.
     *
     * @param handledNode the handled node
     * @return true if the changes to any IN occur, otherwise false.
     */
    private boolean handleNodeBackward(CFG<Node> cfg, Node node, DataflowResult<Node, Fact> result,
            Set<Node> handledNode
    ) {
        if (handledNode.contains(node)) {
            return false;
        }
        handledNode.add(node);  // label the node

        Fact outFact = calOutFact(cfg, node, result);
        boolean changed = analysis.transferNode(node, result.getInFact(node), outFact);

        // handle the predecessors iteratively
        Set<Node> predecessors = cfg.getPredsOf(node);
        for (Node predecessor : predecessors) {
            changed = changed || handleNodeBackward(cfg, predecessor, result, handledNode);
        }

        return changed;
    }

    /**
     * handle the given node (i.e. applying the transfer function) iteratively backwards with BFS.
     *
     * @return true if the changes to any IN occur, otherwise false.
     */
    private boolean handleNodeBackwardBFS(CFG<Node> cfg, DataflowResult<Node, Fact> result
    ) {
        Queue<Node> workList = new LinkedList<>();
        workList.add(cfg.getExit());
        HashSet<Node> handledNodes = new HashSet<>();
        boolean changed = false;

        while (!workList.isEmpty()) {
            Node node = workList.poll();
            if (handledNodes.contains(node)) {
                continue;
            }
            handledNodes.add(node);
            Fact outFact = calOutFact(cfg, node, result);
            changed = changed || analysis.transferNode(node, result.getInFact(node), outFact);
            workList.addAll(cfg.getPredsOf(node));
        }

        return changed;
    }
}
