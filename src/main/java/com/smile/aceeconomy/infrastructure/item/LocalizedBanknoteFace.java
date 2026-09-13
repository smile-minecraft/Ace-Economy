package com.smile.aceeconomy.infrastructure.item;

import com.smile.aceeconomy.infrastructure.acelib.ConfigLangAdapter;
import com.smile.aceeconomy.ports.BanknoteClaim;

import net.kyori.adventure.text.Component;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Renders the localized face (display name and lore) of a v2 banknote from the registered
 * {@code banknote.*} lang keys, so a minted note is recognizable by its actual value and currency
 * in every supported locale. All claim data travels through the safe message pipeline
 * ({@link ConfigLangAdapter#renderMessage}), where variable values are escaped before MiniMessage
 * parsing — player-adjacent content is never parsed as markup.
 *
 * <p>The {@code {currency}} var carries the operator-configured display name resolved through
 * {@link CurrencyNames}, not the raw currency id — the face must read like the rest of the
 * economy UI. An unresolvable or failing resolver degrades to the raw id (redemption keys off
 * the id, so the note stays valid and decodable in every case).
 *
 * <p>Fallback contract: a renderer failure degrades to the legacy plain face
 * ({@code "Banknote <value>"} with no lore) instead of propagating, because minting happens after
 * the balance has already left the account — a display outage must never turn into a withdrawal
 * failure. The production renderer does not throw by contract, so the fallback only guards the
 * seam itself.
 */
public final class LocalizedBanknoteFace {

    /** Renders one lang key with vars. Production wraps {@link ConfigLangAdapter#renderMessage}. */
    public interface Renderer {
        @NotNull Component render(@NotNull String key, @NotNull Map<String, Object> vars);
    }

    /**
     * Resolves the operator-configured display name for a claim currency id. Returns {@code null}
     * when the id is unknown; implementations must not throw for unknown ids, but any failure is
     * tolerated by the face renderer, which falls back to the raw id.
     */
    public interface CurrencyNames {
        @Nullable String name(@NotNull String currencyId);
    }

    private final Renderer renderer;
    private final CurrencyNames currencyNames;

    public LocalizedBanknoteFace(@NotNull Renderer renderer) {
        this(renderer, null);
    }

    public LocalizedBanknoteFace(@NotNull Renderer renderer, @Nullable CurrencyNames currencyNames) {
        this.renderer = Objects.requireNonNull(renderer, "renderer");
        this.currencyNames = currencyNames;
    }

    /** Production face bound to the live message adapter and the shared display registry. */
    public static @NotNull LocalizedBanknoteFace production(@NotNull ConfigLangAdapter messages,
                                                            @NotNull CurrencyNames currencyNames) {
        Objects.requireNonNull(messages, "messages");
        Objects.requireNonNull(currencyNames, "currencyNames");
        return new LocalizedBanknoteFace(messages::renderMessage, currencyNames);
    }

    /** Localized display name carrying the note value, currency and issuer. */
    public @NotNull Component displayName(@NotNull BanknoteClaim claim) {
        Component rendered = renderSafe("banknote.name", faceVars(claim));
        if (rendered != null) {
            return rendered;
        }
        return Component.text("Banknote " + claim.value());
    }

    /** Localized lore: value line, issuer line, redeem hint line (in that order). */
    public @NotNull List<Component> lore(@NotNull BanknoteClaim claim) {
        Component value = renderSafe("banknote.lore-value", faceVars(claim));
        Component issuer = renderSafe("banknote.lore-issuer", Map.of("issuer", claim.issuer().toString()));
        Component click = renderSafe("banknote.lore-click", Map.of());
        if (value != null && issuer != null && click != null) {
            return List.of(value, issuer, click);
        }
        return List.of();
    }

    private Map<String, Object> faceVars(BanknoteClaim claim) {
        return Map.of(
                "value", Long.toString(claim.value()),
                "currency", currencyName(claim.currency()),
                "issuer", claim.issuer().toString());
    }

    /**
     * The human-readable name shown for a currency: the display name configured for the claim's
     * currency id, falling back to the raw id when no resolver is bound (legacy constructor), the
     * id is unknown, or the resolver itself fails. The raw id is always a safe, meaningful fallback
     * because redemption keys off the id, never off the rendered face.
     */
    private String currencyName(String currencyId) {
        if (currencyNames == null) {
            return currencyId;
        }
        try {
            String name = currencyNames.name(currencyId);
            return name == null || name.isBlank() ? currencyId : name;
        } catch (Throwable t) {
            return currencyId;
        }
    }

    private Component renderSafe(String key, Map<String, Object> vars) {
        try {
            return renderer.render(key, vars);
        } catch (Throwable t) {
            return null;
        }
    }
}
