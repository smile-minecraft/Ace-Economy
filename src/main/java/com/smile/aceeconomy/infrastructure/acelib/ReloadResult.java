package com.smile.aceeconomy.infrastructure.acelib;

/**
 * Result of a v2 config/lang reload.
 *
 * <p>Both config and lang reloads preserve the last valid in-memory snapshot on
 * failure (AceLib guarantees this for config; the adapter catches lang exceptions
 * so the old {@code LangManager} snapshot is never overwritten). The result is
 * therefore always diagnosable: a {@code false} flag is paired with a
 * human-readable reason, and no half-applied state is exposed.</p>
 */
public final class ReloadResult {

    private final boolean configReloaded;
    private final boolean langReloaded;
    private final String configError;
    private final String langError;
    private final String currencySummary;
    private final java.util.List<String> appliedNotes;
    private final java.util.List<String> restartNotes;

    public ReloadResult(boolean configReloaded, boolean langReloaded, String langError) {
        this(configReloaded, langReloaded, null, langError);
    }

    public ReloadResult(boolean configReloaded, boolean langReloaded, String configError, String langError) {
        this(configReloaded, langReloaded, configError, langError, null,
                java.util.List.of(), java.util.List.of());
    }

    public ReloadResult(boolean configReloaded, boolean langReloaded, String configError, String langError,
                        String currencySummary, java.util.List<String> appliedNotes,
                        java.util.List<String> restartNotes) {
        this.configReloaded = configReloaded;
        this.langReloaded = langReloaded;
        this.configError = configError;
        this.langError = langError;
        this.currencySummary = currencySummary;
        this.appliedNotes = appliedNotes == null ? java.util.List.of() : java.util.List.copyOf(appliedNotes);
        this.restartNotes = restartNotes == null ? java.util.List.of() : java.util.List.copyOf(restartNotes);
    }

    public boolean configReloaded() {
        return configReloaded;
    }

    public boolean langReloaded() {
        return langReloaded;
    }

    public boolean success() {
        return configReloaded && langReloaded;
    }

    public String configError() {
        return configError;
    }

    public String langError() {
        return langError;
    }

    public String diagnostics() {
        StringBuilder sb = new StringBuilder();
        sb.append("config=").append(configReloaded ? "ok" : "failed");
        if (configError != null) {
            sb.append(", configError=").append(configError);
        }
        sb.append(", lang=").append(langReloaded ? "ok" : "failed");
        if (langError != null) {
            sb.append(", langError=").append(langError);
        }
        if (currencySummary != null) {
            sb.append(", currencies=").append(currencySummary);
        }
        for (String note : appliedNotes) {
            sb.append(", applied=").append(note);
        }
        for (String note : restartNotes) {
            sb.append(", restart=").append(note);
        }
        return sb.toString();
    }

    /** One-line currency classification, or {@code null} when currencies were not reviewed. */
    public String currencySummary() {
        return currencySummary;
    }

    /** Changes hot-applied by this reload (display metadata, layout, config/lang). */
    public java.util.List<String> appliedNotes() {
        return appliedNotes;
    }

    /** Changes detected but deferred until restart (alias, storage, structural currencies). */
    public java.util.List<String> restartNotes() {
        return restartNotes;
    }

    /** Human-readable detail for command feedback: reasons on failure, notes on success. */
    public String detail() {
        if (!success()) {
            if (configError != null) {
                return configError;
            }
            if (currencySummary != null) {
                return currencySummary;
            }
            return langError != null ? langError : "reload failed";
        }
        StringBuilder sb = new StringBuilder();
        for (String note : appliedNotes) {
            if (!sb.isEmpty()) {
                sb.append('\n');
            }
            sb.append(note);
        }
        for (String note : restartNotes) {
            if (!sb.isEmpty()) {
                sb.append('\n');
            }
            sb.append(note);
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return "ReloadResult{" + diagnostics() + "}";
    }
}
