package com.smile.aceeconomy.domain;

import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Operator identity plus free-text reason carried from an administrative command
 * into the persisted transaction.
 *
 * <p>A recorded audit names the sender at command time: a player contributes the
 * UUID and the display name seen then, the console contributes a fixed marker.
 * The identity is always derived from the sender itself, never from a command
 * argument, so one operator cannot file a record under another operator's name.
 *
 * <p>A missing audit ({@link #unrecorded()}) means the record predates operator
 * tracking or travelled a path that carries no sender. It is distinct from a
 * console record: console stores a null id with the fixed console name, while an
 * unrecorded audit stores null for both.
 */
public final class AdminAudit {

    /** Fixed operator name stored for console senders. */
    public static final String CONSOLE_OPERATOR_NAME = "CONSOLE";

    /** Reason stored when the operator gives none. Never null on new records. */
    public static final String DEFAULT_REASON = "unspecified";

    /** Longest accepted reason text, in characters. */
    public static final int MAX_REASON_LENGTH = 256;

    /**
     * Separator between a machine-readable prefix ({@code rollback:<category>},
     * {@code import:<source>}) and the operator reason. A persisted reversal or
     * import record therefore reads {@code rollback:deposit|harbor-check}: the
     * machine prefix stays first so existing readers keep matching, and the
     * operator reason follows after one separator character.
     */
    public static final String REASON_SEPARATOR = "|";

    private static final Pattern WHITESPACE = Pattern.compile("\\s");
    private static final Pattern URL_SCHEME = Pattern.compile("[a-z][a-z0-9+.-]*://",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern IPV4 = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}");
    private static final Pattern IPV6_HEX_GROUP = Pattern.compile("[0-9a-fA-F]{1,4}");
    /**
     * A full eight-group literal occurring anywhere inside the token. The
     * lookarounds require a non-hex, non-colon separator (for example
     * {@code =}, {@code -} or the token edge) so {@code ip=2001:db8:0:0:0:0:0:1}
     * is caught while {@code foo:bar} and {@code dead:beef} stay allowed.
     */
    private static final Pattern IPV6_FULL_SUBSTRING = Pattern.compile(
            "(?<![0-9a-fA-F:])(?:[0-9a-fA-F]{1,4}:){7}[0-9a-fA-F]{1,4}(?![0-9a-fA-F:])");
    /** An uncompressed literal with a dotted-quad tail, occurring anywhere inside the token. */
    private static final Pattern IPV6_MIXED_SUBSTRING = Pattern.compile(
            "(?<![0-9a-fA-F:.])(?:[0-9a-fA-F]{1,4}:){6}\\d{1,3}(?:\\.\\d{1,3}){3}(?![0-9a-fA-F:.])");
    private static final Pattern SECRET_WORD =
            Pattern.compile("(webhook|token|secret|password|bearer|api[-_]?key|private[-_]?key)",
                    Pattern.CASE_INSENSITIVE);
    private static final Pattern SECRET_PREFIX =
            Pattern.compile("^(sk-|ghp_|gho_|ghu_|xox|AKIA).*", Pattern.CASE_INSENSITIVE);

    private final UUID operatorId;
    private final String operatorName;
    private final String reason;
    private final boolean recorded;

    private AdminAudit(UUID operatorId, String operatorName, String reason, boolean recorded) {
        this.operatorId = operatorId;
        this.operatorName = operatorName;
        this.reason = reason;
        this.recorded = recorded;
    }

    /** Marker for records that carry no operator information. */
    public static AdminAudit unrecorded() {
        return new AdminAudit(null, null, null, false);
    }

    /** Audit for a console sender. */
    public static AdminAudit console(String reason) {
        return new AdminAudit(null, CONSOLE_OPERATOR_NAME, validateReason(reason), true);
    }

    /** Audit for a player sender, using the identity seen at command time. */
    public static AdminAudit player(UUID operatorId, String operatorName, String reason) {
        Objects.requireNonNull(operatorId, "operatorId");
        Objects.requireNonNull(operatorName, "operatorName");
        if (operatorName.isBlank()) {
            throw new IllegalArgumentException("operatorName must not be blank");
        }
        return new AdminAudit(operatorId, operatorName, validateReason(reason), true);
    }

    /**
     * Normalizes a raw reason token. Null becomes the default; anything else is
     * returned trimmed. Blank, over-long, multi-word and secret-looking values
     * are rejected with a fixed message that never echoes the raw value.
     *
     * @throws IllegalArgumentException with a fixed message that never carries the raw value
     */
    public static String validateReason(String raw) {
        if (raw == null) {
            return DEFAULT_REASON;
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("reason must not be blank");
        }
        if (trimmed.length() > MAX_REASON_LENGTH) {
            throw new IllegalArgumentException("reason must be at most " + MAX_REASON_LENGTH + " characters");
        }
        if (WHITESPACE.matcher(trimmed).find()) {
            throw new IllegalArgumentException("reason must be a single word without spaces");
        }
        if (URL_SCHEME.matcher(trimmed).find() || IPV4.matcher(trimmed).find()
                || looksLikeIpv6(trimmed)) {
            throw new IllegalArgumentException("reason must not contain a network address");
        }
        if (SECRET_WORD.matcher(trimmed).find() || SECRET_PREFIX.matcher(trimmed).find()) {
            throw new IllegalArgumentException("reason must not look like a credential");
        }
        return trimmed;
    }

    /**
     * Best-effort IPv6 screen over a single-token reason. Pure string checks
     * only: no DNS lookup and no network access. Compressed ({@code ::}),
     * bracketed ({@code [::1]}), zoned ({@code fe80::1%eth0}), mapped
     * ({@code ::ffff:192.0.2.1}, already caught through its dotted tail) and
     * full eight-group forms are refused, including a full or mixed-tail
     * literal embedded in a longer token ({@code ip=2001:db8:0:0:0:0:0:1}).
     * A single colon (for example {@code foo:bar}) stays allowed so ordinary
     * tokens are not misjudged.
     */
    private static boolean looksLikeIpv6(String value) {
        if ((value.indexOf('[') >= 0 || value.indexOf(']') >= 0) && value.indexOf(':') >= 0) {
            return true;
        }
        if (value.indexOf('%') >= 0 && value.indexOf(':') >= 0) {
            return true;
        }
        if (value.contains("::")) {
            return true;
        }
        if (IPV6_FULL_SUBSTRING.matcher(value).find()
                || IPV6_MIXED_SUBSTRING.matcher(value).find()) {
            return true;
        }
        int colons = 0;
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) == ':') {
                colons++;
            }
        }
        if (colons != 7) {
            return false;
        }
        String[] parts = value.split(":", -1);
        if (parts.length != 8) {
            return false;
        }
        for (String part : parts) {
            if (!IPV6_HEX_GROUP.matcher(part).matches()) {
                return false;
            }
        }
        return true;
    }

    public UUID operatorId() {
        return operatorId;
    }

    public String operatorName() {
        return operatorName;
    }

    public String reason() {
        return reason;
    }

    public boolean isRecorded() {
        return recorded;
    }

    /**
     * Composes the persisted reason for a machine-stamped record: the machine
     * prefix stays first, the operator reason follows after
     * {@link #REASON_SEPARATOR}. An unrecorded audit (a legacy caller that
     * carries no sender) keeps the bare prefix, exactly like records written
     * before operator tracking.
     *
     * @param machinePrefix the existing machine-readable prefix, never null
     * @param audit the operator audit, or null / unrecorded for legacy callers
     */
    public static String composeReason(String machinePrefix, AdminAudit audit) {
        if (audit == null || !audit.isRecorded()) {
            return machinePrefix;
        }
        return machinePrefix + REASON_SEPARATOR + audit.reason();
    }
}
