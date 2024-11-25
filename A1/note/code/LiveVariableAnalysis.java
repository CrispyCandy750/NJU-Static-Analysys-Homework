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

import pascal.taie.analysis.dataflow.fact.SetFact;
import pascal.taie.analysis.graph.cfg.CFG;
import pascal.taie.config.AnalysisConfig;
import pascal.taie.ir.exp.LValue;
import pascal.taie.ir.exp.RValue;
import pascal.taie.ir.exp.Var;
import pascal.taie.ir.stmt.Stmt;

import java.util.List;
import java.util.Optional;

/**
 * Implementation of classic live variable analysis.
 */
public class LiveVariableAnalysis extends
        AbstractDataflowAnalysis<Stmt, SetFact<Var>> {

    public static final String ID = "livevar";

    public LiveVariableAnalysis(AnalysisConfig config) {
        super(config);
    }

    /**
     * @return false because livevar analysis is backward analysis.
     */
    @Override
    public boolean isForward() {
        return false;
    }

    /**
     * @return empty set because the boundary fact of live variable analysis is empty.
     */
    @Override
    public SetFact<Var> newBoundaryFact(CFG<Stmt> cfg) {
        return new SetFact<>();
    }

    /**
     * @return empty set because the initial fact of each non-boundary node in live variable
     * analysis is empty.
     */
    @Override
    public SetFact<Var> newInitialFact() {
        return new SetFact<>();
    }

    /**
     * target = target ∪ fact:
     * live variable analysis is may analysis, the meet operation is union.
     */
    @Override
    public void meetInto(SetFact<Var> fact, SetFact<Var> target) {
        target.union(fact);
    }

    /**
     * In[stmt] = use ∪ (Out[stmt] - def), the  live variable analysis is backward analysis.
     *
     * @param stmt the statement corresponding to the node.
     * @param in the in setFact of the node
     * @param out the out setFact of the node
     * @return true if the transfer changed the out (in) fact, otherwise false.
     */
    @Override
    public boolean transferNode(Stmt stmt, SetFact<Var> in, SetFact<Var> out) {
        if (null == out) {
            return false;
        }
        SetFact<Var> temp = out.copy();

        /* Remove the defined variable from the out */
        Optional<LValue> defLValOptional = stmt.getDef();
        if (defLValOptional.isPresent()) {
            // Is the stmt.getDef a val?
            LValue defExp = defLValOptional.get();
            if (defExp instanceof Var) {
                temp.remove((Var) defExp);
            }
        }

        /* Add the used variable in the given statement */
        List<RValue> useExps = stmt.getUses();
        for (RValue rValue : useExps) {
            if (rValue instanceof Var) {
                temp.add((Var) rValue);
            }
        }

        if (temp.equals(in)) {
            return false;
        } else {
            in.union(temp);
            return true;
        }
    }
}
