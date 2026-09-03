package com.smile.aceeconomy.infrastructure.acelib;

import com.smile.aceeconomy.domain.Currency;
import com.smile.aceeconomy.domain.CurrencyRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Classifies a candidate {@code currencies} section against the live registry so
 * {@code /aceeco reload} can decide between hot-apply and restart-required.
 *
 * <p>Only display metadata ({@code name} / {@code symbol}) is hot-appliable: it never
 * affects stored balances, scales or the default currency, so swapping the immutable
 * registry reference in every display holder is observably equivalent to a full restart
 * for that diff. Every structural change is restart-required instead:</p>
 * <ul>
 *   <li>{@code ADDED} — a new id needs batch initialization of existing accounts with
 *       rollback support, which no persistence backend currently offers; refusing is
 *       safer than leaving sparse maps behind;</li>
 *   <li>{@code DANGEROUS} — removed ids, scale changes and default changes would
 *       reinterpret or orphan stored balances.</li>
 * </ul>
 *
 * <p>The classifier is pure and never throws: an unparsable candidate yields
 * {@code INVALID} with the parser diagnostic.</p>
 */
public final class CurrencyReloadPlan {

    public enum Disposition {
        IDENTICAL,
        DISPLAY_ONLY,
        ADDED,
        DANGEROUS,
        INVALID
    }

    public record Classification(Disposition disposition, CurrencyRegistry candidate, List<String> details) {
        public Classification {
            Objects.requireNonNull(disposition, "disposition");
            details = details == null ? List.of() : List.copyOf(details);
        }

        /** Single-line operator-facing summary; restart-required plans say so explicitly. */
        public String summary() {
            return switch (disposition) {
                case IDENTICAL -> "currencies unchanged";
                case DISPLAY_ONLY -> "currencies display-only change (hot-applied)";
                case ADDED -> "currencies added (restart required: existing accounts need batch initialization)";
                case DANGEROUS -> "currencies structural change (restart required)";
                case INVALID -> "currencies invalid";
            };
        }
    }

    private CurrencyReloadPlan() {
    }

    public static Classification classify(CurrencyRegistry live, Object candidateRaw) {
        Objects.requireNonNull(live, "live");
        final CurrencyRegistry candidate;
        try {
            candidate = CurrencyConfigParser.parse(candidateRaw);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return new Classification(Disposition.INVALID, null,
                    List.of("currencies invalid: " + e.getMessage()));
        }
        List<String> added = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        List<String> displayChanged = new ArrayList<>();
        List<String> scaleChanged = new ArrayList<>();
        for (Currency c : live.all()) {
            if (!candidate.contains(c.id())) {
                removed.add("currency removed: " + c.id()
                        + " (restart and data migration required; existing balances must not be orphaned)");
            }
        }
        for (Currency c : candidate.all()) {
            if (!live.contains(c.id())) {
                added.add("currency added: " + c.id()
                        + " (restart required: existing accounts need batch initialization)");
                continue;
            }
            Currency old = live.get(c.id());
            if (old.scale() != c.scale()) {
                scaleChanged.add("currency scale changed: " + c.id()
                        + " " + old.scale() + " -> " + c.scale() + " (restart required)");
            }
            if (old.isDefault() != c.isDefault()) {
                // The parser guarantees exactly one default, so any flag flip is a default move.
                scaleChanged.add("currency default changed: "
                        + (old.isDefault() ? c.id() + " no longer default" : c.id() + " is now default")
                        + " (restart required)");
            }
            if (!Objects.equals(old.displayName(), c.displayName())
                    || !Objects.equals(old.symbol(), c.symbol())) {
                displayChanged.add("currency display changed: " + c.id());
            }
        }
        if (!removed.isEmpty() || !scaleChanged.isEmpty()) {
            List<String> details = new ArrayList<>(removed);
            details.addAll(scaleChanged);
            details.addAll(added);
            details.addAll(displayChanged);
            return new Classification(Disposition.DANGEROUS, candidate, details);
        }
        if (!added.isEmpty()) {
            List<String> details = new ArrayList<>(added);
            details.addAll(displayChanged);
            return new Classification(Disposition.ADDED, candidate, details);
        }
        if (!displayChanged.isEmpty()) {
            return new Classification(Disposition.DISPLAY_ONLY, candidate, displayChanged);
        }
        if (!live.defaultCurrencyId().equals(candidate.defaultCurrencyId())) {
            return new Classification(Disposition.DANGEROUS, candidate, List.of(
                    "currency default changed: " + live.defaultCurrencyId()
                            + " -> " + candidate.defaultCurrencyId() + " (restart required)"));
        }
        return new Classification(Disposition.IDENTICAL, candidate, List.of());
    }

    /**
     * Defensive guard for runtime display swaps: every holder calls this before replacing
     * its registry reference so a programming error can never hot-apply a structural change.
     *
     * @throws IllegalArgumentException when ids, scales or the default currency differ
     */
    public static void requireDisplayOnlyChange(CurrencyRegistry live, CurrencyRegistry candidate) {
        Objects.requireNonNull(live, "live");
        Objects.requireNonNull(candidate, "candidate");
        Classification plan = classify(live, toRaw(candidate));
        if (plan.disposition() != Disposition.IDENTICAL
                && plan.disposition() != Disposition.DISPLAY_ONLY) {
            throw new IllegalArgumentException(
                    "refusing non-display currency swap: " + plan.summary()
                            + " :: " + String.join("; ", plan.details()));
        }
    }

    private static Object toRaw(CurrencyRegistry registry) {
        java.util.Map<String, Object> raw = new java.util.LinkedHashMap<>();
        for (Currency c : registry.all()) {
            java.util.Map<String, Object> fields = new java.util.LinkedHashMap<>();
            fields.put("name", c.displayName());
            fields.put("symbol", c.symbol());
            fields.put("scale", c.scale());
            fields.put("default", c.isDefault());
            raw.put(c.id(), fields);
        }
        return raw;
    }
}
