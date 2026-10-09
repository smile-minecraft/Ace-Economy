package com.smile.aceeconomy.bootstrap;

import com.smile.acelib.lifecycle.LifecycleResult;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Structured failure from the AceLib lifecycle host.
 *
 * <p>Unlike the previous ordered manager (which rethrew the original start exception), the
 * host reports failures as a {@link LifecycleResult}: structural problems (missing
 * dependency, duplicate id, dependency cycle) reject the whole batch before anything runs,
 * and enable/cleanup failures carry per-module codes. The original throwable is logged by
 * AceLib itself under its {@code ACELIB-LIFE-*} code; this exception preserves the codes,
 * the affected module ids and the diagnostic messages so the startup/shutdown log stays
 * actionable.
 */
public final class LifecycleRegistrationException extends Exception {

    private final LifecycleResult result;

    public LifecycleRegistrationException(@NotNull String action,
            @NotNull LifecycleResult result) {
        super(action + " " + result.outcome() + " (host " + result.status() + ")"
                + describe(result));
        this.result = Objects.requireNonNull(result, "result");
    }

    /** The structured host result behind this failure. */
    @NotNull
    public LifecycleResult result() {
        return result;
    }

    private static String describe(@NotNull LifecycleResult result) {
        if (result.problems().isEmpty()) {
            return "";
        }
        return ": " + result.problems().stream()
                .map(problem -> "[" + problem.code().code() + "]"
                        + (problem.moduleId() == null ? "" : " " + problem.moduleId())
                        + " " + problem.message())
                .collect(Collectors.joining("; "));
    }
}
