package com.smile.aceeconomy.domain;

import java.util.Objects;

/**
 * Single volatile publish point for display-only currency metadata (name / symbol).
 *
 * <p>Every display-rendering collaborator (command adapters, Vault, placeholders) shares one
 * instance. A validated reload publishes the candidate registry exactly once, so concurrent
 * readers always observe one complete registry — the whole old one or the whole new one —
 * never a mixture where one surface already shows the new symbol while another still shows
 * the old one. The registry itself is immutable; publishing is a single reference swap.</p>
 */
public final class CurrencyDisplayHolder {

    private volatile CurrencyRegistry current;

    public CurrencyDisplayHolder(CurrencyRegistry initial) {
        this.current = Objects.requireNonNull(initial, "initial");
    }

    /** Current registry; every read observes one complete publish, never a half-swapped state. */
    public CurrencyRegistry get() {
        return current;
    }

    /**
     * Publish a validated candidate. Infallible by construction: a plain volatile reference
     * write that never throws for a non-null candidate. Callers must validate the candidate
     * as display-only (see {@code CurrencyReloadPlan}) before publishing.
     */
    public void publish(CurrencyRegistry candidate) {
        this.current = Objects.requireNonNull(candidate, "candidate");
    }
}
