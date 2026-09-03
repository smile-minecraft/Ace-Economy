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
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
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

    private final Map<UUID, Player> players = new ConcurrentHashMap<>();
    private final Map<UUID, GuiSession> sessions = new ConcurrentHashMap<>();

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
        GuiArgument arg = GuiArgument.of(player, title, size, protectedSlots);
        GuiResult result = guiService.openInventory(arg);
        if (!result.isSuccess() || result.session() == null) {
            return OpenOutcome.failed(result.errorCode(), result.detail());
        }
        players.put(player.getUniqueId(), player);
        sessions.put(player.getUniqueId(), result.session());
        return OpenOutcome.success(result.session());
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

    public @NotNull CloseOutcome close(@NotNull UUID playerUuid, long generation) {
        GuiResult result = guiService.closeInventory(playerUuid, generation);
        if (!result.isSuccess()) {
            return CloseOutcome.rejected(result.errorCode());
        }
        players.remove(playerUuid);
        sessions.remove(playerUuid);
        return CloseOutcome.closed();
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
     * {@link #invalidateAll()} drops them.
     */
    public void replaceLayout(@NotNull Function<Integer, BankGuiAction> resolver) {
        this.actionResolver = Objects.requireNonNull(resolver, "resolver");
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
        int dropped = 0;
        for (Map.Entry<UUID, GuiSession> entry : snapshot) {
            UUID uuid = entry.getKey();
            GuiSession known = entry.getValue();
            try {
                if (known != null) {
                    guiService.closeInventory(uuid, known.generation());
                }
            } catch (Throwable ignored) {
                // Local state below is still cleared; the stale generation can no longer act.
            } finally {
                players.remove(uuid);
                sessions.remove(uuid);
                dropped++;
            }
        }
        return dropped;
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
