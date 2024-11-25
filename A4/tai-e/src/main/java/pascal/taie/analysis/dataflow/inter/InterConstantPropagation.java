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

import jas.CP;
import pascal.taie.analysis.dataflow.analysis.constprop.CPFact;
import pascal.taie.analysis.dataflow.analysis.constprop.ConstantPropagation;
import pascal.taie.analysis.dataflow.analysis.constprop.Value;
import pascal.taie.analysis.graph.cfg.CFG;
import pascal.taie.analysis.graph.cfg.CFGBuilder;
import pascal.taie.analysis.graph.icfg.CallEdge;
import pascal.taie.analysis.graph.icfg.CallToReturnEdge;
import pascal.taie.analysis.graph.icfg.NormalEdge;
import pascal.taie.analysis.graph.icfg.ReturnEdge;
import pascal.taie.config.AnalysisConfig;
import pascal.taie.ir.IR;
import pascal.taie.ir.exp.InvokeExp;
import pascal.taie.ir.exp.LValue;
import pascal.taie.ir.exp.RValue;
import pascal.taie.ir.exp.Var;
import pascal.taie.ir.stmt.Invoke;
import pascal.taie.ir.stmt.Stmt;
import pascal.taie.language.classes.JMethod;
import pascal.taie.util.AnalysisException;

import java.util.List;
import java.util.Optional;

/**
 * Implementation of interprocedural constant propagation for int values.
 */
public class InterConstantPropagation extends
        AbstractInterDataflowAnalysis<JMethod, Stmt, CPFact> {

    public static final String ID = "inter-constprop";

    private final ConstantPropagation cp;

    public InterConstantPropagation(AnalysisConfig config) {
        super(config);
        cp = new ConstantPropagation(new AnalysisConfig(ConstantPropagation.ID));
    }

    @Override
    public boolean isForward() {
        return cp.isForward();
    }

    @Override
    public CPFact newBoundaryFact(Stmt boundary) {
        IR ir = icfg.getContainingMethodOf(boundary).getIR();
        return cp.newBoundaryFact(ir.getResult(CFGBuilder.ID));
    }

    @Override
    public CPFact newInitialFact() {
        return cp.newInitialFact();
    }

    @Override
    public void meetInto(CPFact fact, CPFact target) {
        cp.meetInto(fact, target);
    }

    /** Call Node does nothing. */
    @Override
    protected boolean transferCallNode(Stmt stmt, CPFact in, CPFact out) {
        return out.copyFrom(in);
    }

    /** Non Call Node does the same things as intraproducural constant propagation. */
    @Override
    protected boolean transferNonCallNode(Stmt stmt, CPFact in, CPFact out) {
        return cp.transferNode(stmt, in, out);
    }

    /** Normal edge does nothing. */
    @Override
    protected CPFact transferNormalEdge(NormalEdge<Stmt> edge, CPFact out) {
        return out.copy();
    }

    /** Call to return edge kill the (Var) lValue of call site. */
    @Override
    protected CPFact transferCallToReturnEdge(CallToReturnEdge<Stmt> edge, CPFact out) {
        CPFact outFactCopy = out.copy();

        Var defVar = getDefVar(edge.getSource());
        if (defVar != null) {
            outFactCopy.remove(defVar);
        }

        return outFactCopy;
    }

    /** param1 = val(arg1) */
    @Override
    protected CPFact transferCallEdge(CallEdge<Stmt> edge, CPFact callSiteOut) {
        List<Var> args = getInvokeArgs(edge.getSource());
        List<Var> params = edge.getCallee().getIR().getParams();

        if (args.size() != params.size()) {
            throw new AnalysisException(
                    "the sizes of the call site args and the callee params are not matched");
        }

        // only transfer params
        CPFact res = new CPFact();
        for (int i = 0; i < args.size(); i++) {
            res.update(params.get(i), callSiteOut.get(args.get(i)));
        }

        return res;
    }

    /** @return the list of the args of the invokeExp. */
    private List<Var> getInvokeArgs(Stmt stmt) {
        if (!(stmt instanceof Invoke)) {
            throw new AnalysisException(
                    "the source of call edge " + stmt + " is not an invoke statement.");
        }
        Invoke invokeStmt = (Invoke) stmt;
        return invokeStmt.getInvokeExp().getArgs();
    }

    @Override
    protected CPFact transferReturnEdge(ReturnEdge<Stmt> edge, CPFact returnOut) {
        CPFact res = new CPFact();

        // use the lvalue of call site but not return site
        Var defVar = getDefVar(edge.getCallSite());
        if (defVar != null) {
            res.update(defVar, getReturnValue(edge, returnOut));
        }

        return res;
    }

    /** @return defined var, if no define or defined lValue is not Var, return null. */
    private Var getDefVar(Stmt returnSite) {
        Optional<LValue> def = returnSite.getDef();
        if (def.isPresent()) {
            LValue lValue = def.get();
            if (lValue instanceof Var) {
                return (Var) lValue;
            }
        }
        return null;
    }

    /** @return the met return value (met with all return vars). */
    private Value getReturnValue(ReturnEdge<Stmt> edge, CPFact returnOut) {
        Value res = Value.getUndef();
        for (Var returnVar : edge.getReturnVars()) {
            res = cp.meetValue(res, returnOut.get(returnVar));
        }
        return res;
    }
}
