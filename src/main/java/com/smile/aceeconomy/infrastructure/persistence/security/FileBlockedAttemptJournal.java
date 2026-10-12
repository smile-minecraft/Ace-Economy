package com.smile.aceeconomy.infrastructure.persistence.security;

import com.smile.aceeconomy.domain.BlockedControlAttempt;
import com.smile.aceeconomy.operations.security.BlockedAttemptJournal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * File-backed append-only journal for refused-attempt evidence, kept as
 * {@code security-control-attempts.jsonl} beside the control file.
 *
 * <p>One line per refused attempt, appended without ever rewriting the
 * control state file; the file is created on the first append and never
 * truncated or deleted here. Each line carries only the minimal metadata:
 * the operation, the target account when there is one, the fixed refusal
 * category, the control version, the trusted operator, and the timestamp.
 * The full command line, IPs, and raw reason text are never stored.
 * Attempt records are read-only evidence: listing them can never
 * reconstruct control state, so bypassing the state file through the
 * journal is impossible. A failed append throws instead of claiming
 * audit success; the refused mutation stays refused.
 */
public final class FileBlockedAttemptJournal implements BlockedAttemptJournal {

    /** Fixed file name beside the data folder, next to the control file. */
    public static final String JOURNAL_FILE_NAME = "security-control-attempts.jsonl";

    private final Path journalFile;

    public FileBlockedAttemptJournal(Path journalFile) {
        this.journalFile = Objects.requireNonNull(journalFile, "journalFile");
    }

    public Path journalFile() {
        return journalFile;
    }

    @Override
    public synchronized void append(BlockedControlAttempt attempt) {
        Objects.requireNonNull(attempt, "attempt");
        String line = render(attempt) + "\n";
        try {
            Path parent = journalFile.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(journalFile, line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException appendFailure) {
            throw new IllegalStateException(
                    "Blocked-attempt journal append failed; the mutation stays refused"
                            + " and no audit success is claimed",
                    appendFailure);
        }
    }

    @Override
    public List<BlockedControlAttempt> list() {
        List<String> lines;
        try {
            if (!Files.exists(journalFile)) {
                return List.of();
            }
            lines = Files.readAllLines(journalFile, StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            throw new IllegalStateException("Blocked-attempt journal cannot be read", unreadable);
        }
        List<BlockedControlAttempt> attempts = new ArrayList<>();
        for (String line : lines) {
            if (line.isBlank()) {
                continue;
            }
            try {
                attempts.add(parse(line));
            } catch (RuntimeException corrupt) {
                throw new IllegalStateException(
                        "Blocked-attempt journal holds a corrupt line", corrupt);
            }
        }
        return List.copyOf(attempts);
    }

    private static String render(BlockedControlAttempt attempt) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", attempt.id().toString());
        row.put("timestamp", attempt.timestamp().toString());
        row.put("operation", attempt.operation());
        row.put("target", attempt.targetAccount().map(UUID::toString).orElse(null));
        row.put("category", attempt.refusalCategory());
        row.put("controlVersion", attempt.controlVersion());
        row.put("operatorId", attempt.operatorId().map(UUID::toString).orElse(null));
        row.put("operatorName", attempt.operatorName().orElse(null));
        return ControlJson.render(row);
    }

    private static BlockedControlAttempt parse(String line) {
        Object root = ControlJson.parse(line);
        if (!(root instanceof Map<?, ?> row)) {
            throw new IllegalArgumentException("journal line must be a JSON object");
        }
        String operation = stringField(row, "operation");
        String category = stringField(row, "category");
        long version = versionField(row);
        UUID target = optionalUuid(row, "target");
        UUID operatorId = optionalUuid(row, "operatorId");
        Object nameRaw = row.get("operatorName");
        String operatorName = nameRaw instanceof String text ? text : null;
        Object tsRaw = row.get("timestamp");
        Instant timestamp = Instant.now();
        if (tsRaw instanceof String text) {
            try {
                timestamp = Instant.parse(text);
            } catch (RuntimeException badTime) {
                throw new IllegalArgumentException("journal line holds a bad timestamp");
            }
        }
        return BlockedControlAttempt.of(operation, target, category, version,
                operatorId, operatorName, timestamp);
    }

    private static String stringField(Map<?, ?> row, String key) {
        Object raw = row.get(key);
        if (!(raw instanceof String text)) {
            throw new IllegalArgumentException("journal field '" + key + "' must be a string");
        }
        return text;
    }

    private static long versionField(Map<?, ?> row) {
        Object raw = row.get("controlVersion");
        if (raw instanceof Long number) {
            return number;
        }
        if (raw instanceof Double number) {
            return number.longValue();
        }
        throw new IllegalArgumentException("journal field 'controlVersion' must be a number");
    }

    private static UUID optionalUuid(Map<?, ?> row, String key) {
        Object raw = row.get(key);
        if (raw == null) {
            return null;
        }
        if (!(raw instanceof String text)) {
            throw new IllegalArgumentException("journal field '" + key + "' must be a UUID string");
        }
        try {
            return UUID.fromString(text);
        } catch (IllegalArgumentException badUuid) {
            throw new IllegalArgumentException("journal field '" + key + "' holds a bad UUID");
        }
    }
}
