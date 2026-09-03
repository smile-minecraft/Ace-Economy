package com.smile.aceeconomy.infrastructure.acelib;

import com.smile.aceeconomy.domain.CurrencyRegistry;

import java.util.List;

/**
 * Production runtime side of the reload transaction, implemented by the composition
 * root. The {@link ConfigLangAdapter} owns the candidate validation and the atomic
 * config/lang swap; this hook supplies the live currency registry, inspects the
 * classified candidate, and hot-applies approved display changes.
 *
 * <p>All calls happen inside the adapter reload lock, so the inspection sees a stable live
 * snapshot and the apply lands in the same atomic window as the config/lang swap.
 * {@code applyApproved} must validate before publishing and then perform only infallible
 * writes: throwing after a publish aborts the config/lang swap while display readers
 * already agree on the new display-only registry by construction (one shared publish
 * point, so no mixed versions are observable).</p>
 */
public interface ReloadRuntime {

    /** Live registry the candidate is classified against. */
    CurrencyRegistry liveCurrencies();

    /**
     * Inspect the classified candidate.
     *
     * @return an empty list to approve; one reason per line to reject the whole reload
     */
    List<String> reviewCurrencyCandidate(CurrencyReloadPlan.Classification classification);

    /**
     * Hot-apply an approved candidate (display-only) plus the validated GUI layout,
     * then invalidate open GUI sessions. Only called after an empty inspection.
     */
    void applyApproved(CurrencyReloadPlan.Classification classification, BankGuiLayout layout);
}
