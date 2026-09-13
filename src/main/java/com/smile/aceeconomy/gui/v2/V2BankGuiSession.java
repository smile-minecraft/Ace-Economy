package com.smile.aceeconomy.gui.v2;

import com.smile.acelib.gui.GuiArgument;
import com.smile.acelib.gui.GuiPage;
import com.smile.acelib.gui.GuiResult;
import com.smile.acelib.gui.GuiService;
import com.smile.acelib.gui.GuiSession;
import com.smile.aceeconomy.ports.BankGuiUseCase;
import com.smile.aceeconomy.ports.DepositResult;
import com.smile.aceeconomy.ports.FoliaContextExecutor;
import com.smile.aceeconomy.ports.WithdrawResult;

import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * v2 bank GUI session controller. It owns the GUI lifecycle through AceLib's {@link GuiService}
 * generation/session contract and dispatches every player/inventory mutation through the
 * {@link FoliaContextExecutor} so nothing touches Bukkit from an arbitrary async thread.
 *
 * <p>Stale protection is delegated to {@link GuiService}: a click or async update carrying a
 * generation that no longer matches the active session is rejected before any business action runs.
 * The actual withdraw/deposit business logic is reached only through the {@link BankGuiUseCase}
 * port, never directly.
 *
 * <p>Wired by the production composition root at server start; this class is constructed with its
 * collaborators so it is fully unit-testable with fakes.
 */
public final class V2BankGuiSession {

    private final GuiService guiService;
    private final FoliaContextExecutor folia;
    private final BankGuiUseCase useCase;
    private volatile Function<Integer, BankGuiAction> actionResolver;
    // Layout generation: bumped once per resolver swap. An open binds the
    // generation that was current when its layout was read; a mismatch means a
    // reload landed in between, so the open must not build a session from the
    // stale layout. Monotonic, never reused for a different layout.
    private final AtomicLong layoutGeneration = new AtomicLong(0);

    private final Map<UUID, Player> players = new ConcurrentHashMap<>();
    private final Map<UUID, GuiSession> sessions = new ConcurrentHashMap<>();
    // Layout generation each locally tracked session was opened under, bound to
    // the session it was written for. A reload bumps layoutGeneration, so a tag
    // newer than the pre-reload value marks a session built from the failed
    // candidate; rollback drops exactly those. The owner binding matters because
    // an open publishes sessions before the tag: a rollback reading between the
    // two still sees the previous tag while sessions already holds the new
    // session, and trusting the generation alone would let the candidate live on.
    private final Map<UUID, SessionTag> sessionLayoutGenerations = new ConcurrentHashMap<>();
    // Generation-bound view identity: the actual top inventory object the
    // player-region render painted into, stapled to the backend session
    // generation it was painted for. The consumer listener only treats an event
    // as a bank event when the event's top inventory is this exact object, so a
    // same-title/same-size inventory from another plugin can never dispatch.
    // Bound inside the AceLib async-update renderer (which re-validates the
    // session, the request and the open-inventory link first), never at open
    // time, when the shell is not open yet and the calling thread may be off
    // the player region.
    private final Map<UUID, ViewTag> viewTags = new ConcurrentHashMap<>();
    // Failed-view guard: the exact top inventory object a render painted into
    // before failing, stapled to the backend session generation it was painted
    // for. A partial/empty shell stays open on the player side, so the
    // consumer listener cancels every top click, shift-click into it and drag
    // touching it until the view closes — never dispatching bank business.
    // Bound when the player-region paint reports incomplete (or throws), never
    // at open time; identity ({@code ==}) plus generation is the ownership
    // proof, so a same-title/same-size foreign inventory is never matched.
    private final Map<UUID, ViewTag> failedViews = new ConcurrentHashMap<>();
    // Pending-view guard: the exact top inventory captured on the player region
    // while an accepted async render is still queued, stapled to the backend
    // session generation it was captured for. The consumer listener cancels
    // every top click, shift-click into it and drag touching it until the
    // paint converts it into a bound view (or a failed guard), never
    // dispatching bank business. Bound through the player-region capture
    // below and refreshed at renderer start, never at open time; identity
    // ({@code ==}) plus generation is the ownership proof, so a
    // same-title/same-size foreign inventory is never matched.
    private final Map<UUID, ViewTag> pendingViews = new ConcurrentHashMap<>();
    // Pending-render intent: a generation with an accepted render whose
    // player-region capture has not run yet (or whose paint is still
    // queued). Published synchronously on the calling thread before the
    // chained region task is dispatched, so the consumer listener can
    // fail-closed the shell even before the exact pending guard exists.
    // Honoured only together with the active backend generation plus the
    // existing size/title sanity, and cleared when the paint converts to
    // bound/failed or when the generation closes/is invalidated. Never read
    // from Bukkit; never dispatched.
    private final Map<UUID, Long> pendingIntents = new ConcurrentHashMap<>();
    // Per-player publish fence: the three bookkeeping puts of an open and the
    // per-key removals of a rollback/invalidation for the same player meet
    // here. Fixed stripes bound memory under UUID churn: the same player
    // always maps to the same stripe, so same-key mutual exclusion holds;
    // different keys sharing a stripe only wait on a map-only critical
    // section, never on the slow inventory open.
    static final int GUARD_STRIPES = 64;
    private final Object[] keyGuards;

    {
        Object[] guards = new Object[GUARD_STRIPES];
        for (int i = 0; i < guards.length; i++) {
            guards[i] = new Object();
        }
        keyGuards = guards;
    }

    private Object guardFor(UUID playerUuid) {
        return keyGuards[Math.floorMod(playerUuid.hashCode(), GUARD_STRIPES)];
    }

    /**
     * Layout generation a session was opened under, stapled to the session the
     * tag was written for. Rollback only trusts a tag whose owner is still the
     * current session; a tag owned by anyone else is stale bookkeeping from an
     * open that has since been replaced, so the current entry is suspect.
     */
    static final record SessionTag(long layoutGeneration, GuiSession owner) {
        SessionTag {
            Objects.requireNonNull(owner, "owner");
        }
    }

    /**
     * The top inventory object a backend session generation was painted into.
     * Identity ({@code ==}) is the ownership proof the consumer listener checks;
     * the generation staples the tag to one backend session so a stale close can
     * never clean up a newer reopen that rebound the tag afterwards.
     */
    static final record ViewTag(long generation, Inventory top) {
        ViewTag {
            Objects.requireNonNull(top, "top");
        }
    }

    public V2BankGuiSession(@NotNull GuiService guiService,
                            @NotNull FoliaContextExecutor folia,
                            @NotNull BankGuiUseCase useCase,
                            @NotNull Function<Integer, BankGuiAction> actionResolver) {
        this.guiService = Objects.requireNonNull(guiService, "guiService");
        this.folia = Objects.requireNonNull(folia, "folio");
        this.useCase = Objects.requireNonNull(useCase, "useCase");
        this.actionResolver = Objects.requireNonNull(actionResolver, "actionResolver");
    }

    public @NotNull OpenOutcome open(@NotNull Player player, @NotNull String title, int size,
                                      @NotNull Set<Integer> protectedSlots) {
        return open(player, title, size, protectedSlots, layoutGeneration.get());
    }

    /**
     * Generation-bound open. The caller passes the layout generation that was
     * current when it read the layout: a mismatch on entry means a reload
     * already swapped the layout, so no session is built. A swap that lands
     * while {@code openInventory} runs is caught by the post-check, which drops
     * the just-created stale session instead of leaving it behind for the
     * reload invalidation to miss. Callers that get a {@code stale-layout}
     * failure should re-read the current layout and retry once.
     */
    public @NotNull OpenOutcome open(@NotNull Player player, @NotNull String title, int size,
                                      @NotNull Set<Integer> protectedSlots,
                                      long expectedLayoutGeneration) {
        if (expectedLayoutGeneration != layoutGeneration.get()) {
            return OpenOutcome.failed("stale-layout", "bank layout changed before open");
        }
        GuiArgument arg = GuiArgument.of(player, title, size, protectedSlots);
        GuiResult result = guiService.openInventory(arg);
        if (!result.isSuccess() || result.session() == null) {
            return OpenOutcome.failed(result.errorCode(), result.detail());
        }
        UUID uuid = player.getUniqueId();
        GuiSession owned = result.session();
        synchronized (guardFor(uuid)) {
            if (layoutGeneration.get() != expectedLayoutGeneration) {
                try {
                    guiService.closeInventory(uuid, owned.generation());
                } catch (Throwable ignored) {
                    // Local bookkeeping below is still cleared; without it a later
                    // click would resolve against the new layout with an old session.
                } finally {
                    dropStaleBookkeeping(uuid, owned);
                }
                return OpenOutcome.failed("stale-layout", "bank layout changed during open");
            }
            // Dependents last: a stale cleanup landing between these puts must
            // already see the new session so it keeps the whole entry.
            SessionTag ownTag = new SessionTag(expectedLayoutGeneration, owned);
            sessions.put(uuid, owned);
            sessionLayoutGenerations.put(uuid, ownTag);
            players.put(uuid, player);
            if (layoutGeneration.get() != expectedLayoutGeneration) {
                // The publish landed after a rollback or reload snapshot: the
                // snapshot missed this entry, so withdraw only our own puts and
                // report stale-layout for a retry on the current layout. The
                // conditional removes never touch a newer retry.
                try {
                    guiService.closeInventory(uuid, owned.generation());
                } catch (Throwable ignored) {
                    // Local state below is still cleared.
                }
                sessions.remove(uuid, owned);
                sessionLayoutGenerations.remove(uuid, ownTag);
                if (!sessions.containsKey(uuid)) {
                    players.remove(uuid);
                }
                return OpenOutcome.failed("stale-layout", "bank layout changed during open");
            }
            if (dropOrphanAfterLostRace(uuid, owned)) {
                // A rollback slipped between the puts above and removed the new
                // session; the players put just re-added an entry no session backs.
                // Report stale-layout so the caller retries on the restored layout.
                return OpenOutcome.failed("stale-layout", "bank layout rolled back during open");
            }
            return OpenOutcome.success(owned);
        }
    }

    /**
     * Best-effort repair for an open that lost a rollback race: when the owned
     * session is gone and no session remains, drop the orphan tag/players puts
     * without touching a newer retry that may have rebuilt since. Returns true
     * when such an orphan was cleaned. Never throws.
     */
    boolean dropOrphanAfterLostRace(UUID playerUuid, GuiSession ownedSession) {
        try {
            if (ownedSession == null || sessions.get(playerUuid) == ownedSession
                    || sessions.containsKey(playerUuid)) {
                return false;
            }
            sessionLayoutGenerations.computeIfPresent(playerUuid,
                    (key, tag) -> tag.owner() == ownedSession ? null : tag);
            players.computeIfPresent(playerUuid,
                    (key, existing) -> sessions.containsKey(key) ? existing : null);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Drop local bookkeeping for a stale open without touching a newer retry.
     * A retry that already rebuilt a session carries a larger GuiService
     * generation, so only entries at or below the stale generation are
     * dropped; anything newer (same player, rebuilt after the swap) is kept,
     * and the player entry is kept with it so the next click still dispatches.
     */
    private void dropStaleBookkeeping(UUID playerUuid, GuiSession staleSession) {
        sessions.computeIfPresent(playerUuid, (key, current) -> {
            if (current == staleSession) {
                return null;
            }
            if (staleSession != null && current.generation() <= staleSession.generation()) {
                return null;
            }
            return current;
        });
        if (staleSession != null) {
            long staleGeneration = staleSession.generation();
            viewTags.computeIfPresent(playerUuid, (key, tag) ->
                    tag.generation() <= staleGeneration ? null : tag);
            failedViews.computeIfPresent(playerUuid, (key, tag) ->
                    tag.generation() <= staleGeneration ? null : tag);
            pendingViews.computeIfPresent(playerUuid, (key, tag) ->
                    tag.generation() <= staleGeneration ? null : tag);
            pendingIntents.computeIfPresent(playerUuid, (key, intent) ->
                    intent <= staleGeneration ? null : intent);
        }
        if (!sessions.containsKey(playerUuid)) {
            sessionLayoutGenerations.remove(playerUuid);
            players.remove(playerUuid);
        }
    }

    /**
     * Handle a click on {@code slot} for the given player/generation. The generation is validated by
     * {@link GuiService} first; a stale generation is rejected without touching the business layer.
     * Withdraw and deposit dispatch their inventory mutations through the player's region thread via
     * {@link FoliaContextExecutor}; close goes through the generation-aware {@link GuiService} close
     * path, which owns its own thread safety.
     *
     * <p>This synchronous wrapper never blocks on the Folia region thread. If the executor defers the
     * runnable, it returns a typed {@link ClickOutcome#dispatched()} instead of a fake success; callers
     * that need the final result should use {@link #handleClickAsync(UUID, long, int)}.
     */
    public @NotNull ClickOutcome handleClick(@NotNull UUID playerUuid, long generation, int slot) {
        CompletableFuture<ClickOutcome> future = handleClickAsync(playerUuid, generation, slot);
        if (future.isDone()) {
            try {
                return future.join();
            } catch (Exception e) {
                return ClickOutcome.rejected("internal.error");
            }
        }
        return ClickOutcome.dispatched();
    }

    /**
     * Async variant that completes on the player's region thread. Every Bukkit inventory mutation
     * happens inside the Folia context; the returned future completes with the typed outcome once the
     * dispatch has run. Generation validation still happens on the calling thread before any dispatch.
     * <p>
     * This overload reads the held item at execution time inside the Folia callback, so it is safe
     * to call from any thread but does not provide click-time binding. For click-time binding use
     * {@link #handleClickAsync(UUID, long, int, ItemStack)} where the caller supplies the item
     * from a legal player/Folia click context and the method clones it immediately.
     */
    public @NotNull CompletableFuture<ClickOutcome> handleClickAsync(@NotNull UUID playerUuid,
                                                                     long generation, int slot) {
        return handleClickAsyncInternal(playerUuid, generation, slot, null, false);
    }

    /**
     * Click-time binding variant. The caller must be in a legal player/Folia click callback and
     * supplies {@code heldItemAtClick} (the item that was in the main hand when the click occurred).
     * The method immediately clones/copies the item into a private immutable snapshot so a later
     * mutation of the original reference cannot affect the credited item, and a deferred swap to a
     * different item yields {@code item.mismatch} without removing the new item.
     * <p>
     * If the snapshot cannot be copied safely (clone returns {@code null}, same reference, or
     * throws), the future completes immediately with {@code item.snapshot-failed} and no
     * business layer call is made.
     */
     public @NotNull CompletableFuture<ClickOutcome> handleClickAsync(@NotNull UUID playerUuid,
                                                                     long generation, int slot,
                                                                     ItemStack heldItemAtClick) {
        if (heldItemAtClick != null) {
            ItemStack snapshot;
            try {
                ItemStack cloned = heldItemAtClick.clone();
                if (cloned == null || cloned == heldItemAtClick) {
                    return CompletableFuture.completedFuture(ClickOutcome.snapshotFailed());
                }
                snapshot = cloned;
            } catch (Throwable t) {
                return CompletableFuture.completedFuture(ClickOutcome.snapshotFailed());
            }
            return handleClickAsyncInternal(playerUuid, generation, slot, snapshot, true);
        }
        // heldItemAtClick == null represents no item at click time; preserve as null snapshot
        // with hasSnapshot true so the callback returns deposit.no-item without reading current.
        return handleClickAsyncInternal(playerUuid, generation, slot, null, true);
    }

    private @NotNull CompletableFuture<ClickOutcome> handleClickAsyncInternal(@NotNull UUID playerUuid,
                                                                              long generation, int slot,
                                                                              ItemStack clickTimeSnapshot,
                                                                              boolean hasSnapshot) {
        GuiResult validation = guiService.validateClick(playerUuid, generation, slot);
        if (!validation.isAllowed()) {
            return CompletableFuture.completedFuture(ClickOutcome.rejected(validation.errorCode()));
        }
        BankGuiAction action = actionResolver.apply(slot);
        if (action == null || action.type() == BankGuiAction.Type.NONE) {
            return CompletableFuture.completedFuture(ClickOutcome.allowed());
        }
        if (action.type() == BankGuiAction.Type.CLOSE) {
            CloseOutcome closed = close(playerUuid, generation);
            ClickOutcome outcome = closed.isClosed()
                    ? ClickOutcome.allowed()
                    : ClickOutcome.rejected(closed.errorCode());
            return CompletableFuture.completedFuture(outcome);
        }
        Player player = players.get(playerUuid);
        if (player == null) {
            return CompletableFuture.completedFuture(ClickOutcome.rejected("no-player"));
        }
        CompletableFuture<ClickOutcome> future = new CompletableFuture<>();
        try {
            folia.runForPlayer(player, () -> {
                try {
                    // Re-validate inside the Folia context: the dispatch may have waited while
                    // a reload invalidated the session, so the pre-dispatch check alone is
                    // stale. A generation that no longer matches is discarded without action.
                    GuiResult recheck = guiService.validateClick(playerUuid, generation, slot);
                    if (!recheck.isAllowed()) {
                        future.complete(ClickOutcome.rejected(recheck.errorCode()));
                        return;
                    }
                    ClickOutcome outcome;
                    switch (action.type()) {
                        case WITHDRAW -> outcome = runWithdraw(player, playerUuid, action);
                        case DEPOSIT -> {
                            if (hasSnapshot) {
                                if (clickTimeSnapshot == null) {
                                    outcome = ClickOutcome.rejected("deposit.no-item");
                                } else {
                                    outcome = depositHeldBanknote(player, playerUuid, clickTimeSnapshot);
                                }
                            } else {
                                // Execution-time read inside player context (old overload)
                                outcome = depositHeldBanknote(player, playerUuid, null);
                            }
                        }
                        default -> outcome = ClickOutcome.allowed();
                    }
                    future.complete(outcome);
                } catch (Throwable t) {
                    future.complete(ClickOutcome.rejected("internal.error"));
                }
            });
        } catch (Throwable t) {
            future.complete(ClickOutcome.rejected("dispatch.failed"));
        }
        return future;
    }

    private static ItemStack copyItemSnapshot(ItemStack original) {
        if (original == null) {
            return null;
        }
        try {
            ItemStack cloned = original.clone();
            if (cloned == null || cloned == original) {
                return null;
            }
            return cloned;
        } catch (Throwable t) {
            return null;
        }
    }

    private ClickOutcome runWithdraw(Player player, UUID playerUuid, BankGuiAction action) {
        if (player.getInventory().firstEmpty() == -1) {
            return ClickOutcome.inventoryFull();
        }
        WithdrawResult r = useCase.withdraw(playerUuid, action.amount(), action.currencyId());
        if (r.success()) {
            player.getInventory().addItem(r.banknote());
            return ClickOutcome.success(r.banknote());
        } else if (r.isInventoryFull()) {
            return ClickOutcome.inventoryFull();
        } else {
            return ClickOutcome.rejected(r.reason());
        }
    }

    /**
     * Redeem the banknote in the player's main hand. The item contract is deliberately the held
     * item: decode and credit happen against exactly what the player is holding when the deposit
     * button is clicked. The item is only touched after the business layer reports a committed
     * credit; every rejection keeps it in hand.
     * <p>
     * The expected item is captured at click time (before the Folia dispatch is queued) so a
     * deferred swap from A→B cannot credit A while removing B. After a successful credit we
     * check that the current hand still matches the expected item (reference or {@code isSimilar}
     * content) before mutating it; a mismatch leaves the current item untouched and returns a
     * typed {@code item.mismatch} retained outcome.
     */
    private ClickOutcome depositHeldBanknote(Player player, UUID playerUuid, ItemStack expected) {
        ItemStack held = expected;
        // Fallback if expected was not captured (e.g. direct call): read current hand
        if (held == null) {
            held = player.getInventory().getItemInMainHand();
        }
        if (held == null || held.getType().isAir() || held.getAmount() <= 0) {
            return ClickOutcome.rejected("deposit.no-item");
        }
        DepositResult result = useCase.deposit(playerUuid, held);
        if (!result.success()) {
            return ClickOutcome.rejected(result.reason());
        }
        return removeOneMatchingFromMainHand(player, expected);
    }

    // Legacy entry kept for direct calls; delegates to the snapshot-aware variant
    @SuppressWarnings("unused")
    private ClickOutcome depositHeldBanknote(Player player, UUID playerUuid) {
        return depositHeldBanknote(player, playerUuid, null);
    }

    /**
     * Remove exactly one item from the main hand after a committed credit, but only if the
     * current hand still matches the item that was credited. If the hand no longer holds the
     * note at this point, the durable nonce protection already makes the leftover
     * unredeemable, but the outcome must stay distinguishable from a clean success.
     * A swapped item (different reference and not {@code isSimilar}) is left untouched and
     * yields {@code item.mismatch}.
     */
    private ClickOutcome removeOneMatchingFromMainHand(Player player, ItemStack expected) {
        ItemStack current = player.getInventory().getItemInMainHand();
        if (current == null || current.getType().isAir() || current.getAmount() <= 0) {
            return ClickOutcome.creditRetained();
        }
        // Check the current hand still matches the credited item.
        boolean matches;
        if (expected != null && current == expected) {
            matches = true;
        } else if (expected != null) {
            boolean similar;
            try {
                similar = current.isSimilar(expected);
            } catch (Throwable t) {
                similar = false;
            }
            // Same reference already handled; otherwise require isSimilar. For mocked
            // ItemStacks isSimilar defaults to false, so a different reference with
            // different mock identity correctly yields mismatch. Same content (isSimilar true)
            // is still considered the same logical item and is safe to decrement.
            matches = similar;
        } else {
            // No expected snapshot (legacy path): assume current is the credited item
            matches = true;
        }
        if (!matches) {
            return ClickOutcome.itemMismatch();
        }
        // Safe to consume one from the current hand (which matches expected)
        if (current.getAmount() <= 1) {
            player.getInventory().setItemInMainHand(null);
        } else {
            current.setAmount(current.getAmount() - 1);
            player.getInventory().setItemInMainHand(current);
        }
        return ClickOutcome.credited();
    }

    private ClickOutcome removeOneFromMainHand(Player player) {
        return removeOneMatchingFromMainHand(player, null);
    }

    /**
     * Begin and apply an async page refresh. The generation is checked on both {@code begin} and
     * {@code apply}; a stale generation yields a rejected outcome and the runnable is never applied.
     */
    public @NotNull RefreshOutcome refresh(@NotNull UUID playerUuid, long generation, int pageIndex) {
        GuiResult begin = guiService.beginAsyncUpdate(playerUuid, generation, pageIndex);
        if (!begin.isSuccess() || begin.asyncRequest() == null) {
            return RefreshOutcome.rejected(begin.errorCode());
        }
        GuiPage<ItemStack> page = GuiPage.loading();
        GuiResult apply = guiService.applyAsyncUpdate(begin.asyncRequest(), page, () -> { });
        if (!apply.isSuccess()) {
            return RefreshOutcome.rejected(apply.errorCode());
        }
        return RefreshOutcome.success();
    }

    /**
     * Resolve the configured action for a top-inventory slot without touching the business
     * layer. The consumer click listener uses this to decide whether a cancelled bank click
     * carries an action worth dispatching; the dispatch itself re-resolves inside
     * {@link #handleClickAsync} on the authoritative path, so a layout swap in between can
     * only turn an action into a no-op, never into a wrong action.
     */
    public @NotNull BankGuiAction actionForSlot(int slot) {
        BankGuiAction action = actionResolver.apply(slot);
        return action == null ? BankGuiAction.none() : action;
    }

    /**
     * Render the configured button items into the player's open bank view through the
     * generation-bound async-update path. Item creation and inventory writes happen inside
     * the AceLib renderer, which runs on the player's region thread, so the caller's thread
     * never touches Bukkit. A stale generation rejects before the renderer runs.
     *
      * <p>A render only counts as success when every planned action button was written:
      * the generation-bound view tag is created after the complete paint, never before.
      * A partial paint, a renderer exception, or a missing/mismatched view reports
      * {@code render.failed} (synchronously when the backend runs the renderer inline)
      * without binding an operable tag; instead the exact failed shell is bound as a
      * failed view, so the consumer listener cancels every top click, shift-click
      * into it and drag touching it until the view closes. The backend session is
      * left for the normal close / reload path; a deferred async paint that later
      * fails binds its failed guard the same way even though this call already returned.
      *
      * <p>An accepted render guards the shell in two stages. First, a
      * pending-render intent for this generation is published synchronously
      * before anything is dispatched: until the paint converts it into a
      * bound view (or a failed guard), the consumer listener cancels every
      * top click, shift-click into the shell and drag touching it for the
      * active generation that still passes the size/title sanity — never
      * dispatching bank business. This closes the window where the capture
      * has not run yet and no exact guard exists.
      *
      * <p>Second, one chained player-region task runs the exact capture and
      * then the apply, in that order, on the region thread (via the
      * {@link FoliaContextExecutor} seam, never a raw Bukkit read): the
      * capture binds the exact open top inventory as the pending view for
      * this generation, and only afterwards is the paint queued. The paint
      * itself refreshes the same guard from its link-verified top before
      * writing. A newer reopen (larger generation, rebound tag) is never
      * shadowed.
      *
      * <p>A rejected capture dispatch, a thrown apply, or a rejected apply
      * never clears the guards to fail-open: the call reports
      * {@code render.failed} (or the apply error) while the intent — and any
      * exact pending guard the capture already bound — stays until the paint
      * converts it, or until close/reload/a newer reopen cleans it up.
      *
      * <p>Narrow trade-off while only the intent (not yet the exact guard)
      * is active: a same-title/same-size inventory from another plugin is a
      * different object the intent cannot tell apart, so its risky clicks
      * are cancelled too until the capture pins the exact shell. Plain
      * bottom-inventory clicks still pass through; live-server ordering of
      * this window needs a human Folia/client check.
      *
      * @param renderer item construction seam; production callers use
      *                 {@link BankGuiRenderer#production()}
      */
    public @NotNull RefreshOutcome renderLayout(@NotNull UUID playerUuid, long generation,
                                                @NotNull com.smile.aceeconomy.infrastructure.acelib.BankGuiLayout layout,
                                                @NotNull com.smile.aceeconomy.infrastructure.acelib.ConfigLangAdapter messages,
                                                @NotNull BankGuiRenderer renderer) {
        Objects.requireNonNull(layout, "layout");
        Objects.requireNonNull(messages, "messages");
        Objects.requireNonNull(renderer, "renderer");
        GuiResult begin;
        try {
            begin = guiService.beginAsyncUpdate(playerUuid, generation, 0);
        } catch (Throwable t) {
            // The async update never began, so no paint may enqueue. Publish the
            // memory-only intent only when this render still owns the active
            // backend generation, so the freshly opened shell stays fail-closed
            // until close or a newer reopen.
            publishBeginFailureIntent(playerUuid, generation);
            return RefreshOutcome.rejected("render.failed");
        }
        if (begin == null || !begin.isSuccess() || begin.asyncRequest() == null) {
            // Same fail-closed publish: the shell is already open on the player
            // side while no exact guard exists yet, so the consumer listener
            // must keep cancelling dangerous clicks and drags for it.
            publishBeginFailureIntent(playerUuid, generation);
            String errorCode;
            try {
                errorCode = begin == null ? "render.failed" : begin.errorCode();
            } catch (Throwable t) {
                errorCode = "render.failed";
            }
            return RefreshOutcome.rejected(errorCode);
        }
        Player player;
        try {
            player = players.get(playerUuid);
        } catch (Throwable t) {
            return RefreshOutcome.rejected("render.failed");
        }
        if (player == null) {
            return RefreshOutcome.rejected("render.failed");
        }
        // Fail-closed first: publish the intent before dispatching anything,
        // so the shell is guarded even while the region capture is queued.
        publishPendingIntent(playerUuid, generation);
        // Single content page; the actual inventory writes happen in the renderer below,
        // which AceLib runs on the player's region thread only after re-validating the
        // session, the request and the open inventory binding.
        GuiPage<ItemStack> page = GuiPage.content(0, 1, List.of());
        java.util.concurrent.atomic.AtomicReference<Boolean> painted =
                new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<Throwable> paintFailure =
                new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<GuiResult> applyResult =
                new java.util.concurrent.atomic.AtomicReference<>();
        try {
            // One chained region task: capture first, then apply. The apply is
            // never enqueued before the capture ran, so no unguarded window
            // remains between them. Every Bukkit read stays inside this task.
            folia.runForPlayer(player, () -> {
                capturePendingViewOnRegion(playerUuid, generation, layout);
                GuiResult apply;
                try {
                    apply = guiService.applyAsyncUpdate(begin.asyncRequest(), page,
                            () -> {
                                try {
                                    boolean ok = paintLayout(playerUuid, generation, layout, messages, renderer);
                                    painted.set(ok);
                                    if (!ok) {
                                        failIncompleteRender(playerUuid, generation, currentTop(playerUuid));
                                    }
                                } catch (Throwable t) {
                                    paintFailure.set(t);
                                    failIncompleteRender(playerUuid, generation, currentTop(playerUuid));
                                    throw t;
                                }
                            });
                } catch (Throwable t) {
                    // The paint never queued: keep the intent (and any exact
                    // guard the capture already bound) fail-closed instead of
                    // clearing the only safe state. The outcome below reports
                    // the failure; close/reload/newer reopen converges it.
                    if (paintFailure.get() == null) {
                        paintFailure.set(t);
                    }
                    return;
                }
                applyResult.set(apply);
                // A rejected apply likewise keeps the guards: the shell is
                // still open on this generation until close/reload/newer.
            });
        } catch (Throwable t) {
            // The region dispatch itself was rejected: the chained task never
            // ran, so the intent published above stays fail-closed until
            // close/reload/newer reopen. Report the failure, never success.
            if (paintFailure.get() != null) {
                return RefreshOutcome.rejected("render.failed");
            }
            GuiResult apply = applyResult.get();
            if (apply != null && !apply.isSuccess() && !apply.isAccepted()) {
                return RefreshOutcome.rejected(apply.errorCode());
            }
            return RefreshOutcome.rejected("render.failed");
        }
        if (paintFailure.get() != null) {
            return RefreshOutcome.rejected("render.failed");
        }
        if (Boolean.FALSE.equals(painted.get())) {
            return RefreshOutcome.rejected("render.failed");
        }
        GuiResult apply = applyResult.get();
        if (apply != null && !apply.isSuccess() && !apply.isAccepted()) {
            return RefreshOutcome.rejected(apply.errorCode());
        }
        return RefreshOutcome.success();
    }

    /**
     * Drop the view tag for a paint that did not complete, and bind the exact
     * failed shell so the consumer listener keeps it inoperable until close.
     * The backend session is deliberately left alone: a missing view can be
     * transient (or an offline test mock without an open inventory), and the
     * normal close / reload path owns the session lifecycle. A newer reopen
     * (larger generation, rebound tag) is never touched: the failed bind is
     * skipped when a newer session or tag already exists. Never throws.
     *
     * @param failedTop the top inventory the failed paint ran against, or
     *                  {@code null} when it was never resolved (no shell to guard)
     */
    private void failIncompleteRender(UUID playerUuid, long generation, Inventory failedTop) {
        try {
            synchronized (guardFor(playerUuid)) {
                // Drop the failed generation's tag plus any older orphan it
                // superseded; a newer reopen carries a larger generation and stays.
                // The pending guard for the same generation converts into the
                // failed guard below (or is dropped when there is no shell).
                viewTags.computeIfPresent(playerUuid, (key, tag) ->
                        tag.generation() <= generation ? null : tag);
                pendingViews.computeIfPresent(playerUuid, (key, tag) ->
                        tag.generation() <= generation ? null : tag);
                recordFailedViewLocked(playerUuid, generation, failedTop);
            }
        } catch (Throwable ignored) {
            // Cleanup is best-effort; the missing view tag already keeps clicks safe.
        }
    }

    /**
     * Publish the pending-render intent for a generation whose async update
     * already began. Runs on the calling thread and touches no Bukkit state:
     * it only records that an accepted render is in flight, so the consumer
     * listener can fail-closed the shell before the region capture pins the
     * exact inventory. Skips stale renders (the backend no longer carries
     * this generation) and never shadows a newer intent. Never throws.
     */
    private void publishPendingIntent(UUID playerUuid, long generation) {
        try {
            synchronized (guardFor(playerUuid)) {
                try {
                    GuiResult active = guiService.getActiveSession(playerUuid);
                    if (active == null || !active.isSuccess() || active.session() == null) {
                        return;
                    }
                    if (active.session().generation() != generation) {
                        return;
                    }
                } catch (Throwable t) {
                    // Backend unreadable: publish fail-closed; close converges it.
                }
                GuiSession current = sessions.get(playerUuid);
                if (current != null && current.generation() > generation) {
                    return;
                }
                Long existing = pendingIntents.get(playerUuid);
                if (existing != null && existing > generation) {
                    return;
                }
                pendingIntents.put(playerUuid, generation);
            }
        } catch (Throwable ignored) {
            // Without the intent the exact capture still guards once it runs.
        }
    }

    /**
     * Fail-closed publish for a render whose async begin was rejected or threw.
     * Binds the memory-only pending intent only when the requested generation
     * still owns the active backend session: a stale failure racing a newer
     * reopen must invent nothing, and a failure with no active session guards
     * no shell, so nothing is published and no fake state is invented. Touches
     * no Bukkit state. Never throws.
     */
    private void publishBeginFailureIntent(UUID playerUuid, long requestedGeneration) {
        try {
            long activeGeneration;
            try {
                GuiResult active = guiService.getActiveSession(playerUuid);
                if (active == null || !active.isSuccess() || active.session() == null) {
                    return;
                }
                activeGeneration = active.session().generation();
            } catch (Throwable t) {
                return;
            }
            if (activeGeneration != requestedGeneration) {
                return;
            }
            publishPendingIntent(playerUuid, activeGeneration);
        } catch (Throwable ignored) {
            // Without the intent the shell keeps its previous guards; close converges it.
        }
    }

    /**
     * Whether a pending-render intent is active for this generation. The
     * consumer listener honours it only together with the active backend
     * session and the size/title sanity it already checked, so it can never
     * reach beyond the player's own bank-sized view.
     */
    boolean hasPendingIntent(UUID playerUuid, long generation) {
        if (playerUuid == null) {
            return false;
        }
        try {
            Long intent = pendingIntents.get(playerUuid);
            return intent != null && intent == generation;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Clear the intent for a paint that converted it into a bound or failed
     * guard. A newer intent (larger generation) is never touched. Callers
     * must hold {@code guardFor(playerUuid)}.
     */
    private void clearPendingIntentAtOrBelowLocked(UUID playerUuid, long generation) {
        try {
            pendingIntents.computeIfPresent(playerUuid, (key, intent) ->
                    intent <= generation ? null : intent);
        } catch (Throwable ignored) {
            // Cleanup is best-effort.
        }
    }

    /**
     * Bind the currently open top inventory as the pending view for this
     * generation. Runs on the player region thread only. The size/title
     * sanity keeps a foreign or not-yet-opened view from being claimed: only
     * a top matching the active session's size and title is bound, and the
     * stale-safe bind below never shadows a newer reopen. Never throws.
     */
    private void capturePendingViewOnRegion(UUID playerUuid, long generation,
                                            com.smile.aceeconomy.infrastructure.acelib.BankGuiLayout layout) {
        Player player;
        try {
            player = players.get(playerUuid);
        } catch (Throwable t) {
            return;
        }
        if (player == null) {
            return;
        }
        Inventory top;
        String viewTitle;
        try {
            top = player.getOpenInventory().getTopInventory();
            viewTitle = player.getOpenInventory().getTitle();
        } catch (Throwable t) {
            return;
        }
        if (top == null) {
            return;
        }
        int topSize;
        try {
            topSize = top.getSize();
        } catch (Throwable t) {
            return;
        }
        if (layout != null && topSize != layout.size()) {
            return;
        }
        try {
            GuiResult active = guiService.getActiveSession(playerUuid);
            if (active == null || !active.isSuccess() || active.session() == null) {
                return;
            }
            if (active.session().generation() != generation) {
                return;
            }
            if (active.session().size() != topSize) {
                return;
            }
            if (viewTitle == null || !viewTitle.equals(active.session().title())) {
                return;
            }
        } catch (Throwable t) {
            return;
        }
        bindPendingView(playerUuid, generation, top);
    }

    /**
     * Bind a pending shell under the per-key fence. Skips stale captures: a
     * newer session, a newer bound/failed tag, or an already-operable view
     * for this generation means a reopen or a completed paint already owns
     * the player, so the stale capture must not shadow it. Never throws.
     */
    private void bindPendingView(UUID playerUuid, long generation, Inventory top) {
        try {
            synchronized (guardFor(playerUuid)) {
                bindPendingViewLocked(playerUuid, generation, top);
            }
        } catch (Throwable ignored) {
            // Best-effort guard; the pending intent still fail-closes until paint.
        }
    }

    /**
     * Stale-safe pending bind. Callers must hold {@code guardFor(playerUuid)}.
     */
    private void bindPendingViewLocked(UUID playerUuid, long generation, Inventory top) {
        if (playerUuid == null || top == null) {
            return;
        }
        try {
            if (!sessions.containsKey(playerUuid)) {
                return;
            }
            GuiSession current = sessions.get(playerUuid);
            if (current != null && current.generation() > generation) {
                return;
            }
            ViewTag bound = viewTags.get(playerUuid);
            if (bound != null && bound.generation() >= generation) {
                return;
            }
            ViewTag failed = failedViews.get(playerUuid);
            if (failed != null && failed.generation() >= generation) {
                return;
            }
            ViewTag pending = pendingViews.get(playerUuid);
            if (pending != null && pending.generation() > generation) {
                return;
            }
            pendingViews.put(playerUuid, new ViewTag(generation, top));
        } catch (Throwable ignored) {
            // Best-effort guard; the pending intent still fail-closes until paint.
        }
    }

    /**
     * Convert the intent into the exact failed guard (or drop it when there
     * is no shell to guard). Callers must hold {@code guardFor(playerUuid)}.
     * A newer intent (larger generation) is never touched.
     */
    private void recordFailedViewLocked(UUID playerUuid, long generation, Inventory failedTop) {
        if (playerUuid == null || failedTop == null) {
            return;
        }
        try {
            if (!sessions.containsKey(playerUuid)) {
                return;
            }
            GuiSession current = sessions.get(playerUuid);
            if (current != null && current.generation() > generation) {
                return;
            }
            ViewTag bound = viewTags.get(playerUuid);
            if (bound != null && bound.generation() > generation) {
                return;
            }
            ViewTag existing = failedViews.get(playerUuid);
            if (existing != null && existing.generation() > generation) {
                return;
            }
            failedViews.put(playerUuid, new ViewTag(generation, failedTop));
            // The failed guard supersedes the pending one for this generation
            // and any older orphan it replaced; a newer pending reopen stays.
            // The converted intent goes with it; a newer intent stays.
            pendingViews.computeIfPresent(playerUuid, (key, tag) ->
                    tag.generation() <= generation ? null : tag);
            clearPendingIntentAtOrBelowLocked(playerUuid, generation);
        } catch (Throwable ignored) {
            // Best-effort guard; the dropped view tag already keeps clicks safe.
        }
    }

    /** Best-effort read of the currently open top inventory; {@code null} when unknown. */
    private Inventory currentTop(UUID playerUuid) {
        try {
            Player player = players.get(playerUuid);
            if (player == null) {
                return null;
            }
            return player.getOpenInventory().getTopInventory();
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Paint the configured buttons into the player's currently open top inventory. Runs on
     * the player region thread (called from the AceLib async-update renderer).
     *
      * <p>The generation-bound view tag is created only after every planned action button
      * was written. A missing player, a replaced view, or any per-slot failure binds the
      * exact failed shell as a failed-view guard instead, so the consumer listener
      * cancels every interaction with the blank or partial GUI without dispatching.
      * Expected per-slot problems are reported as {@code false};
      * VM/Bukkit fatal errors ({@link Error}) propagate instead of counting as success.
     *
     * @return true when the full paint completed and the view tag was bound
     */
    private boolean paintLayout(UUID playerUuid, long generation,
                             com.smile.aceeconomy.infrastructure.acelib.BankGuiLayout layout,
                             com.smile.aceeconomy.infrastructure.acelib.ConfigLangAdapter messages,
                             BankGuiRenderer renderer) {
        Player player = players.get(playerUuid);
        if (player == null) {
            return false;
        }
        org.bukkit.inventory.Inventory top;
        try {
            top = player.getOpenInventory().getTopInventory();
        } catch (Exception e) {
            return false;
        }
        if (top == null) {
            return false;
        }
        int topSize;
        try {
            topSize = top.getSize();
        } catch (Exception e) {
            return false;
        }
        if (topSize != layout.size()) {
            bindFailedView(playerUuid, generation, top);
            return false;
        }
        // The renderer runs on the player region after AceLib re-validated the
        // session, the request and the open-inventory link, so this top is the
        // linked shell: refresh the pending guard from it before painting, in
        // case the pre-apply capture saw a stale or not-yet-opened view.
        bindPendingView(playerUuid, generation, top);
        Map<Integer, com.smile.aceeconomy.infrastructure.acelib.BankGuiLayout.SlotConfig> planned;
        try {
            planned = renderer.plan(layout);
        } catch (Throwable t) {
            bindFailedView(playerUuid, generation, top);
            if (t instanceof Error error) {
                throw error;
            }
            return false;
        }
        Set<Integer> written;
        try {
            written = renderer.renderInto(top, layout, messages);
        } catch (Throwable t) {
            bindFailedView(playerUuid, generation, top);
            if (t instanceof Error error) {
                throw error;
            }
            return false;
        }
        if (written == null || !written.containsAll(planned.keySet())) {
            bindFailedView(playerUuid, generation, top);
            return false;
        }
        bindBoundView(playerUuid, generation, top);
        return true;
    }

    /**
     * Record a failed shell for a paint that ran against a known top
     * inventory. Stale-safe through {@link #recordFailedViewLocked}: a newer
     * reopen is never shadowed. Never throws.
     */
    private void bindFailedView(UUID playerUuid, long generation, Inventory top) {
        try {
            synchronized (guardFor(playerUuid)) {
                recordFailedViewLocked(playerUuid, generation, top);
            }
        } catch (Throwable ignored) {
            // Best-effort guard; the dropped view tag already keeps clicks safe.
        }
    }

    /**
     * Bind a fully painted view and drop any failed guard at or below the
     * painted generation. The pending guard for the painted generation
     * converts into the bound view the same way. A stale paint for an older
     * generation never binds:
     * the current session or an existing newer tag proves a reopen already
     * owns the player. Never throws.
     */
    private void bindBoundView(UUID playerUuid, long generation, Inventory top) {
        try {
            synchronized (guardFor(playerUuid)) {
                GuiSession current = sessions.get(playerUuid);
                if (current != null && current.generation() > generation) {
                    return;
                }
                ViewTag bound = viewTags.get(playerUuid);
                if (bound != null && bound.generation() > generation) {
                    return;
                }
                viewTags.put(playerUuid, new ViewTag(generation, top));
                failedViews.computeIfPresent(playerUuid, (key, tag) ->
                        tag.generation() <= generation ? null : tag);
                pendingViews.computeIfPresent(playerUuid, (key, tag) ->
                        tag.generation() <= generation ? null : tag);
                clearPendingIntentAtOrBelowLocked(playerUuid, generation);
            }
        } catch (Throwable ignored) {
            // Binding is best-effort; without the tag the shell stays inoperable.
        }
    }

    /**
     * Whether an exact pending or failed guard is bound for this generation
     * (any inventory object). While one exists, the exact mechanism owns the
     * decision: the matching shell is cancelled by identity, and any other
     * object — including a same-title/same-size foreign inventory — passes
     * through. The intent fallback therefore applies only when no exact guard
     * exists yet for the generation. Never throws.
     */
    boolean hasExactGuard(UUID playerUuid, long generation) {
        if (playerUuid == null) {
            return false;
        }
        try {
            ViewTag pending = pendingViews.get(playerUuid);
            if (pending != null && pending.generation() == generation) {
                return true;
            }
            ViewTag failed = failedViews.get(playerUuid);
            return failed != null && failed.generation() == generation;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Ownership proof for a pending shell: the event's top inventory must be
     * the exact object captured on the player region for the same backend
     * session generation. A same-title/same-size inventory from another
     * plugin is a different object and is rejected here, before any cancel.
     */
    boolean isPendingView(UUID playerUuid, long generation, Inventory top) {
        if (playerUuid == null || top == null) {
            return false;
        }
        try {
            ViewTag tag = pendingViews.get(playerUuid);
            return tag != null && tag.generation() == generation && tag.top() == top;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Ownership proof for consumer events: the event's top inventory must be the exact
     * object the player-region render bound to this backend session generation. A
     * same-title/same-size inventory from another plugin is a different object and is
     * rejected here, before any cancel or dispatch.
     */
    boolean isBoundView(UUID playerUuid, long generation, Inventory top) {
        if (playerUuid == null || top == null) {
            return false;
        }
        try {
            ViewTag tag = viewTags.get(playerUuid);
            return tag != null && tag.generation() == generation && tag.top() == top;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Ownership proof for a failed shell: the event's top inventory must be
     * the exact object the failed paint ran against, stapled to the same
     * backend session generation. A same-title/same-size inventory from
     * another plugin is a different object and is rejected here, before any
     * cancel.
     */
    boolean isFailedView(UUID playerUuid, long generation, Inventory top) {
        if (playerUuid == null || top == null) {
            return false;
        }
        try {
            ViewTag tag = failedViews.get(playerUuid);
            return tag != null && tag.generation() == generation && tag.top() == top;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Fallback close cleanup for a bound, failed or still-pending bank view whose backend session is already gone
     * (the backend's own close listener ran first on the same event). Only the local
     * bookkeeping still carrying the closed view's generation is dropped; a newer reopen that
     * rebound the tag, or any entry with a different generation, is kept.
     *
     * @return true when the closing view was the bound, failed or pending bank inventory and its
     *         generation's local bookkeeping was dropped
     */
    boolean discardViewIfBound(UUID playerUuid, Inventory top) {
        if (playerUuid == null || top == null) {
            return false;
        }
        try {
            ViewTag tag = viewTags.get(playerUuid);
            if (tag != null && tag.top() == top) {
                dropLocalIfGenerationMatches(playerUuid, tag.generation());
                return true;
            }
            ViewTag failed = failedViews.get(playerUuid);
            if (failed != null && failed.top() == top) {
                dropLocalIfGenerationMatches(playerUuid, failed.generation());
                return true;
            }
            ViewTag pending = pendingViews.get(playerUuid);
            if (pending != null && pending.top() == top) {
                dropLocalIfGenerationMatches(playerUuid, pending.generation());
                return true;
            }
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Read-only test seam: whether local session bookkeeping is still tracked. */
    boolean hasTrackedSession(UUID playerUuid) {
        if (playerUuid == null) {
            return false;
        }
        try {
            return sessions.containsKey(playerUuid);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Render with the production item construction. See
     * {@link #renderLayout(UUID, long, com.smile.aceeconomy.infrastructure.acelib.BankGuiLayout, com.smile.aceeconomy.infrastructure.acelib.ConfigLangAdapter, BankGuiRenderer)}.
     */
    public @NotNull RefreshOutcome renderLayout(@NotNull UUID playerUuid, long generation,
                                                @NotNull com.smile.aceeconomy.infrastructure.acelib.BankGuiLayout layout,
                                                @NotNull com.smile.aceeconomy.infrastructure.acelib.ConfigLangAdapter messages) {
        return renderLayout(playerUuid, generation, layout, messages, BankGuiRenderer.production());
    }

    public @NotNull CloseOutcome close(@NotNull UUID playerUuid, long generation) {
        GuiResult result = guiService.closeInventory(playerUuid, generation);
        if (!result.isSuccess()) {
            return CloseOutcome.rejected(result.errorCode());
        }
        // Conditional for the same reason as the stale-open cleanup: a
        // concurrent reopen already replaced the entry with a larger
        // generation, and the unconditional remove would delete it.
        dropLocalIfGenerationMatches(playerUuid, generation);
        return CloseOutcome.closed();
    }

    /**
     * Best-effort local cleanup for a bank view that closed without going through
     * {@link #close} (for example the player pressed ESC and the GUI backend already
     * dropped its own session, so a backend close here would be rejected). Only the
     * entry still carrying {@code generation} is dropped; a newer reopen is kept.
     * Never throws.
     */
    public void noteViewClosed(@NotNull UUID playerUuid, long generation) {
        try {
            dropLocalIfGenerationMatches(playerUuid, generation);
        } catch (Throwable ignored) {
            // Local bookkeeping is best-effort; a later click re-validates against
            // the backend session and is rejected when nothing is open.
        }
    }

    private void dropLocalIfGenerationMatches(UUID playerUuid, long generation) {
        synchronized (guardFor(playerUuid)) {
            sessions.computeIfPresent(playerUuid, (key, current) ->
                    current.generation() == generation ? null : current);
            if (!sessions.containsKey(playerUuid)) {
                sessionLayoutGenerations.remove(playerUuid);
                players.remove(playerUuid);
            }
            // The view tag belongs to the dropped generation only; a newer reopen
            // that rebound the tag carries a larger generation and is kept. The
            // failed-view and pending-view guards follow the same rule: only the
            // closed generation's guards are dropped, never a newer one. The
            // pending intent for the closed generation goes with them.
            viewTags.computeIfPresent(playerUuid, (key, tag) ->
                    tag.generation() == generation ? null : tag);
            failedViews.computeIfPresent(playerUuid, (key, tag) ->
                    tag.generation() == generation ? null : tag);
            pendingViews.computeIfPresent(playerUuid, (key, tag) ->
                    tag.generation() == generation ? null : tag);
            pendingIntents.computeIfPresent(playerUuid, (key, intent) ->
                    intent == generation ? null : intent);
        }
    }

    public @NotNull Optional<GuiSession> activeSession(@NotNull UUID playerUuid) {
        GuiResult result = guiService.getActiveSession(playerUuid);
        if (result.isSuccess() && result.session() != null) {
            return Optional.of(result.session());
        }
        return Optional.empty();
    }

    /**
     * Hot-swap the layout resolver after a validated reload. Sessions opened afterwards
     * resolve slots against the new layout; sessions already open keep working until
     * {@link #invalidateAll()} drops them. Every swap starts a new layout generation so
     * opens bound to the previous generation are rejected instead of building stale
     * sessions; the production reload publishes the new layout reference before calling
     * this, so a reader that takes the generation first and the layout second only ever
     * sees a matching pair or a newer generation, never a stale layout with a matching
     * generation.
     */
    public void replaceLayout(@NotNull Function<Integer, BankGuiAction> resolver) {
        this.actionResolver = Objects.requireNonNull(resolver, "resolver");
        layoutGeneration.incrementAndGet();
    }

    /** Current layout generation for generation-bound opens. */
    public long layoutGeneration() {
        return layoutGeneration.get();
    }

    /**
     * Drop every open session so no pre-reload generation can act under post-reload rules.
     * Each known session is closed through {@link GuiService} best-effort, then local
     * bookkeeping is cleared unconditionally: even if a remote close throws, later clicks
     * find no session and are rejected instead of running against mixed versions.
     *
     * @return the number of locally tracked sessions dropped
     */
    public int invalidateAll() {
        java.util.List<Map.Entry<UUID, GuiSession>> snapshot =
                new java.util.ArrayList<>(sessions.entrySet());
        java.util.Map<UUID, SessionTag> knownTags = new java.util.HashMap<>(sessionLayoutGenerations);
        int dropped = 0;
        for (Map.Entry<UUID, GuiSession> entry : snapshot) {
            UUID uuid = entry.getKey();
            GuiSession known = entry.getValue();
            synchronized (guardFor(uuid)) {
                try {
                    if (known != null && sessions.get(uuid) == known) {
                        guiService.closeInventory(uuid, known.generation());
                    }
                } catch (Throwable ignored) {
                    // Local state below is still cleared; the stale generation can no longer act.
                } finally {
                    // Conditional: an open that completed after the snapshot
                    // replaced the entry, and the unconditional remove would
                    // delete the fresh session. The player entry is kept with it.
                    if (sessions.remove(uuid, known)) {
                        SessionTag knownTag = knownTags.get(uuid);
                        if (knownTag == null) {
                            sessionLayoutGenerations.remove(uuid);
                        } else {
                            sessionLayoutGenerations.remove(uuid, knownTag);
                        }
                        // The view tag belongs to the dropped backend session only;
                        // a concurrent reopen that rebound it carries a larger
                        // generation and survives the conditional removal. The
                        // failed-view and pending-view guards for the same
                        // generation go with it, as does its pending intent.
                        long droppedGeneration = known.generation();
                        viewTags.computeIfPresent(uuid, (key, tag) ->
                                tag.generation() == droppedGeneration ? null : tag);
                        failedViews.computeIfPresent(uuid, (key, tag) ->
                                tag.generation() == droppedGeneration ? null : tag);
                        pendingViews.computeIfPresent(uuid, (key, tag) ->
                                tag.generation() == droppedGeneration ? null : tag);
                        pendingIntents.computeIfPresent(uuid, (key, intent) ->
                                intent == droppedGeneration ? null : intent);
                        dropped++;
                    }
                    if (!sessions.containsKey(uuid)) {
                        players.remove(uuid);
                    }
                }
            }
        }
        sweepOrphansLocked();
        return dropped;
    }

    /**
     * Best-effort rollback cleanup after a failed layout swap. Closes every
     * session opened from the failed candidate — tags newer than
     * {@code keepThroughGeneration} up to {@code failedGeneration} — so no
     * new-size session survives under the restored resolver. Sessions proven
     * to predate the swap keep working; an untagged entry cannot be proven
     * old, so it is dropped and the player reopens. Never throws: any failure
     * is swallowed after clearing what can be cleared, and callers record it
     * as a suppressed cause on the original failure.
     *
     * @return the number of sessions dropped
     */
    public int dropSessionsAfterFailedSwap(long keepThroughGeneration, long failedGeneration) {
        if (failedGeneration <= keepThroughGeneration) {
            return 0;
        }
        java.util.List<Map.Entry<UUID, GuiSession>> snapshot =
                new java.util.ArrayList<>(sessions.entrySet());
        int dropped = 0;
        for (Map.Entry<UUID, GuiSession> entry : snapshot) {
            dropped += dropOneSuspectAfterFailedSwap(
                    entry.getKey(), entry.getValue(), keepThroughGeneration, failedGeneration)
                    ? 1 : 0;
        }
        sweepOrphansLocked();
        // Re-scan for publishes that landed after the snapshot was taken: an
        // open whose puts arrived mid-rollback is suspect under the same rule,
        // and the weakly consistent snapshot above may have missed it. Opens
        // that land after this pass self-clean through the post-publish fence
        // in open(), so a single bounded pass closes the window.
        for (Map.Entry<UUID, GuiSession> entry :
                new java.util.ArrayList<>(sessions.entrySet())) {
            dropped += dropOneSuspectAfterFailedSwap(
                    entry.getKey(), entry.getValue(), keepThroughGeneration, failedGeneration)
                    ? 1 : 0;
        }
        sweepOrphansLocked();
        return dropped;
    }

    /**
     * Drop one rollback suspect for the same per-key fence the open publish
     * holds. Returns true when a session was dropped. Never throws.
     */
    private boolean dropOneSuspectAfterFailedSwap(UUID uuid, GuiSession known,
                                                  long keepThroughGeneration,
                                                  long failedGeneration) {
        synchronized (guardFor(uuid)) {
            try {
                if (known == null || sessions.get(uuid) != known) {
                    return false;
                }
                SessionTag tag = sessionLayoutGenerations.get(uuid);
                // An entry without a tag cannot be proven old: the rollback may have
                // landed between the session put and the tag put of an open from the
                // failed candidate, so treat it as suspect and drop it. A tag owned
                // by anyone but the current session is equally stale: the sessions
                // put of a same-UUID candidate landed first and the tag put has not
                // caught up, so the current entry is the suspect, not the old tag.
                // Only a tag owned by the current session at or below the pre-swap
                // generation (or beyond the failed one) is kept.
                if (tag != null && tag.owner() == known
                        && (tag.layoutGeneration() <= keepThroughGeneration
                            || tag.layoutGeneration() > failedGeneration)) {
                    return false;
                }
                try {
                    guiService.closeInventory(uuid, known.generation());
                } catch (Throwable ignored) {
                    // Local state below is still cleared.
                }
                if (sessions.remove(uuid, known)) {
                    if (tag == null) {
                        sessionLayoutGenerations.remove(uuid);
                    } else {
                        sessionLayoutGenerations.remove(uuid, tag);
                    }
                    if (!sessions.containsKey(uuid)) {
                        players.remove(uuid);
                    }
                    long droppedGeneration = known.generation();
                    viewTags.computeIfPresent(uuid, (key, viewTag) ->
                            viewTag.generation() == droppedGeneration ? null : viewTag);
                    failedViews.computeIfPresent(uuid, (key, failedTag) ->
                            failedTag.generation() == droppedGeneration ? null : failedTag);
                    pendingViews.computeIfPresent(uuid, (key, pendingTag) ->
                            pendingTag.generation() == droppedGeneration ? null : pendingTag);
                    pendingIntents.computeIfPresent(uuid, (key, intent) ->
                            intent == droppedGeneration ? null : intent);
                    return true;
                }
                return false;
            } catch (Throwable ignored) {
                return false;
            }
        }
    }

    /**
     * Per-key orphan sweep: drop players/tag entries that back no session.
     * Each key is rechecked under its fence, so a concurrent publish racing
     * the scan cannot be mistaken for an orphan.
     */
    private void sweepOrphansLocked() {
        for (UUID key : new java.util.ArrayList<>(players.keySet())) {
            synchronized (guardFor(key)) {
                if (!sessions.containsKey(key)) {
                    players.remove(key);
                }
            }
        }
        for (UUID key : new java.util.ArrayList<>(sessionLayoutGenerations.keySet())) {
            synchronized (guardFor(key)) {
                if (!sessions.containsKey(key)) {
                    sessionLayoutGenerations.remove(key);
                }
            }
        }
        for (UUID key : new java.util.ArrayList<>(viewTags.keySet())) {
            synchronized (guardFor(key)) {
                if (!sessions.containsKey(key)) {
                    viewTags.remove(key);
                }
            }
        }
        for (UUID key : new java.util.ArrayList<>(failedViews.keySet())) {
            synchronized (guardFor(key)) {
                if (!sessions.containsKey(key)) {
                    failedViews.remove(key);
                }
            }
        }
        for (UUID key : new java.util.ArrayList<>(pendingViews.keySet())) {
            synchronized (guardFor(key)) {
                if (!sessions.containsKey(key)) {
                    pendingViews.remove(key);
                }
            }
        }
        for (UUID key : new java.util.ArrayList<>(pendingIntents.keySet())) {
            synchronized (guardFor(key)) {
                if (!sessions.containsKey(key)) {
                    pendingIntents.remove(key);
                }
            }
        }
    }

    // ---- outcomes -------------------------------------------------------------

    public static final class OpenOutcome {
        private final boolean success;
        private final GuiSession session;
        private final String errorCode;
        private final String detail;

        private OpenOutcome(boolean success, GuiSession session, String errorCode, String detail) {
            this.success = success;
            this.session = session;
            this.errorCode = errorCode;
            this.detail = detail;
        }

        public static OpenOutcome success(GuiSession session) {
            return new OpenOutcome(true, session, null, null);
        }

        public static OpenOutcome failed(String errorCode, String detail) {
            return new OpenOutcome(false, null, errorCode, detail);
        }

        public boolean success() {
            return success;
        }

        public GuiSession session() {
            return session;
        }

        public String errorCode() {
            return errorCode;
        }

        public String detail() {
            return detail;
        }
    }

    public static final class ClickOutcome {
        private enum Kind { ACCEPTED, ALLOWED, SUCCESS, CREDITED, CREDIT_RETAINED, ITEM_MISMATCH, SNAPSHOT_FAILED, INVENTORY_FULL, REJECTED, DISPATCHED }

        private final Kind kind;
        private final ItemStack banknote;
        private final String reason;

        private ClickOutcome(Kind kind, ItemStack banknote, String reason) {
            this.kind = kind;
            this.banknote = banknote;
            this.reason = reason;
        }

        public static ClickOutcome accepted() {
            return new ClickOutcome(Kind.ACCEPTED, null, null);
        }

        public static ClickOutcome allowed() {
            return new ClickOutcome(Kind.ALLOWED, null, null);
        }

        public static ClickOutcome success(ItemStack banknote) {
            return new ClickOutcome(Kind.SUCCESS, banknote, null);
        }

        /** The held banknote was redeemed: nonce consumed and account credited, item removed. */
        public static ClickOutcome credited() {
            return new ClickOutcome(Kind.CREDITED, null, null);
        }

        /**
         * The credit was committed but the held item could not be removed (it vanished from the
         * hand before removal). The durable nonce protection makes the leftover item unredeemable,
         * but the outcome must stay distinguishable from a clean success.
         */
        public static ClickOutcome creditRetained() {
            return new ClickOutcome(Kind.CREDIT_RETAINED, null, "item.remove-failed");
        }

        /**
         * The credit was committed but the current hand no longer matches the
         * item that was credited (swapped to a different ItemStack). The current
         * item is left untouched and the outcome is distinguishable from a clean
         * success; the nonce is already consumed so the original note cannot be
         * double-credited.
         */
        public static ClickOutcome itemMismatch() {
            return new ClickOutcome(Kind.ITEM_MISMATCH, null, "item.mismatch");
        }

        /** Click-time snapshot could not be copied safely; caller must retry. */
        public static ClickOutcome snapshotFailed() {
            return new ClickOutcome(Kind.SNAPSHOT_FAILED, null, "item.snapshot-failed");
        }

        public static ClickOutcome inventoryFull() {
            return new ClickOutcome(Kind.INVENTORY_FULL, null, null);
        }

        public static ClickOutcome rejected(String reason) {
            return new ClickOutcome(Kind.REJECTED, null, reason);
        }

        /**
         * The Folia dispatch has been queued but has not yet executed. Callers that called the
         * synchronous {@link V2BankGuiSession#handleClick} receive this instead of a fake success;
         * the real outcome arrives via {@link V2BankGuiSession#handleClickAsync}.
         */
        public static ClickOutcome dispatched() {
            return new ClickOutcome(Kind.DISPATCHED, null, "dispatched");
        }

        public boolean isAccepted() {
            return kind == Kind.ACCEPTED;
        }

        public boolean isAllowed() {
            return kind == Kind.ALLOWED;
        }

        public boolean isSuccess() {
            return kind == Kind.SUCCESS;
        }

        public boolean isCredited() {
            return kind == Kind.CREDITED;
        }

        public boolean isCreditRetained() {
            return kind == Kind.CREDIT_RETAINED;
        }

        public boolean isItemMismatch() {
            return kind == Kind.ITEM_MISMATCH;
        }

        public boolean isSnapshotFailed() {
            return kind == Kind.SNAPSHOT_FAILED;
        }

        public boolean isInventoryFull() {
            return kind == Kind.INVENTORY_FULL;
        }

        public boolean isRejected() {
            return kind == Kind.REJECTED;
        }

        public boolean isDispatched() {
            return kind == Kind.DISPATCHED;
        }

        public ItemStack banknote() {
            return banknote;
        }

        public String reason() {
            return reason;
        }
    }

    public static final class RefreshOutcome {
        private final boolean success;
        private final String errorCode;

        private RefreshOutcome(boolean success, String errorCode) {
            this.success = success;
            this.errorCode = errorCode;
        }

        public static RefreshOutcome success() {
            return new RefreshOutcome(true, null);
        }

        public static RefreshOutcome rejected(String errorCode) {
            return new RefreshOutcome(false, errorCode);
        }

        public boolean isSuccess() {
            return success;
        }

        public String errorCode() {
            return errorCode;
        }
    }

    public static final class CloseOutcome {
        private final boolean closed;
        private final String errorCode;

        private CloseOutcome(boolean closed, String errorCode) {
            this.closed = closed;
            this.errorCode = errorCode;
        }

        public static CloseOutcome closed() {
            return new CloseOutcome(true, null);
        }

        public static CloseOutcome rejected(String errorCode) {
            return new CloseOutcome(false, errorCode);
        }

        public boolean isClosed() {
            return closed;
        }

        public String errorCode() {
            return errorCode;
        }
    }
}
