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

import pascal.taie.World;
import pascal.taie.analysis.dataflow.analysis.constprop.CPFact;
import pascal.taie.analysis.dataflow.analysis.constprop.ConstantPropagation;
import pascal.taie.analysis.dataflow.analysis.constprop.Value;
import pascal.taie.analysis.graph.cfg.CFGBuilder;
import pascal.taie.analysis.graph.icfg.CallEdge;
import pascal.taie.analysis.graph.icfg.CallToReturnEdge;
import pascal.taie.analysis.graph.icfg.NormalEdge;
import pascal.taie.analysis.graph.icfg.ReturnEdge;
import pascal.taie.analysis.pta.PointerAnalysisResult;
import pascal.taie.analysis.pta.core.heap.Obj;
import pascal.taie.config.AnalysisConfig;
import pascal.taie.ir.IR;
import pascal.taie.ir.exp.*;
import pascal.taie.ir.stmt.*;
import pascal.taie.language.classes.JField;
import pascal.taie.language.classes.JMethod;
import pascal.taie.util.AnalysisException;

import java.util.*;

/**
 * Implementation of interprocedural constant propagation for int values.
 */
public class InterConstantPropagation extends
        AbstractInterDataflowAnalysis<JMethod, Stmt, CPFact> {

    public static final String ID = "inter-constprop";

    private final ConstantPropagation cp;
    private ExpEvaluator expEvaluator = null;
    private final Map<Var, Set<Var>> varAliasCache;

    private PointerAnalysisResult pta;

    public InterConstantPropagation(AnalysisConfig config) {
        super(config);
        cp = new ConstantPropagation(new AnalysisConfig(ConstantPropagation.ID));
        varAliasCache = new HashMap<>();
    }

    @Override
    protected void initialize() {
        String ptaId = getOptions().getString("pta");
        pta = World.get().getResult(ptaId);
        // You can do initialization work here
        expEvaluator = new ExpEvaluator();
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

    /**
     * Call Node does nothing.
     *
     * @return true if out fact is changed, otherwise false.
     */
    @Override
    protected boolean transferCallNode(Stmt stmt, CPFact in, CPFact out) {
        return out.copyFrom(in);
    }

    /**
     * Non Call Node (may) apply transfer function to in fact.
     *
     * @return true if out fact is changed, otherwise false.
     */
    @Override
    protected boolean transferNonCallNode(Stmt stmt, CPFact in, CPFact out) {
        boolean changed;
        if (stmt instanceof LoadField loadField) {
            changed = transferLoadField(loadField, in, out);
        } else if (stmt instanceof LoadArray loadArray) {
            changed = transferLoadArray(loadArray, in, out);
        } else {
            changed = cp.transferNode(stmt, in, out);
            if (changed && stmt instanceof StoreField storeField) {
                appendRelevantLoadFieldsToWL(storeField);
            }
        }
        return changed;
    }

    /**
     * Append relevant LoadFields to WL.
     * e.g. if stmt is `x.f = a`, append `y = x.f` to WL
     *
     * @param storeField a StoreField: x.f = a
     */
    private void appendRelevantLoadFieldsToWL(StoreField storeField) {
        if (storeField.getFieldAccess() instanceof InstanceFieldAccess instanceFieldAccess) {
            solver.appendAbsentNodesInWL(getLoadFieldOfAliasOf(instanceFieldAccess.getBase(),
                    instanceFieldAccess.getFieldRef().resolve()));
        }
    }

    /** @return true if out fact is changed, otherwise false. */
    private boolean transferLoadField(LoadField loadField, CPFact in, CPFact out) {
        in.update(loadField.getLValue(),
                loadField.getRValue().accept(expEvaluator));
        return out.copyFrom(in);
    }

    /** @return true if out fact is changed, otherwise false. */
    private boolean transferLoadArray(LoadArray loadArray, CPFact in, CPFact out) {
        in.update(loadArray.getLValue(),
                loadArray.getRValue().accept(expEvaluator));
        return out.copyFrom(in);
    }

    /** @return the copy of out fact: normal edge does nothing. */
    @Override
    protected CPFact transferNormalEdge(NormalEdge<Stmt> edge, CPFact out) {
        return out.copy();
    }

    /**
     * Call to Return Edge is from call site to the next statement of that in the same method.
     * It kills the assignee of the call site from the out fact.
     */
    @Override
    protected CPFact transferCallToReturnEdge(CallToReturnEdge<Stmt> edge, CPFact out) {
        CPFact targetFact = out.copy();

        Optional<LValue> defOptional = edge.getSource().getDef();

        if (defOptional.isPresent()) {  // Assignee of call site exists
            LValue lValue = defOptional.get();
            if (lValue instanceof Var assignee) {  // assignee is a variable
                targetFact.remove(assignee);  // remove the assignee.
            }
        }

        return targetFact;
    }

    /**
     * Call Edge is the edge connecting a call site to method entry of the callee.
     * It transfers the value of arguments to parameters.
     */
    @Override
    protected CPFact transferCallEdge(CallEdge<Stmt> edge, CPFact callSiteOut) {
        CPFact targetFact = new CPFact();
        Stmt sourceStmt = edge.getSource();

        if (!(sourceStmt instanceof Invoke callSite)) {
            throw new AnalysisException("The call site of edge: " + edge + " is not an invoke");
        }

        List<Var> args = callSite.getInvokeExp().getArgs();
        List<Var> params = edge.getCallee().getIR().getParams();

        if (args.size() != params.size()) {
            throw new AnalysisException(
                    "The numbers of args and params of call site: " + callSite + " do not match!");
        }

        for (int i = 0; i < args.size(); i++) {
            targetFact.update(params.get(i), callSiteOut.get(args.get(i)));
        }

        return targetFact;
    }

    /**
     * Return Edge is the edge connecting a method exit to return site of the call site.
     * It transfers the meet value of all return vars of callee to assignee of call site.
     */
    @Override
    protected CPFact transferReturnEdge(ReturnEdge<Stmt> edge, CPFact returnOut) {
        CPFact targetFact = new CPFact();
        Optional<LValue> defOptional = edge.getCallSite().getDef();

        if (defOptional.isPresent()) {
            LValue lValue = defOptional.get();
            if (lValue instanceof Var assignee) {
                targetFact.update(assignee, meetValueOfReturnVars(edge.getReturnVars(), returnOut));
            }
        }

        return targetFact;
    }

    /** @return the meet value of return vars. */
    private Value meetValueOfReturnVars(Collection<Var> returnVars, CPFact returnOut) {
        Value res = Value.getUndef();
        for (Var returnVar : returnVars) {
            res = cp.meetValue(res, returnOut.get(returnVar));
        }
        return res;
    }

    /** @return all StoreFields and the lValue is the given var.field. (var.field = x) */
    private Collection<StoreField> getAllStoreFieldsOf(Var var, JField field) {
        Set<StoreField> storeFields = new HashSet<>();
        for (StoreField storeField : var.getStoreFields()) {
            if (storeField.getFieldRef().resolve().equals(field)) {
                storeFields.add(storeField);
            }
        }
        return storeFields;
    }

    /**
     * @return all loadFields whose base var is the alias of given var and the field is specific
     * field.
     */
    private Collection<Stmt> getLoadFieldOfAliasOf(Var var, JField field) {
        Collection<Stmt> loadFields = new HashSet<>();
        for (Var alias : getAliasesOf(var)) {
            loadFields.addAll(getAllLoadFieldOf(alias, field));
        }
        return loadFields;
    }

    /** @return all LoadFields and the lValue is the given var.field. (x = var.field) */
    private Set<LoadField> getAllLoadFieldOf(Var var, JField field) {
        Set<LoadField> loadFields = new HashSet<>();
        for (LoadField loadField : var.getLoadFields()) {
            if (loadField.getFieldRef().resolve().equals(field)) {
                loadFields.add(loadField);
            }
        }
        return loadFields;
    }

    /** @return all aliases of given var including itself from the alias cache. */
    private Set<Var> getAliasesOf(Var var) {
        Set<Var> aliases = varAliasCache.get(var);

        if (aliases == null) {
            aliases = findAliasesOf(var);
            varAliasCache.put(var, aliases);
        }

        return aliases;
    }

    /** @return all aliases of given base including itself from the pointer analysis result. */
    private Set<Var> findAliasesOf(Var base) {
        HashSet<Var> aliases = new HashSet<>();
        Set<Obj> ptsOfBase = pta.getPointsToSet(base);

        for (Var var : pta.getVars()) {
            if (!Collections.disjoint(ptsOfBase, pta.getPointsToSet(var))) {
                aliases.add(var);
            }
        }

        return aliases;
    }

    /**
     * The Evaluator of expression, here only implements the evaluation of
     * InstanceFieldAccess, StaticFieldAccess and ArrayAccess.
     */
    private class ExpEvaluator implements ExpVisitor<Value> {

        /** @return the value of the instance field access base.field. */
        @Override
        public Value visit(InstanceFieldAccess instanceFieldAccess) {
            Value res = Value.getUndef();

            Var base = instanceFieldAccess.getBase();
            JField field = instanceFieldAccess.getFieldRef().resolve();

            for (Var alias : getAliasesOf(base)) {
                res = cp.meetValue(res,
                        meetRValueOf(getAllStoreFieldsOf(alias, field)));
            }

            return res;
        }

        /** @return the meet value of rValue of all storeFields. */
        private Value meetRValueOf(Collection<StoreField> storeFields) {
            Value res = Value.getUndef();
            for (StoreField storeField : storeFields) {
                Value valueOfRVar = solver.getOutFactOf(storeField).get(storeField.getRValue());
                res = cp.meetValue(res, valueOfRVar);
            }
            return res;
        }

        @Override
        public Value visit(StaticFieldAccess staticFieldAccess) {
            return null;
        }

        @Override
        public Value visit(ArrayAccess arrayAccess) {
            //TODO: finish evaluation of array access.
            return null;
        }
    }
}
