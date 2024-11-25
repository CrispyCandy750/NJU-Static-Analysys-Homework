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

class IterativeSolver<Node, Fact> extends Solver<Node, Fact> {

    /** Record the number of iteration for performance comparison */
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
        /* if changes to any IN occurs */
        while (traverseBackward(cfg, result))
            cnt_iter++;  // for performance comparison
        System.out.println("cnt_iter = " + cnt_iter);
    }

    /**
     * recalculate the output of given node in the cfg.
     * <del>needn't keeping the output state.</del>
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
     * traverse the cfg (i.e. apply the transfer functions) backwards with BFS starting at exit
     * node.
     *
     * @return true if the changes to any IN occur, otherwise false.
     */
    private boolean traverseBackwardBFS(CFG<Node> cfg, DataflowResult<Node, Fact> result
    ) {

        boolean changed = false;

        /* Initialize the variables for BFS */
        Queue<Node> workList = new LinkedList<>();
        workList.add(cfg.getExit());
        HashSet<Node> handledNodes = new HashSet<>();

        while (!workList.isEmpty()) {

            /* Ignore repetition and cycles. */
            Node node = workList.poll();
            if (handledNodes.contains(node)) {
                continue;
            }

            /* label the handled node. */
            handledNodes.add(node);

            /* handle the node */
            Fact outFact = calOutFact(cfg, node, result);
            // changed = changed || analysis.transferNode(node, result.getInFact(node), outFact);
            changed = analysis.transferNode(node, result.getInFact(node), outFact) || changed;
            workList.addAll(cfg.getPredsOf(node));
        }

        return changed;
    }

    /**
     * traverse the cfg (i.e. apply the transfer functions) backwards with BFS starting at exit
     * node.
     *
     * @return true if the changes to any IN occur, otherwise false.
     */
    private boolean traverseBackward(CFG<Node> cfg, DataflowResult<Node, Fact> result
    ) {
        boolean changed = false;
        for (Node node: cfg.getNodes()) {
            Fact outFact = calOutFact(cfg, node, result);
            // changed = changed || analysis.transferNode(node, result.getInFact(node), outFact);
            changed = analysis.transferNode(node, result.getInFact(node), outFact) || changed;
        }
        return changed;
    }
}
