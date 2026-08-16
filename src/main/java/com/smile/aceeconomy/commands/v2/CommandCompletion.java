package com.smile.aceeconomy.commands.v2;

import java.util.List;
import java.util.Locale;

/** Deterministic, case-insensitive completion helpers shared by command specs. */
public final class CommandCompletion {

    private CommandCompletion() {
    }

    public static List<String> byPrefix(List<String> candidates, String rawPrefix) {
        String prefix = rawPrefix == null ? "" : rawPrefix.toLowerCase(Locale.ROOT);
        return candidates.stream()
                .filter(candidate -> candidate != null && candidate.toLowerCase(Locale.ROOT).startsWith(prefix))
                .distinct()
                .toList();
    }

    public static String last(List<String> args) {
        return args == null || args.isEmpty() ? "" : args.get(args.size() - 1);
    }
}
