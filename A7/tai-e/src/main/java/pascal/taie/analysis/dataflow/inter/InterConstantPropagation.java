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
import pascal.taie.ir.proginfo.FieldRef;
import pascal.taie.ir.stmt.*;
import pascal.taie.language.classes.JMethod;
import pascal.taie.util.AnalysisException;

import java.util.*;
import java.util.function.Function;

/**
 * Implementation of interprocedural constant propagation for int values.
 */
public class InterConstantPropagation extends
        AbstractInterDataflowAnalysis<JMethod, Stmt, CPFact> {

    public static final String ID = "inter-constprop";

    private final ConstantPropagation cp;
    private ExpEvaluator expEvaluator;
    private final LoadStoreStmtCache loadStoreStmtCache;

    private PointerAnalysisResult pta;

    public InterConstantPropagation(AnalysisConfig config) {
        super(config);
        cp = new ConstantPropagation(new AnalysisConfig(ConstantPropagation.ID));
        loadStoreStmtCache = new LoadStoreStmtCache();
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
     * Non Call Node (may) apply transfer function to in fact, records some stmt and append stmt
     * to workList.
     *
     * @return true if out fact is changed, otherwise false.
     */
    @Override
    protected boolean transferNonCallNode(Stmt stmt, CPFact in, CPFact out) {
        boolean changed = applyTransferFunction(stmt, in, out);
        recordStaticFieldAccessStmt(stmt);
        appendRelatedStmtToWL(changed, stmt);
        return changed;
    }

    private StaticFieldAccess loadStaticFieldAccess = null;
    private LoadField staticLoadField = null;
    private StaticFieldAccess storeStaticFieldAccess = null;
    private StoreField staticStoreField = null;

    /** Just for test the equal() of static field access */
    private void testStaticField(Stmt stmt) {
        if (stmt instanceof LoadField loadField && loadField.isStatic()) {
            loadStaticFieldAccess = (StaticFieldAccess) loadField.getFieldAccess();
            staticLoadField = loadField;
        } else if (stmt instanceof StoreField storeField && storeField.isStatic()) {
            storeStaticFieldAccess = (StaticFieldAccess) storeField.getFieldAccess();
            staticStoreField = storeField;
        }

        if (loadStaticFieldAccess != null && staticLoadField != null
                && storeStaticFieldAccess != null && staticStoreField != null) {
            System.out.println(
                    "=============================================================================");
            System.out.println(staticLoadField + "|||" + loadStaticFieldAccess);
            System.out.println(staticStoreField + "|||" + storeStaticFieldAccess);


            System.exit(0);
        }
    }

    /**
     * Apply transfer function to in fact.
     *
     * @return true if the out fact is changed, otherwise false.
     */
    private boolean applyTransferFunction(Stmt stmt, CPFact in, CPFact out) {
        if (stmt instanceof LoadField loadField) { // a = x.f, a = T.f
            return transferLoadField(loadField, in, out);
        } else if (stmt instanceof LoadArray loadArray) {
            return transferLoadArray(loadArray, in, out);
        } else {
            return cp.transferNode(stmt, in, out);
        }
    }

    /** Record the StaticLoad and StaticStore stmts. */
    private void recordStaticFieldAccessStmt(Stmt stmt) {
        if (stmt instanceof LoadField loadField && loadField.isStatic()) {
            loadStoreStmtCache.put((StaticFieldAccess) loadField.getRValue(), loadField);
        } else if (stmt instanceof StoreField storeField && storeField.isStatic()) {
            loadStoreStmtCache.put((StaticFieldAccess) storeField.getFieldAccess(),
                    storeField);
        }
    }

    /**
     * Append statements related to given `stmt` to WL
     *
     * @param append do append if true, do nothing if false
     */
    private void appendRelatedStmtToWL(boolean append, Stmt sourceStmt) {
        if (append && sourceStmt instanceof StoreField storeField) {
            appendRelevantLoadFieldsToWL(storeField);
        }
    }

    /**
     * Append relevant LoadFields to WL.
     * e.g. if stmt is `x.f = a`, append `y = x.f` to WL
     *
     * @param storeField a StoreField: x.f = a
     */
    private void appendRelevantLoadFieldsToWL(StoreField storeField) {
        if (storeField.getFieldAccess() instanceof InstanceFieldAccess instanceFieldAccess) {
            appendAbsentNodesToWL(loadStoreStmtCache.getLoadFields(instanceFieldAccess));
        } else if (storeField.getFieldAccess() instanceof StaticFieldAccess staticFieldAccess) {
            appendAbsentNodesToWL(loadStoreStmtCache.getLoadFields(staticFieldAccess));
        }
    }

    /**
     * Append all nodes to workList of solver, ignoring the existing node.
     *
     * @param nodes the nodes to be appended.
     * @param <Node> the type of the node, it should be stmt in this application.
     */
    private <Node extends Stmt> void appendAbsentNodesToWL(Collection<Node> nodes) {
        for (Node node : nodes) {
            solver.appendAbsentNodeInWL(node);
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

    /**
     * The Evaluator of expression, here only implements the evaluation of
     * InstanceFieldAccess, StaticFieldAccess and ArrayAccess.
     */
    private class ExpEvaluator implements ExpVisitor<Value> {

        /** @return the value of the instance field access base.field. */
        @Override
        public Value visit(InstanceFieldAccess instanceFieldAccess) {
            return meetRValueOf(loadStoreStmtCache.getStoreFields(instanceFieldAccess));
        }

        /** @return the value of the static field access T.f. */
        @Override
        public Value visit(StaticFieldAccess staticFieldAccess) {
            return meetRValueOf(loadStoreStmtCache.getStoreFields(staticFieldAccess));
        }

        @Override
        public Value visit(ArrayAccess arrayAccess) {
            //TODO: finish evaluation of array access.
            return null;
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
    }

    /** The class caches the relevant StoreFields, LoadFields, StoreArrays, LoadArrays. */
    private class LoadStoreStmtCache {
        private final Map<Var, Set<Var>> aliasCache;
        private final Map<InstanceFieldAccess, Set<StoreField>> storeFieldsOfInstanceFieldAccess;
        private final Map<InstanceFieldAccess, Set<LoadField>> loadFieldsOfInstanceFieldAccess;
        private final Map<FieldRef, Set<StoreField>> storeFieldsOfStaticFieldAccess;
        private final Map<FieldRef, Set<LoadField>> loadFieldsOfStaticFieldAccess;
        private final Map<ArrayAccess, Set<StoreArray>> storeArraysOfArrayAccess;
        private final Map<ArrayAccess, Set<LoadArray>> loadArraysOfArrayAccess;

        LoadStoreStmtCache() {
            aliasCache = new HashMap<>();
            storeFieldsOfInstanceFieldAccess = new HashMap<>();
            loadFieldsOfInstanceFieldAccess = new HashMap<>();
            storeFieldsOfStaticFieldAccess = new HashMap<>();
            loadFieldsOfStaticFieldAccess = new HashMap<>();
            storeArraysOfArrayAccess = new HashMap<>();
            loadArraysOfArrayAccess = new HashMap<>();
        }

        /** @return all aliases of given var including itself from the alias cache. */
        private Set<Var> getAliasesOf(Var var) {
            return getOrCache(aliasCache, var, this::findAliasesOf);
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
         * get from the cache, if the value is not existing, find and cache it.
         *
         * @param key the key
         * @param finder the function to find the value
         * @param <K> The type of key
         * @param <V> The type of the set of the value.
         * @return the set of the value
         */
        private <K, V> Set<V> getOrCache(Map<K, Set<V>> cache, K key, Function<K, Set<V>> finder) {
            Set<V> res = cache.get(key);
            if (res == null) {
                res = finder.apply(key);
                cache.put(key, res);
            }
            return res;
        }

        /* ---------------------- Instance Field Access ---------------------- */

        /** @return the related load fields of given key(x.f): a = x.f. */
        Set<LoadField> getLoadFields(InstanceFieldAccess key) {
            return getOrCache(loadFieldsOfInstanceFieldAccess, key, this::findInstanceLoadFields);
        }

        /**
         * @param instanceFieldAccess x.f
         * @return all instance load fields of given x.f (including the alias)
         */
        private Set<LoadField> findInstanceLoadFields(InstanceFieldAccess instanceFieldAccess) {
            return findInstanceLoadOrStoreFields(instanceFieldAccess, Var::getLoadFields);
        }

        /** @return the related store fields of given key(x.f): x.f = b. */
        Set<StoreField> getStoreFields(InstanceFieldAccess key) {
            return getOrCache(storeFieldsOfInstanceFieldAccess, key, this::findInstanceStoreFields);
        }

        /**
         * @param instanceFieldAccess x.f
         * @return all instance store fields of given x.f (including the alias)
         */
        private Set<StoreField> findInstanceStoreFields(InstanceFieldAccess instanceFieldAccess) {
            return findInstanceLoadOrStoreFields(instanceFieldAccess, Var::getStoreFields);
        }

        private <T extends FieldStmt> Set<T> findInstanceLoadOrStoreFields(
                InstanceFieldAccess instanceFieldAccess,
                Function<Var, List<T>> getLoadOrStoreFieldsOf
        ) {
            Var base = instanceFieldAccess.getBase();
            FieldRef fieldRef = instanceFieldAccess.getFieldRef();
            Set<T> loadOrStoreFields = new HashSet<>();

            for (Var var : getAliasesOf(base)) {
                for (T loadOrStoreField : getLoadOrStoreFieldsOf.apply(var)) {
                    if (loadOrStoreField.getFieldRef().equals(fieldRef)) {
                        loadOrStoreFields.add(loadOrStoreField);
                    }
                }
            }
            return loadOrStoreFields;
        }

        /* ---------------------- Static Field Access ---------------------- */

        /** @return the related load fields of given key(T.f): a = T.f. */
        Set<LoadField> getLoadFields(StaticFieldAccess key) {
            return get(loadFieldsOfStaticFieldAccess, key.getFieldRef());
        }

        /** @return the related store fields of given key(T.f): T.f = b. */
        Set<StoreField> getStoreFields(StaticFieldAccess key) {
            return get(storeFieldsOfStaticFieldAccess, key.getFieldRef());
        }

        /** Record the given static loadField. */
        boolean put(StaticFieldAccess staticFieldAccess, LoadField loadField) {
            return put(loadFieldsOfStaticFieldAccess, staticFieldAccess.getFieldRef(), loadField);
        }

        /** Record the given static storeField. */
        boolean put(StaticFieldAccess staticFieldAccess, StoreField storeField) {
            return put(storeFieldsOfStaticFieldAccess, staticFieldAccess.getFieldRef(), storeField);
        }

        /**
         * add the value in the set corresponding to the key, if the set is non-existing,
         * creates a new set.
         */
        private <K, V> boolean put(Map<K, Set<V>> map, K key, V value) {
            Set<V> set = map.get(key);
            if (set == null) {
                map.put(key, new HashSet<>(Set.of(value)));
                return true;
            } else {
                return set.add(value);
            }
        }

        private <K, V> Set<V> get(Map<K, Set<V>> map, K key) {
            return map.getOrDefault(key, new HashSet<>());
        }
    }
}
