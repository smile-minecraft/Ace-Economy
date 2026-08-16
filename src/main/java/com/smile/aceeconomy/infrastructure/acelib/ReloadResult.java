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
    private final String langError;

    public ReloadResult(boolean configReloaded, boolean langReloaded, String langError) {
        this.configReloaded = configReloaded;
        this.langReloaded = langReloaded;
        this.langError = langError;
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

    public String langError() {
        return langError;
    }

    public String diagnostics() {
        StringBuilder sb = new StringBuilder();
        sb.append("config=").append(configReloaded ? "ok" : "failed");
        sb.append(", lang=").append(langReloaded ? "ok" : "failed");
        if (langError != null) {
            sb.append(", langError=").append(langError);
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return "ReloadResult{" + diagnostics() + "}";
    }
}
