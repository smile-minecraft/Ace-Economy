package com.smile.aceeconomy.bootstrap;

import com.smile.acelib.AceLibApi.AceLibProvider;
import com.smile.acelib.lifecycle.LifecycleHost;
import com.smile.acelib.lifecycle.LifecycleModule;
import com.smile.acelib.lifecycle.LifecycleResult;
import com.smile.acelib.lifecycle.LifecycleResult.Code;
import com.smile.acelib.lifecycle.LifecycleResult.Outcome;
import com.smile.acelib.lifecycle.LifecycleResult.Problem;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Deterministic test double for {@link LifecycleHost}.
 *
 * <p>Mirrors the observable semantics of AceLib 1.4.0 {@code LifecycleHostImpl} for the two
 * operations downstream code may call ({@code register}/{@code unregister}):
 * <ul>
 *   <li>structural validation (duplicate id, missing dependency, dependency cycle) happens
 *       before any enable callback runs; a rejection runs nothing;</li>
 *   <li>only the newly added ids are enabled, in global topological order (id-sorted
 *       tie-break, same as the production Kahn pass);</li>
 *   <li>an enable failure reverse-closes the handles delivered so far in the same batch
 *       and reports {@code ENABLE_FAILED} (plus {@code CLOSE_FAILED} when compensation
 *       itself throws);</li>
 *   <li>unregister closes only the calling owner's modules in reverse topological order,
 *       refuses with {@code ACTIVE_DEPENDENTS} while another owner's live module still
 *       (transitively) depends on them, and is a success no-op for an owner with no
 *       modules.</li>
 * </ul>
 *
 * <p>Deliberately simplified against production: AceLib core reload internals
 * ({@code beginReload}/{@code finishReload}) are package-private in AceLib and not modelled;
 * a {@code FAILED} status here only follows cleanup failures. Live-host divergence risk
 * stays with the Folia test-server verification, not with these unit tests.
 */
public final class FakeLifecycleHost implements LifecycleHost {

    private final AceLibProvider provider;
    private final Map<String, OwnedModule> modules = new LinkedHashMap<>();
    private Status status = Status.READY;
    private LifecycleResult lastResult =
            new LifecycleResult(Outcome.SUCCESS, status, List.of(), List.of());
    private int registerCalls;
    private int unregisterCalls;

    public FakeLifecycleHost(AceLibProvider provider) {
        this.provider = Objects.requireNonNull(provider, "provider");
    }

    /** How many times {@link #register} was invoked (re-registration guard). */
    public int registerCalls() {
        return registerCalls;
    }

    /** How many times {@link #unregister} was invoked. */
    public int unregisterCalls() {
        return unregisterCalls;
    }

    @Override
    public synchronized Status status() {
        return status;
    }

    @Override
    public synchronized LifecycleResult lastResult() {
        return lastResult;
    }

    @Override
    public synchronized LifecycleResult register(Plugin owner,
            Collection<LifecycleModule> batch) {
        registerCalls++;
        if (owner == null) {
            return record(reject(Code.INVALID_MODULE, null, List.of(),
                    "module owner must not be null"));
        }
        if (batch == null) {
            return record(reject(Code.INVALID_MODULE, null, List.of(),
                    "module batch must not be null"));
        }
        if (status != Status.READY) {
            return record(reject(Code.INVALID_STATE, null, List.of(),
                    "host does not accept registration while " + status));
        }

        List<Problem> problems = new ArrayList<>();
        Map<String, LifecycleModule> added = new TreeMap<>();
        Map<String, Integer> counts = new HashMap<>();
        for (LifecycleModule module : batch) {
            if (module == null) {
                problems.add(problem(Code.INVALID_MODULE, null, List.of(),
                        "module batch contains a null declaration"));
                continue;
            }
            counts.merge(module.id(), 1, Integer::sum);
            added.putIfAbsent(module.id(), module);
        }
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            if (entry.getValue() > 1 || modules.containsKey(entry.getKey())) {
                problems.add(problem(Code.DUPLICATE_ID, entry.getKey(), List.of(entry.getKey()),
                        "module id is already present in this batch or host graph"));
            }
        }

        Map<String, LifecycleModule> graph = definitions();
        for (Map.Entry<String, LifecycleModule> entry : added.entrySet()) {
            if (!modules.containsKey(entry.getKey())) {
                graph.put(entry.getKey(), entry.getValue());
            }
        }
        for (LifecycleModule module : graph.values()) {
            for (String dependency : module.dependsOn().stream().sorted().toList()) {
                if (!graph.containsKey(dependency)) {
                    problems.add(problem(Code.MISSING_DEPENDENCY, module.id(), List.of(dependency),
                            "module depends on an id that is not registered"));
                }
            }
        }
        findCycles(graph, problems);
        List<String> affected = added.keySet().stream().sorted().toList();
        if (!problems.isEmpty()) {
            return record(new LifecycleResult(Outcome.REJECTED, status, problems, affected));
        }

        List<OwnedModule> started = new ArrayList<>();
        for (String id : topologicalOrder(graph)) {
            LifecycleModule declaration = added.get(id);
            if (declaration == null) {
                continue;
            }
            try {
                LifecycleModule.Handle handle = declaration.starter()
                        .enable(new LifecycleModule.Context(owner, provider));
                if (handle == null) {
                    throw new IllegalStateException("module enable returned a null handle");
                }
                started.add(new OwnedModule(owner, declaration, handle));
            } catch (Throwable failure) {
                problems.add(problem(Code.ENABLE_FAILED, id, List.of(id),
                        "module enable callback failed: " + failure));
                compensate(started, problems);
                return record(new LifecycleResult(Outcome.FAILED, status, problems, affected));
            }
        }
        for (OwnedModule startedModule : started) {
            modules.put(startedModule.declaration.id(), startedModule);
        }
        return record(new LifecycleResult(Outcome.SUCCESS, status, List.of(), affected));
    }

    @Override
    public synchronized LifecycleResult unregister(Plugin owner) {
        unregisterCalls++;
        if (owner == null) {
            return record(reject(Code.INVALID_MODULE, null, List.of(),
                    "module owner must not be null"));
        }
        Set<String> ownedIds = new TreeSet<>();
        for (OwnedModule module : modules.values()) {
            if (module.owner == owner) {
                ownedIds.add(module.declaration.id());
            }
        }
        if (ownedIds.isEmpty()) {
            return record(new LifecycleResult(Outcome.SUCCESS, status, List.of(), List.of()));
        }
        if (status != Status.READY && status != Status.FAILED) {
            return record(reject(Code.INVALID_STATE, null, List.copyOf(ownedIds),
                    "host does not accept unregister while " + status));
        }
        if (status != Status.FAILED) {
            Set<String> blockers = new TreeSet<>();
            for (OwnedModule candidate : modules.values()) {
                if (candidate.owner != owner
                        && transitivelyDependsOn(candidate.declaration, ownedIds, new HashSet<>())) {
                    blockers.add(candidate.declaration.id());
                }
            }
            if (!blockers.isEmpty()) {
                return record(reject(Code.ACTIVE_DEPENDENTS, null, List.copyOf(blockers),
                        "other plugin modules still depend on modules owned by this owner"));
            }
        }

        List<Problem> problems = new ArrayList<>();
        List<String> owned = List.copyOf(ownedIds);
        closeInReverseOrder(owned, problems);
        if (!problems.isEmpty()) {
            status = Status.FAILED;
            return record(new LifecycleResult(Outcome.FAILED, status, problems, owned));
        }
        owned.forEach(modules::remove);
        return record(new LifecycleResult(Outcome.SUCCESS, status, List.of(), owned));
    }

    private void compensate(List<OwnedModule> started, List<Problem> problems) {
        for (int i = started.size() - 1; i >= 0; i--) {
            try {
                started.get(i).handle.close();
            } catch (Throwable failure) {
                status = Status.FAILED;
                problems.add(problem(Code.CLOSE_FAILED, started.get(i).declaration.id(),
                        List.of(started.get(i).declaration.id()),
                        "module handle cleanup failed: " + failure));
            }
        }
    }

    private void closeInReverseOrder(List<String> selectedIds, List<Problem> problems) {
        Map<String, LifecycleModule> graph = new TreeMap<>();
        for (OwnedModule entry : modules.values()) {
            graph.put(entry.declaration.id(), entry.declaration);
        }
        List<String> ordered = topologicalOrder(graph);
        Set<String> selected = new HashSet<>(selectedIds);
        for (int i = ordered.size() - 1; i >= 0; i--) {
            String id = ordered.get(i);
            if (!selected.contains(id)) {
                continue;
            }
            OwnedModule entry = modules.get(id);
            if (entry != null && entry.handle != null) {
                try {
                    entry.handle.close();
                } catch (Throwable failure) {
                    problems.add(problem(Code.CLOSE_FAILED, id, List.of(id),
                            "module handle cleanup failed: " + failure));
                }
            }
        }
    }

    private boolean transitivelyDependsOn(LifecycleModule candidate, Set<String> targets,
            Set<String> visited) {
        for (String dependency : candidate.dependsOn()) {
            if (targets.contains(dependency)) {
                return true;
            }
            if (!visited.add(dependency)) {
                continue;
            }
            OwnedModule dependencyModule = modules.get(dependency);
            if (dependencyModule != null
                    && transitivelyDependsOn(dependencyModule.declaration, targets, visited)) {
                return true;
            }
        }
        return false;
    }

    private Map<String, LifecycleModule> definitions() {
        Map<String, LifecycleModule> result = new TreeMap<>();
        for (OwnedModule module : modules.values()) {
            result.put(module.declaration.id(), module.declaration);
        }
        return result;
    }

    private static List<String> topologicalOrder(Map<String, LifecycleModule> graph) {
        Map<String, Integer> dependencyCount = new HashMap<>();
        Map<String, Set<String>> dependents = new HashMap<>();
        for (String id : graph.keySet()) {
            dependencyCount.put(id, 0);
            dependents.put(id, new TreeSet<>());
        }
        for (LifecycleModule module : graph.values()) {
            int count = 0;
            for (String dependency : module.dependsOn()) {
                if (graph.containsKey(dependency)) {
                    count++;
                    dependents.get(dependency).add(module.id());
                }
            }
            dependencyCount.put(module.id(), count);
        }
        PriorityQueue<String> ready = new PriorityQueue<>();
        dependencyCount.forEach((id, count) -> {
            if (count == 0) {
                ready.add(id);
            }
        });
        List<String> ordered = new ArrayList<>(graph.size());
        while (!ready.isEmpty()) {
            String id = ready.remove();
            ordered.add(id);
            for (String dependent : dependents.get(id)) {
                int remaining = dependencyCount.merge(dependent, -1, Integer::sum);
                if (remaining == 0) {
                    ready.add(dependent);
                }
            }
        }
        return ordered;
    }

    private void findCycles(Map<String, LifecycleModule> graph, List<Problem> problems) {
        Map<String, Integer> state = new HashMap<>();
        for (String id : new TreeSet<>(graph.keySet())) {
            if (!state.containsKey(id)) {
                visit(id, graph, state, new ArrayList<>(), problems);
            }
        }
    }

    private void visit(String id, Map<String, LifecycleModule> graph, Map<String, Integer> state,
            List<String> stack, List<Problem> problems) {
        state.put(id, 1);
        stack.add(id);
        LifecycleModule module = graph.get(id);
        if (module != null) {
            for (String dependency : module.dependsOn().stream().sorted().toList()) {
                if (!graph.containsKey(dependency)) {
                    continue;
                }
                Integer seen = state.get(dependency);
                if (seen == null) {
                    visit(dependency, graph, state, stack, problems);
                } else if (seen == 1) {
                    List<String> cycle = new TreeSet<>(
                            stack.subList(stack.indexOf(dependency), stack.size()))
                            .stream().toList();
                    problems.add(problem(Code.DEPENDENCY_CYCLE, null, cycle,
                            "module dependency graph contains a cycle"));
                }
            }
        }
        stack.remove(stack.size() - 1);
        state.put(id, 2);
    }

    private LifecycleResult reject(Code code, String moduleId, List<String> relatedIds,
            String message) {
        return record(new LifecycleResult(Outcome.REJECTED, status,
                List.of(problem(code, moduleId, relatedIds, message)), relatedIds));
    }

    private LifecycleResult record(LifecycleResult result) {
        lastResult = result;
        return result;
    }

    private static Problem problem(Code code, String moduleId, List<String> relatedIds,
            String message) {
        return new Problem(code, moduleId, relatedIds, message);
    }

    private static final class OwnedModule {
        private final Plugin owner;
        private final LifecycleModule declaration;
        private LifecycleModule.Handle handle;

        private OwnedModule(Plugin owner, LifecycleModule declaration,
                LifecycleModule.Handle handle) {
            this.owner = owner;
            this.declaration = declaration;
            this.handle = handle;
        }
    }
}
