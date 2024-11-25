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

package pascal.taie.analysis.graph.callgraph;

import pascal.taie.World;
import pascal.taie.ir.proginfo.MethodRef;
import pascal.taie.ir.stmt.Invoke;
import pascal.taie.language.classes.ClassHierarchy;
import pascal.taie.language.classes.JClass;
import pascal.taie.language.classes.JMethod;
import pascal.taie.language.classes.Subsignature;

import java.util.HashSet;
import java.util.LinkedList;
import java.util.Queue;
import java.util.Set;

/**
 * Implementation of the CHA algorithm.
 */
class CHABuilder implements CGBuilder<Invoke, JMethod> {

    private ClassHierarchy hierarchy;

    @Override
    public CallGraph<Invoke, JMethod> build() {
        hierarchy = World.get().getClassHierarchy();
        return buildCallGraph(World.get().getMainMethod());
    }

    private CallGraph<Invoke, JMethod> buildCallGraph(JMethod entry) {
        DefaultCallGraph callGraph = new DefaultCallGraph();
        callGraph.addEntryMethod(entry);
        fillCallGraph(callGraph, entry);
        return callGraph;
    }

    /** Fill the call graph with edges and reachable methods. */
    private void fillCallGraph(DefaultCallGraph callGraph, JMethod entry) {
        Queue<JMethod> workList = new LinkedList<>();
        workList.add(entry);
        iterateWorkList(workList, callGraph);
    }

    /** Iterate the workList and fill the call graph until the list is empty. */
    private void iterateWorkList(Queue<JMethod> workList, DefaultCallGraph callGraph) {
        while (!workList.isEmpty()) {
            JMethod jMethod = workList.poll();
            if (callGraph.contains(jMethod)) {
                continue;
            }
            callGraph.addReachableMethod(jMethod);
            callGraph.callSitesIn(jMethod).forEach(invoke -> {
                for (JMethod targetMethod : resolve(invoke)) {
                    callGraph.addEdge(new Edge<>(CallGraphs.getCallKind(invoke), invoke, targetMethod));
                    workList.add(targetMethod);
                }
            });
        }
    }

    /**
     * Resolves call targets (callees) of a call site via CHA.
     */
    private Set<JMethod> resolve(Invoke callSite) {
        Set<JMethod> targetMethods = new HashSet<>();

        MethodRef methodRef = callSite.getMethodRef();
        JClass declaringClass = methodRef.getDeclaringClass();
        Subsignature subsignature = methodRef.getSubsignature();

        if (callSite.isStatic()) {  // static call
            targetMethods.add(declaringClass.getDeclaredMethod(subsignature));
        } else if (callSite.isSpecial()) {  // special call
            JMethod specialCallTarget = dispatch(declaringClass, subsignature);
            if (specialCallTarget != null) {
                targetMethods.add(specialCallTarget);
            }
        } else if (callSite.isVirtual()) {  // virtual call
            fillWithAllSubClassMethod(targetMethods, declaringClass, subsignature);
        } else if (callSite.isInterface()) {  // interface call
            fillWithAllSubInterfaceMethod(targetMethods, declaringClass, subsignature);
        }

        return targetMethods;
    }

    /**
     * Fill the targetMethods set with target method of subInterfaces.
     *
     * @param jInterface is an interface.
     */
    private void fillWithAllSubInterfaceMethod(Set<JMethod> targetMethods, JClass jInterface,
            Subsignature subsignature
    ) {
        for (JClass subInterface : hierarchy.getDirectSubinterfacesOf(jInterface)) {
            fillWithAllSubInterfaceMethod(targetMethods, subInterface, subsignature);
        }
        for (JClass subImplementor : hierarchy.getDirectImplementorsOf(jInterface)) {
            fillWithAllSubClassMethod(targetMethods, subImplementor, subsignature);
        }
    }

    /**
     * Fill the targetMethods set with target method of subInterfaces.
     *
     * @param jClass is a class but not interface.
     */
    private void fillWithAllSubClassMethod(Set<JMethod> targetMethods, JClass jClass,
            Subsignature subsignature
    ) {
        if (jClass == null) {
            return;
        }

        JMethod targetMethod = dispatch(jClass, subsignature);
        if (targetMethods.contains(targetMethod)) {  // avoid the duplicated paths
            return;
        }

        if (targetMethod != null) {
            targetMethods.add(targetMethod);
        }

        for (JClass subClass : hierarchy.getDirectSubclassesOf(jClass)) {
            fillWithAllSubClassMethod(targetMethods, subClass, subsignature);
        }
    }

    /**
     * Looks up the target method based on given class and method subsignature.
     *
     * @return the dispatched target method, or null if no satisfying method
     * can be found.
     */
    private JMethod dispatch(JClass jclass, Subsignature subsignature) {

        // base case
        if (jclass == null) {
            return null;
        }

        JMethod declaredMethod = jclass.getDeclaredMethod(subsignature);
        if (declaredMethod != null && !declaredMethod.isAbstract()) {
            return declaredMethod;
        }

        return dispatch(jclass.getSuperClass(), subsignature);
    }
}
