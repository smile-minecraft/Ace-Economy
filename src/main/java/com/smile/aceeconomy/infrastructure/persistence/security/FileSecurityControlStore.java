package com.smile.aceeconomy.infrastructure.persistence.security;

import com.smile.aceeconomy.domain.AdminAudit;
import com.smile.aceeconomy.domain.SecurityControlAudit;
import com.smile.aceeconomy.domain.SecurityControlState;
import com.smile.aceeconomy.infrastructure.persistence.json.DefaultJsonFilePublisher;
import com.smile.aceeconomy.infrastructure.persistence.json.JsonFilePublisher;
import com.smile.aceeconomy.operations.security.SecurityControlStore;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * File-backed {@link SecurityControlStore} kept at the plugin data-folder
 * root as {@code security-control.json}, outside the economy snapshot.
 *
 * <p>State and its control audits publish together in one atomic file
 * publish (temporary file plus atomic move, then durability sync); a failed
 * publish before the replacement leaves the previous file untouched and
 * throws instead of claiming success. A filesystem without atomic-move
 * support fails closed outright — never a silent non-atomic replacement. A
 * durability-sync failure after the replacement landed throws
 * {@link com.smile.aceeconomy.operations.security.ControlPostPublishException}:
 * the landed content is the new state, so the caller adopts it instead of
 * reporting that nothing was applied. The load path never creates,
 * overwrites, or deletes the file: a first start without a file opens
 * writable, while an existing unreadable file fails closed with a loud fixed
 * diagnosis that never echoes the raw file content. Unknown fields are
 * ignored so newer files stay readable.
 */
public final class FileSecurityControlStore implements SecurityControlStore {

    /** Fixed file name beside the data folder, outside the economy snapshot. */
    public static final String CONTROL_FILE_NAME = "security-control.json";

    private static final int FORMAT_VERSION = 1;

    private final Path controlFile;
    private final JsonFilePublisher publisher;
    private volatile boolean loadFailed;
    private volatile String diagnosis = "";
    private volatile List<SecurityControlAudit> audits = List.of();

    public FileSecurityControlStore(Path controlFile) {
        this(controlFile, new DefaultJsonFilePublisher());
    }

    /** Injectable publish path; tests script pre/post-publish failures through it. */
    FileSecurityControlStore(Path controlFile, JsonFilePublisher publisher) {
        this.controlFile = Objects.requireNonNull(controlFile, "controlFile");
        this.publisher = Objects.requireNonNull(publisher, "publisher");
    }

    public Path controlFile() {
        return controlFile;
    }

    @Override
    public SecurityControlState load() {
        if (!Files.exists(controlFile)) {
            loadFailed = false;
            diagnosis = "";
            audits = List.of();
            return SecurityControlState.open();
        }
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(controlFile);
        } catch (IOException unreadable) {
            return failClosed("the control file cannot be read");
        }
        SecurityControlState parsed;
        try {
            parsed = parse(new String(bytes, StandardCharsets.UTF_8));
        } catch (RuntimeException corrupt) {
            return failClosed("the control file is corrupt");
        }
        loadFailed = false;
        diagnosis = "";
        return parsed;
    }

    @Override
    public void save(SecurityControlState state, SecurityControlAudit audit) {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(audit, "audit");
        List<SecurityControlAudit> next = new ArrayList<>(audits);
        next.add(audit);
        String document = render(state, next);
        byte[] bytes = document.getBytes(StandardCharsets.UTF_8);
        Path tmp = null;
        try {
            Path parent = controlFile.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            tmp = controlFile.resolveSibling(
                    controlFile.getFileName() + ".tmp-" + UUID.randomUUID());
            Files.write(tmp, bytes, StandardOpenOption.CREATE_NEW);
            boolean published = false;
            try {
                publisher.moveAtomic(tmp, controlFile);
                published = true;
                publisher.syncFile(controlFile);
                Path dir = controlFile.toAbsolutePath().getParent();
                if (dir != null) {
                    publisher.syncDirectory(dir);
                }
            } catch (AtomicMoveNotSupportedException notAtomic) {
                // No silent non-atomic replacement: the previous file, state
                // and audits stay exactly as they were.
                throw new IllegalStateException(
                        "Security control publish requires an atomic file replacement,"
                                + " but the filesystem does not support it; no state or audit"
                                + " was applied",
                        notAtomic);
            } catch (IOException | RuntimeException syncFailure) {
                if (published) {
                    // The replacement landed, so its content is the new
                    // state. Adopt the audits and let the service adopt the
                    // state through the exception below — never claim that
                    // nothing was applied while memory stays open.
                    audits = List.copyOf(next);
                    throw new com.smile.aceeconomy.operations.security
                            .ControlPostPublishException(
                            "Security control replacement landed on disk but its durability"
                                    + " sync failed; the new state was adopted — verify the"
                                    + " control file and restart if the machine crashed",
                            syncFailure);
                }
                throw new IllegalStateException(
                        "Security control publish failed; no state or audit was applied",
                        syncFailure);
            } finally {
                if (tmp != null) {
                    try {
                        Files.deleteIfExists(tmp);
                    } catch (IOException ignored) {
                        // Best-effort cleanup; the temp name can never collide
                        // with the live file.
                    }
                }
            }
        } catch (IOException publishFailure) {
            throw new IllegalStateException(
                    "Security control publish failed; no state or audit was applied",
                    publishFailure);
        }
        audits = List.copyOf(next);
    }

    @Override
    public boolean isLoadFailed() {
        return loadFailed;
    }

    @Override
    public String loadDiagnosis() {
        return diagnosis;
    }

    @Override
    public List<SecurityControlAudit> audits() {
        return audits;
    }

    private SecurityControlState failClosed(String cause) {
        loadFailed = true;
        diagnosis = "Safety control file '" + CONTROL_FILE_NAME + "' " + cause
                + "; all balance-changing operations are refused until an operator repairs"
                + " the file and restarts (fail-closed).";
        audits = List.of();
        return SecurityControlState.loadFailed(diagnosis);
    }

    private SecurityControlState parse(String document) {
        Object root = ControlJson.parse(document);
        if (!(root instanceof Map<?, ?> model)) {
            throw new IllegalArgumentException("control file must be a JSON object");
        }
        // Strict schema: every field written by save must be present, non-null
        // and exactly typed. A missing file still opens writable (handled in
        // load), but any existing incomplete file fails closed. Unknown extra
        // fields are ignored so newer files stay readable.
        long format = requireFormat(model);
        if (format != FORMAT_VERSION) {
            throw new IllegalArgumentException("control file holds an unknown format");
        }
        long version = requireVersion(model);
        boolean readonly = requireBoolean(model, "globalReadonly");
        Set<UUID> frozen = requireFrozen(model);
        List<SecurityControlAudit> parsedAudits = requireAudits(model);
        audits = List.copyOf(parsedAudits);
        return new SecurityControlState(frozen, readonly, false, version);
    }

    private static SecurityControlAudit parseAudit(Map<?, ?> row) {
        // Strict audit schema: every key written by save must be present.
        // Unknown extra keys are ignored. All messages are fixed and never
        // echo raw file content.
        requireKeyPresent(row, "id");
        requireKeyPresent(row, "operation");
        requireKeyPresent(row, "operatorId");
        requireKeyPresent(row, "operatorName");
        requireKeyPresent(row, "reason");
        requireKeyPresent(row, "timestamp");
        requireKeyPresent(row, "target");
        String idRaw = stringField(row, "id");
        String operation = stringField(row, "operation");
        String reasonRaw = stringField(row, "reason");
        String operatorName = stringField(row, "operatorName");
        Object operatorIdObj = row.get("operatorId");
        Object targetObj = row.get("target");
        String timestampRaw = stringField(row, "timestamp");
        UUID id = requireCanonicalUuid(idRaw, "control audit holds a bad id");
        if (!isKnownOperation(operation)) {
            throw new IllegalArgumentException("unknown control operation");
        }
        String reason;
        try {
            reason = AdminAudit.validateReason(reasonRaw);
        } catch (IllegalArgumentException badReason) {
            throw new IllegalArgumentException("control audit holds a bad reason");
        }
        if (!reason.equals(reasonRaw)) {
            throw new IllegalArgumentException("control audit holds a bad reason");
        }
        if (operatorName == null || operatorName.isBlank()) {
            throw new IllegalArgumentException("control audit holds a bad operator");
        }
        UUID operatorId = null;
        if (operatorIdObj != null) {
            if (!(operatorIdObj instanceof String operatorIdRaw)) {
                throw new IllegalArgumentException("control audit holds a bad operator id");
            }
            operatorId = requireCanonicalUuid(operatorIdRaw,
                    "control audit holds a bad operator id");
        }
        if (operatorId == null) {
            if (!AdminAudit.CONSOLE_OPERATOR_NAME.equals(operatorName)) {
                throw new IllegalArgumentException("control audit holds a bad operator");
            }
        }
        Instant timestamp;
        try {
            timestamp = Instant.parse(timestampRaw);
        } catch (RuntimeException badTime) {
            throw new IllegalArgumentException("control audit holds a bad timestamp");
        }
        UUID target = null;
        if (targetObj != null) {
            if (!(targetObj instanceof String targetRaw)) {
                throw new IllegalArgumentException("control audit holds a bad target id");
            }
            target = requireCanonicalUuid(targetRaw, "control audit holds a bad target id");
        }
        AdminAudit audit;
        if (operatorId == null) {
            audit = AdminAudit.console(reason);
        } else {
            audit = AdminAudit.player(operatorId, operatorName, reason);
        }
        return switch (operation) {
            case SecurityControlAudit.OPERATION_FREEZE -> {
                if (target == null) {
                    throw new IllegalArgumentException("freeze audit must name a target");
                }
                yield SecurityControlAudit.rehydrate(id, audit, timestamp, operation, target);
            }
            case SecurityControlAudit.OPERATION_UNFREEZE -> {
                if (target == null) {
                    throw new IllegalArgumentException("unfreeze audit must name a target");
                }
                yield SecurityControlAudit.rehydrate(id, audit, timestamp, operation, target);
            }
            case SecurityControlAudit.OPERATION_READONLY_ON -> {
                if (target != null) {
                    throw new IllegalArgumentException("read-only audit must not name a target");
                }
                yield SecurityControlAudit.rehydrate(id, audit, timestamp, operation, null);
            }
            case SecurityControlAudit.OPERATION_READONLY_OFF -> {
                if (target != null) {
                    throw new IllegalArgumentException("read-only audit must not name a target");
                }
                yield SecurityControlAudit.rehydrate(id, audit, timestamp, operation, null);
            }
            default -> throw new IllegalArgumentException("unknown control operation");
        };
    }

    private static String render(SecurityControlState state, List<SecurityControlAudit> trail) {
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("format", (long) FORMAT_VERSION);
        model.put("version", state.version());
        model.put("globalReadonly", state.isGlobalReadonly());
        List<Object> frozen = new ArrayList<>();
        for (UUID id : state.frozenAccounts()) {
            frozen.add(id.toString());
        }
        frozen.sort(null);
        model.put("frozen", frozen);
        List<Object> auditRows = new ArrayList<>();
        for (SecurityControlAudit audit : trail) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", audit.id().toString());
            row.put("operation", audit.operation());
            row.put("operatorId", audit.operatorId() == null
                    ? null : audit.operatorId().toString());
            row.put("operatorName", audit.operatorName());
            row.put("reason", audit.reason());
            row.put("timestamp", audit.timestamp().toString());
            row.put("target", audit.targetAccount().map(UUID::toString).orElse(null));
            auditRows.add(row);
        }
        model.put("audits", auditRows);
        return ControlJson.render(model);
    }

    private static long requireFormat(Map<?, ?> model) {
        requireKeyPresent(model, "format");
        Object raw = model.get("format");
        if (!(raw instanceof Long number)) {
            throw new IllegalArgumentException("control file field 'format' must be an integer");
        }
        return number;
    }

    private static long requireVersion(Map<?, ?> model) {
        requireKeyPresent(model, "version");
        Object raw = model.get("version");
        if (!(raw instanceof Long number)) {
            throw new IllegalArgumentException("control file field 'version' must be an integer");
        }
        if (number < 0) {
            throw new IllegalArgumentException("control file holds a bad version");
        }
        return number;
    }

    private static boolean requireBoolean(Map<?, ?> model, String key) {
        requireKeyPresent(model, key);
        Object raw = model.get(key);
        if (!(raw instanceof Boolean flag)) {
            throw new IllegalArgumentException("control file field '" + key + "' must be a boolean");
        }
        return flag;
    }

    private static Set<UUID> requireFrozen(Map<?, ?> model) {
        requireKeyPresent(model, "frozen");
        Object raw = model.get("frozen");
        if (!(raw instanceof List<?> list)) {
            throw new IllegalArgumentException("control file 'frozen' must be an array");
        }
        Set<UUID> frozen = new HashSet<>();
        for (Object entry : list) {
            if (!(entry instanceof String text)) {
                throw new IllegalArgumentException("control file frozen entries must be UUIDs");
            }
            UUID id = requireCanonicalUuid(text, "control file holds a bad account id");
            if (!frozen.add(id)) {
                throw new IllegalArgumentException("control file holds a duplicate account id");
            }
        }
        return frozen;
    }

    private static List<SecurityControlAudit> requireAudits(Map<?, ?> model) {
        requireKeyPresent(model, "audits");
        Object raw = model.get("audits");
        if (!(raw instanceof List<?> list)) {
            throw new IllegalArgumentException("control file 'audits' must be an array");
        }
        List<SecurityControlAudit> parsed = new ArrayList<>();
        Set<UUID> seenIds = new HashSet<>();
        for (Object entry : list) {
            if (!(entry instanceof Map<?, ?> row)) {
                throw new IllegalArgumentException("control audit entries must be objects");
            }
            SecurityControlAudit audit = parseAudit(row);
            if (!seenIds.add(audit.id())) {
                throw new IllegalArgumentException("control file holds a duplicate audit id");
            }
            parsed.add(audit);
        }
        return parsed;
    }

    private static void requireKeyPresent(Map<?, ?> model, String key) {
        if (!model.containsKey(key)) {
            throw new IllegalArgumentException("control file is missing '" + key + "'");
        }
        if (model.get(key) == null && !key.equals("operatorId") && !key.equals("target")) {
            // Top-level fields and required audit strings must be non-null.
            // Nullable audit keys (operatorId/target) are checked by the caller
            // through containsKey, so a null value there is legal.
            boolean topLevel = key.equals("format") || key.equals("version")
                    || key.equals("globalReadonly") || key.equals("frozen") || key.equals("audits");
            if (topLevel) {
                throw new IllegalArgumentException("control file field '" + key + "' must not be null");
            }
        }
    }

    private static UUID requireCanonicalUuid(String text, String failure) {
        UUID id;
        try {
            id = UUID.fromString(text);
        } catch (IllegalArgumentException badUuid) {
            throw new IllegalArgumentException(failure);
        }
        if (!id.toString().equals(text)) {
            throw new IllegalArgumentException(failure);
        }
        return id;
    }

    private static boolean isKnownOperation(String operation) {
        return SecurityControlAudit.OPERATION_FREEZE.equals(operation)
                || SecurityControlAudit.OPERATION_UNFREEZE.equals(operation)
                || SecurityControlAudit.OPERATION_READONLY_ON.equals(operation)
                || SecurityControlAudit.OPERATION_READONLY_OFF.equals(operation);
    }

    private static String stringField(Map<?, ?> row, String key) {
        Object raw = row.get(key);
        if (!(raw instanceof String text)) {
            throw new IllegalArgumentException("control file field '" + key + "' must be a string");
        }
        return text;
    }

    private static String optionalString(Map<?, ?> row, String key) {
        Object raw = row.get(key);
        if (raw == null) {
            return null;
        }
        if (!(raw instanceof String text)) {
            throw new IllegalArgumentException("control file field '" + key + "' must be a string");
        }
        return text;
    }
}
