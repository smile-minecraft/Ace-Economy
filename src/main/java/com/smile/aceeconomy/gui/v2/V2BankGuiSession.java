package com.smile.aceeconomy.gui.v2;

import com.smile.acelib.gui.GuiButtonClick;
import com.smile.acelib.gui.GuiResult;
import com.smile.acelib.gui.GuiScope;
import com.smile.acelib.gui.GuiSession;
import com.smile.acelib.gui.GuiView;
import com.smile.aceeconomy.infrastructure.acelib.BankGuiLayout;
import com.smile.aceeconomy.infrastructure.acelib.ConfigLangAdapter;
import com.smile.aceeconomy.infrastructure.banknote.BanknoteFlow;
import com.smile.aceeconomy.ports.BankGuiUseCase;
import com.smile.aceeconomy.ports.DepositResult;
import com.smile.aceeconomy.ports.FoliaContextExecutor;
import com.smile.aceeconomy.ports.WithdrawResult;

import net.kyori.adventure.text.Component;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;

/**
 * v2 bank GUI session controller on the declarative model. The bank screen
 * is assembled up front from the operator-owned {@link BankGuiLayout} and
 * opened through the plugin-owned {@link GuiScope}: one complete
 * {@link GuiView} per open, with every button built before the scope call.
 * A button that cannot be built refuses the whole open with an explicit
 * player-facing error, so a view that failed assembly never reaches the
 * scope. Icon placement happens later, inside the upstream scope; a slot
 * that fails there is skipped and the view still opens without that icon,
 * so the refusal covers assembly failures only.
 *
 * <p>Click protection, stale-click rejection, drag protection and close
 * handling are owned upstream: the scope only ever dispatches a button
 * handler for a live session owned by this plugin, and the view denies
 * every non-button slot by default. This class keeps no pending, failed or
 * window guards of its own.
 *
 * <p>Every button handler runs on the player region thread. Withdraw and
 * deposit reach the ledger only through {@link BankGuiUseCase} (offline
 * path) or the shared {@link BanknoteFlow} (production path), exactly like
 * the command and form entries. A deposit clones the main-hand item at
 * click time, so a later hand swap cannot credit one item while removing
 * another.
 *
 * <p>Reload reopens every tracked view with the new layout through
 * {@link #applyLayout}: the screen is rebuilt in place, and only a view
 * whose rebuild or reopen fails is closed on a best-effort basis with a
 * best-effort notice, leaving the player to open it again by hand.
 *
 * <p>Wired by the production composition root at server start; constructed
 * with collaborators so it is fully unit-testable with fakes.
 */
public final class V2BankGuiSession {

    private static final System.Logger LOG = System.getLogger(V2BankGuiSession.class.getName());

    private final GuiScope scope;
    private final FoliaContextExecutor folia;
    private final BankGuiUseCase useCase;
    private final ConfigLangAdapter messages;
    private final BankGuiRenderer renderer;
    /**
     * Shared banknote pipeline. {@code null} in offline unit tests that drive
     * the synchronous use-case path; production always wires the flow so money
     * moves off the region thread with the same handover guarantees as the
     * command and form entries.
     */
    private final BanknoteFlow banknoteFlow;

    private volatile BankGuiLayout layout;

    private final ConcurrentMap<UUID, GuiSession> openSessions = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, Player> players = new ConcurrentHashMap<>();
    /**
     * Serialises opens against layout swaps: an open either completes fully
     * before a swap (and is then rebuilt by it) or reads the new layout, so
     * a swapped-out layout can never leak a stale view past the reload.
     */
    private final Object openLock = new Object();

    public V2BankGuiSession(@NotNull GuiScope scope,
                            @NotNull FoliaContextExecutor folia,
                            @NotNull BankGuiUseCase useCase,
                            @NotNull BankGuiLayout layout,
                            @NotNull ConfigLangAdapter messages,
                            BanknoteFlow banknoteFlow,
                            @NotNull BankGuiRenderer renderer) {
        this.scope = Objects.requireNonNull(scope, "scope");
        this.folia = Objects.requireNonNull(folia, "folia");
        this.useCase = Objects.requireNonNull(useCase, "useCase");
        this.layout = Objects.requireNonNull(layout, "layout");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.banknoteFlow = banknoteFlow;
        this.renderer = Objects.requireNonNull(renderer, "renderer");
    }

    public V2BankGuiSession(@NotNull GuiScope scope,
                            @NotNull FoliaContextExecutor folia,
                            @NotNull BankGuiUseCase useCase,
                            @NotNull BankGuiLayout layout,
                            @NotNull ConfigLangAdapter messages,
                            BanknoteFlow banknoteFlow) {
        this(scope, folia, useCase, layout, messages, banknoteFlow, BankGuiRenderer.production());
    }

    public V2BankGuiSession(@NotNull GuiScope scope,
                            @NotNull FoliaContextExecutor folia,
                            @NotNull BankGuiUseCase useCase,
                            @NotNull BankGuiLayout layout,
                            @NotNull ConfigLangAdapter messages) {
        this(scope, folia, useCase, layout, messages, null, BankGuiRenderer.production());
    }

    /**
     * Open the bank view for {@code player} from the current layout. The
     * title and every button are built first; any assembly failure refuses
     * the open and tells the player instead of opening an incompletely
     * assembled view. The refusal covers assembly only: per-slot icon
     * placement inside the upstream scope may still skip a slot (see the
     * class note), leaving that icon missing.
     */
    public @NotNull OpenOutcome open(@NotNull Player player) {
        Objects.requireNonNull(player, "player");
        synchronized (openLock) {
            BankGuiLayout current = layout;
            if (!current.enabled()) {
                return OpenOutcome.failed("bank.disabled", "bank gui is disabled");
            }
            UUID uuid = player.getUniqueId();
            String title;
            try {
                title = messages.plainMessage(current.titleKey(), Map.of());
            } catch (Throwable failure) {
                tellFailure(player);
                return OpenOutcome.failed("title.render-failed",
                        failure.getClass().getSimpleName());
            }
            GuiView view;
            try {
                view = BankGuiViewFactory.build(title, current, messages, renderer, this::handlerFor);
            } catch (BankGuiViewFactory.ViewBuildException buildFailure) {
                LOG.log(System.Logger.Level.WARNING,
                        "Bank GUI open refused, button build failed: " + buildFailure.getMessage());
                tellFailure(player);
                return OpenOutcome.failed("view.build-failed", buildFailure.getMessage());
            } catch (Throwable failure) {
                LOG.log(System.Logger.Level.WARNING,
                        "Bank GUI open refused: " + failure.getClass().getSimpleName());
                tellFailure(player);
                return OpenOutcome.failed("view.build-failed", failure.getClass().getSimpleName());
            }
            GuiResult result;
            try {
                result = scope.openView(uuid, view);
            } catch (Throwable failure) {
                tellFailure(player);
                return OpenOutcome.failed("open.failed", failure.getClass().getSimpleName());
            }
            if (result == null || !result.isSuccess() || result.session() == null) {
                tellFailure(player);
                String code = result == null ? "open.failed" : result.errorCode();
                String detail = result == null ? "scope returned null" : result.detail();
                return OpenOutcome.failed(code, detail);
            }
            GuiSession session = result.session();
            openSessions.put(uuid, session);
            players.put(uuid, player);
            return OpenOutcome.success(session);
        }
    }

    /**
     * Close the player's bank view through the owning scope. Best-effort:
     * local tracking is dropped even when the scope call fails, so a later
     * open never resolves against a dead entry.
     */
    public @NotNull CloseOutcome close(@NotNull UUID playerUuid) {
        Objects.requireNonNull(playerUuid, "playerUuid");
        GuiResult result;
        try {
            result = scope.close(playerUuid);
        } catch (Throwable failure) {
            openSessions.remove(playerUuid);
            players.remove(playerUuid);
            return CloseOutcome.rejected("close.failed");
        }
        openSessions.remove(playerUuid);
        players.remove(playerUuid);
        if (result != null && result.isSuccess()) {
            return CloseOutcome.closed();
        }
        return CloseOutcome.rejected(result == null ? "close.failed" : result.errorCode());
    }

    /**
     * The locally tracked open view, if this controller opened one for the
     * player and has not dropped it since. A player who closed the window
     * by hand may still resolve here until the next reload or invalidation
     * heals the entry; every click is still re-validated against the live
     * scope state before any business runs.
     */
    public @NotNull Optional<GuiSession> activeSession(@NotNull UUID playerUuid) {
        return Optional.ofNullable(openSessions.get(playerUuid));
    }

    /**
     * Hot-swap the layout after a validated reload and reopen every tracked
     * view in place with the new content. A player whose rebuild and reopen
     * both succeed keeps the same window, now showing the new content. A
     * player whose rebuild or reopen fails is closed on a best-effort basis
     * and sent a best-effort failure notice, and is no longer tracked. A
     * player who already closed by hand simply stops being tracked.
     *
     * @return the number of views rebuilt and reopened
     */
    public int applyLayout(@NotNull BankGuiLayout newLayout) {
        Objects.requireNonNull(newLayout, "newLayout");
        String title;
        try {
            title = messages.plainMessage(newLayout.titleKey(), Map.of());
        } catch (Throwable failure) {
            LOG.log(System.Logger.Level.WARNING,
                    "Bank GUI reload reopen skipped, title render failed: "
                            + failure.getClass().getSimpleName());
            return 0;
        }
        synchronized (openLock) {
            layout = newLayout;
            List<UUID> ids = new ArrayList<>(openSessions.keySet());
            if (!newLayout.enabled()) {
                // A disabled layout reopens nothing: every tracked view is
                // closed, so no click can run against a switched-off surface.
                for (UUID uuid : ids) {
                    closeQuietly(uuid);
                }
                return 0;
            }
            int reopened = 0;
            for (UUID uuid : ids) {
                GuiView view;
                try {
                    view = BankGuiViewFactory.build(title, newLayout, messages, renderer,
                            this::handlerFor);
                } catch (Throwable failure) {
                    LOG.log(System.Logger.Level.WARNING,
                            "Bank GUI reload reopen failed for " + uuid + ", closing: "
                                    + failure.getClass().getSimpleName());
                    Player player = playerFor(uuid);
                    closeQuietly(uuid);
                    tellFailure(player);
                    continue;
                }
                openSessions.remove(uuid);
                GuiResult result;
                try {
                    result = scope.replaceView(uuid, view);
                } catch (Throwable failure) {
                    Player player = playerFor(uuid);
                    closeQuietly(uuid);
                    tellFailure(player);
                    continue;
                }
                if (result != null && result.isSuccess() && result.session() != null) {
                    openSessions.put(uuid, result.session());
                    reopened++;
                } else {
                    Player player = playerFor(uuid);
                    closeQuietly(uuid);
                    tellFailure(player);
                }
            }
            return reopened;
        }
    }

    /**
     * Drop every tracked view so no pre-reload generation can act under
     * post-reload rules. Each scope close is best-effort and tracking is
     * cleared unconditionally: even if a scope call throws, later clicks
     * find no entry and are rejected instead of running against mixed
     * versions.
     *
     * @return the number of locally tracked views dropped
     */
    public int invalidateAll() {
        List<UUID> ids = new ArrayList<>(openSessions.keySet());
        int dropped = 0;
        for (UUID uuid : ids) {
            try {
                scope.close(uuid);
            } catch (Throwable ignored) {
                // Tracking below is still cleared; the stale entry can no longer act.
            } finally {
                if (openSessions.remove(uuid) != null) {
                    dropped++;
                }
                players.remove(uuid);
            }
        }
        return dropped;
    }

    private @NotNull Consumer<GuiButtonClick> handlerFor(@NotNull BankGuiAction action,
                                                         int slot) {
        return switch (action.type()) {
            case WITHDRAW -> click -> onWithdrawClick(click, action);
            case DEPOSIT -> this::onDepositClick;
            case CLOSE -> this::onCloseClick;
            case NONE -> click -> { };
        };
    }

    private void onWithdrawClick(@NotNull GuiButtonClick click, @NotNull BankGuiAction action) {
        UUID uuid = click.playerUuid();
        long generation = click.generation();
        if (!isClickStillCurrent(uuid, generation)) {
            reply(uuid, ClickOutcome.rejected("stale.generation"), null);
            return;
        }
        if (banknoteFlow != null) {
            BanknoteFlow.GenerationCheck stillValid = () -> isClickStillCurrent(uuid, generation);
            try {
                banknoteFlow.withdraw(uuid, action.currencyId(), action.amount(), stillValid)
                        .whenComplete((outcome, failure) -> reply(uuid,
                                failure != null ? ClickOutcome.rejected("internal.error")
                                        : mapWithdrawOutcome(outcome),
                                failure));
            } catch (Throwable failure) {
                reply(uuid, ClickOutcome.rejected("internal.error"), failure);
            }
            return;
        }
        Player player = playerFor(uuid);
        if (player == null) {
            reply(uuid, ClickOutcome.rejected("no-player"), null);
            return;
        }
        ClickOutcome outcome;
        try {
            outcome = runWithdraw(player, uuid, action);
        } catch (Throwable failure) {
            reply(uuid, ClickOutcome.rejected("internal.error"), failure);
            return;
        }
        reply(uuid, outcome, null);
    }

    private void onDepositClick(@NotNull GuiButtonClick click) {
        UUID uuid = click.playerUuid();
        long generation = click.generation();
        if (!isClickStillCurrent(uuid, generation)) {
            reply(uuid, ClickOutcome.rejected("stale.generation"), null);
            return;
        }
        // Click-time binding: read the main hand on this event thread and
        // clone it immediately, so a later hand swap cannot credit one item
        // while removing another.
        ItemStack held = null;
        Player reader = playerFor(uuid);
        if (reader != null) {
            try {
                held = reader.getInventory().getItemInMainHand();
            } catch (Throwable ignored) {
                held = null;
            }
        }
        ItemStack snapshot = null;
        boolean snapshotFailed = false;
        if (held != null) {
            try {
                ItemStack cloned = held.clone();
                if (cloned == null || cloned == held) {
                    snapshotFailed = true;
                } else {
                    snapshot = cloned;
                }
            } catch (Throwable ignored) {
                snapshotFailed = true;
            }
        }
        if (snapshotFailed) {
            // Silent: the click was cancelled and nothing moved, so there is
            // nothing truthful to report yet.
            return;
        }
        if (banknoteFlow != null) {
            if (snapshot == null) {
                reply(uuid, ClickOutcome.rejected("deposit.no-item"), null);
                return;
            }
            BanknoteFlow.GenerationCheck stillValid = () -> isClickStillCurrent(uuid, generation);
            try {
                banknoteFlow.redeem(uuid, snapshot, BanknoteFlow.Hand.MAIN_HAND,
                                useCase::deposit, stillValid)
                        .whenComplete((outcome, failure) -> reply(uuid,
                                failure != null ? ClickOutcome.rejected("internal.error")
                                        : mapRedeemOutcome(outcome),
                                failure));
            } catch (Throwable failure) {
                reply(uuid, ClickOutcome.rejected("internal.error"), failure);
            }
            return;
        }
        Player player = playerFor(uuid);
        if (player == null) {
            reply(uuid, ClickOutcome.rejected("no-player"), null);
            return;
        }
        ClickOutcome outcome;
        try {
            outcome = depositHeldBanknote(player, uuid, snapshot);
        } catch (Throwable failure) {
            reply(uuid, ClickOutcome.rejected("internal.error"), failure);
            return;
        }
        reply(uuid, outcome, null);
    }

    private void onCloseClick(@NotNull GuiButtonClick click) {
        UUID uuid = click.playerUuid();
        try {
            scope.close(uuid);
        } catch (Throwable ignored) {
            // Local tracking below is still dropped; the click stays silent either way.
        } finally {
            openSessions.remove(uuid);
            players.remove(uuid);
        }
    }

    /**
     * Whether the click's generation is still the live view: the tracked
     * session must match and the scope must still hold the view. A reload
     * rebuild (new generation), a manual close and a scope shutdown all
     * read as stale, so in-flight pipelines refuse or refund instead of
     * delivering under rules that no longer apply.
     */
    boolean isClickStillCurrent(@NotNull UUID playerUuid, long generation) {
        try {
            GuiSession known = openSessions.get(playerUuid);
            if (known == null || known.generation() != generation) {
                return false;
            }
            return scope.viewOf(playerUuid).isPresent();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private Player playerFor(@NotNull UUID playerUuid) {
        Player tracked = players.get(playerUuid);
        if (tracked != null) {
            return tracked;
        }
        try {
            return Bukkit.getPlayer(playerUuid);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void closeQuietly(@NotNull UUID playerUuid) {
        try {
            scope.close(playerUuid);
        } catch (Throwable ignored) {
            // Best-effort: tracking below is still dropped.
        } finally {
            openSessions.remove(playerUuid);
            players.remove(playerUuid);
        }
    }

    /**
     * Tell one click outcome on the player's region thread. Mapping is
     * total: every failure and pending state names its cause, while
     * successes stay silent. Never throws.
     */
    private void reply(@NotNull UUID playerUuid, ClickOutcome outcome, Throwable failure) {
        Reply reply = replyFor(outcome, failure);
        if (reply == null) {
            return;
        }
        Player player = playerFor(playerUuid);
        if (player == null) {
            return;
        }
        Component message;
        try {
            message = messages.renderMessage(reply.key(), reply.vars());
        } catch (Throwable ignored) {
            return;
        }
        if (message == null) {
            return;
        }
        try {
            folia.runForPlayer(player, () -> {
                try {
                    messages.sendChatWithFallback(player, message, null);
                } catch (Throwable ignored) {
                    // Messaging is best-effort; the ledger state is already settled.
                }
            });
        } catch (Throwable ignored) {
            // The region dispatch itself was rejected (shutdown, offline
            // player): the click stayed cancelled, so the player can click again.
        }
    }

    /** Best-effort open/rebuild failure notice: no incompletely assembled view, one clear error. */
    private void tellFailure(Player player) {
        if (player == null) {
            return;
        }
        Component message;
        try {
            message = messages.renderMessage("general.transaction-failed", Map.of());
        } catch (Throwable ignored) {
            return;
        }
        if (message == null) {
            return;
        }
        try {
            folia.runForPlayer(player, () -> {
                try {
                    messages.sendChatWithFallback(player, message, null);
                } catch (Throwable ignored) {
                    // Messaging is best-effort; refusing the open already protected the player.
                }
            });
        } catch (Throwable ignored) {
            // The region dispatch itself was rejected; the open stayed refused either way.
        }
    }

    private ClickOutcome runWithdraw(Player player, UUID playerUuid, BankGuiAction action) {
        if (player.getInventory().firstEmpty() == -1) {
            return ClickOutcome.inventoryFull();
        }
        WithdrawResult result = useCase.withdraw(playerUuid, action.amount(), action.currencyId());
        if (result.success()) {
            player.getInventory().addItem(result.banknote());
            return ClickOutcome.success(result.banknote());
        } else if (result.isInventoryFull()) {
            return ClickOutcome.inventoryFull();
        } else {
            return ClickOutcome.rejected(result.reason());
        }
    }

    /**
     * Redeem the banknote in the player's main hand. The item contract is deliberately the held
     * item: decode and credit happen against exactly what the player is holding when the deposit
     * button is clicked. The item is only touched after the business layer reports a committed
     * credit; every rejection keeps it in hand.
     *
     * <p>A {@code null} expected snapshot falls back to the current hand; a
     * non-null snapshot that no longer matches the hand leaves the current
     * item untouched and yields a typed {@code item.mismatch} outcome.
     */
    private ClickOutcome depositHeldBanknote(Player player, UUID playerUuid, ItemStack expected) {
        ItemStack held = expected;
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

    /**
     * Remove exactly one item from the main hand after a committed credit, but only if the
     * current hand still matches the item that was credited. A swapped item is left untouched
     * and yields {@code item.mismatch}; an emptied hand yields a retained outcome.
     */
    private ClickOutcome removeOneMatchingFromMainHand(Player player, ItemStack expected) {
        ItemStack current = player.getInventory().getItemInMainHand();
        if (current == null || current.getType().isAir() || current.getAmount() <= 0) {
            return ClickOutcome.creditRetained();
        }
        boolean matches;
        if (expected != null && current == expected) {
            matches = true;
        } else if (expected != null) {
            boolean similar;
            try {
                similar = current.isSimilar(expected);
            } catch (Throwable ignored) {
                similar = false;
            }
            matches = similar;
        } else {
            matches = true;
        }
        if (!matches) {
            return ClickOutcome.itemMismatch();
        }
        if (current.getAmount() <= 1) {
            player.getInventory().setItemInMainHand(null);
        } else {
            current.setAmount(current.getAmount() - 1);
            player.getInventory().setItemInMainHand(current);
        }
        return ClickOutcome.credited();
    }

    /**
     * Map a shared-flow withdraw outcome to a click outcome. The flow already
     * delivered the note on the region thread for successes; every other
     * outcome only decides the reply the player receives.
     */
    private ClickOutcome mapWithdrawOutcome(BanknoteFlow.WithdrawOutcome outcome) {
        if (outcome == null) {
            return ClickOutcome.rejected("internal.error");
        }
        if (outcome.isDelivered()) {
            return ClickOutcome.success(outcome.note());
        }
        if (outcome.isRefusedFull()) {
            return ClickOutcome.inventoryFull();
        }
        if (outcome.isRefunded()) {
            if (BanknoteFlow.REASON_INVENTORY_FULL.equals(outcome.reason())) {
                return ClickOutcome.inventoryFull();
            }
            return ClickOutcome.rejected(outcome.reason() == null
                    ? "transaction.failed" : outcome.reason());
        }
        if (outcome.isVoidRefunded()) {
            return ClickOutcome.rejected("delivery.uncertain");
        }
        if (outcome.isPending()) {
            return ClickOutcome.pending(outcome.reason() == null
                    ? BanknoteFlow.PENDING_WITHDRAW_INDETERMINATE : outcome.reason());
        }
        com.smile.aceeconomy.domain.EconomyError error = outcome.error();
        if (error == com.smile.aceeconomy.domain.EconomyError.INSUFFICIENT_FUNDS
                || error == com.smile.aceeconomy.domain.EconomyError.DEBT_LIMIT_EXCEEDED
                || error == com.smile.aceeconomy.domain.EconomyError.DEBT_DISABLED) {
            return ClickOutcome.rejected("insufficient.funds");
        }
        if (error == com.smile.aceeconomy.domain.EconomyError.CURRENCY_NOT_FOUND) {
            return ClickOutcome.rejected("currency.unknown");
        }
        return ClickOutcome.rejected("transaction.failed");
    }

    /**
     * Map a shared-flow redeem outcome to a click outcome. The flow already
     * removed the note on the region thread for clean credits; retained and
     * mismatch outcomes keep the item untouched by construction.
     */
    private ClickOutcome mapRedeemOutcome(BanknoteFlow.RedeemOutcome outcome) {
        if (outcome == null) {
            return ClickOutcome.rejected("internal.error");
        }
        if (outcome.isCredited()) {
            return ClickOutcome.credited();
        }
        if (outcome.isCreditRetained()) {
            return ClickOutcome.creditRetained(outcome.value());
        }
        if (outcome.isItemMismatch()) {
            return ClickOutcome.itemMismatch();
        }
        if (outcome.isPending()) {
            return ClickOutcome.pending(outcome.reason() == null
                    ? BanknoteFlow.PENDING_CREDIT_INDETERMINATE : outcome.reason());
        }
        return ClickOutcome.rejected(outcome.reason() == null
                ? "transaction.failed" : outcome.reason());
    }

    record Reply(String key, java.util.Map<String, Object> vars) {
    }

    static Reply replyFor(ClickOutcome outcome, Throwable failure) {
        if (failure != null) {
            return new Reply("general.transaction-failed", java.util.Map.of());
        }
        if (outcome == null) {
            return new Reply("general.transaction-failed", java.util.Map.of());
        }
        if (outcome.isInventoryFull()) {
            return new Reply("general.inventory-full", java.util.Map.of());
        }
        if (outcome.isItemMismatch()) {
            return new Reply("banknote.redeem-mismatch", java.util.Map.of());
        }
        if (outcome.isCreditRetained()) {
            long value = outcome.retainedValue();
            if (value >= 0) {
                return new Reply("banknote.redeem-retained",
                        java.util.Map.of("amount", Long.toString(value)));
            }
            return new Reply("banknote.redeem-failed", java.util.Map.of());
        }
        if (outcome.isPending()) {
            String reason = outcome.reason() == null ? "" : outcome.reason();
            if (BanknoteFlow.PENDING_CREDIT_INDETERMINATE.equals(reason)) {
                return new Reply("banknote.redeem-pending", java.util.Map.of());
            }
            if (BanknoteFlow.PENDING_SHUTDOWN.equals(reason)) {
                return new Reply("general.transaction-cancelled", java.util.Map.of());
            }
            return new Reply("banknote.withdraw-pending", java.util.Map.of());
        }
        if (outcome.isRejected()) {
            return switch (outcome.reason()) {
                case "insufficient.funds" ->
                        new Reply("economy.insufficient-funds", java.util.Map.of());
                case "replay.detected" ->
                        new Reply("banknote.redeem-replay", java.util.Map.of());
                case "delivery.uncertain" ->
                        new Reply("banknote.delivery-uncertain", java.util.Map.of());
                case "stale.generation", "transaction.cancelled" ->
                        new Reply("general.transaction-cancelled", java.util.Map.of());
                default -> new Reply("general.transaction-failed", java.util.Map.of());
            };
        }
        // Successes and plain allowances stay silent: the click was cancelled
        // and the player sees the GUI state itself.
        return null;
    }

    public static final class OpenOutcome {
        private final GuiSession session;
        private final String errorCode;
        private final String detail;

        private OpenOutcome(GuiSession session, String errorCode, String detail) {
            this.session = session;
            this.errorCode = errorCode;
            this.detail = detail;
        }

        public static OpenOutcome success(GuiSession session) {
            return new OpenOutcome(Objects.requireNonNull(session, "session"), null, null);
        }

        public static OpenOutcome failed(String errorCode, String detail) {
            return new OpenOutcome(null, errorCode, detail);
        }

        public boolean success() {
            return session != null;
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
        private final String kind;
        private final ItemStack banknote;
        private final long retainedValue;
        private final String reason;

        private ClickOutcome(String kind, ItemStack banknote, long retainedValue, String reason) {
            this.kind = kind;
            this.banknote = banknote;
            this.retainedValue = retainedValue;
            this.reason = reason;
        }

        public static ClickOutcome allowed() {
            return new ClickOutcome("allowed", null, -1, null);
        }

        public static ClickOutcome success(ItemStack banknote) {
            return new ClickOutcome("success", banknote, -1, null);
        }

        public static ClickOutcome credited() {
            return new ClickOutcome("credited", null, -1, null);
        }

        public static ClickOutcome creditRetained() {
            return new ClickOutcome("credit-retained", null, -1, null);
        }

        public static ClickOutcome creditRetained(long value) {
            return new ClickOutcome("credit-retained", null, value, null);
        }

        public static ClickOutcome itemMismatch() {
            return new ClickOutcome("item-mismatch", null, -1, null);
        }

        public static ClickOutcome snapshotFailed() {
            return new ClickOutcome("snapshot-failed", null, -1, null);
        }

        public static ClickOutcome inventoryFull() {
            return new ClickOutcome("inventory-full", null, -1, null);
        }

        public static ClickOutcome rejected(String reason) {
            return new ClickOutcome("rejected", null, -1, reason);
        }

        public static ClickOutcome pending(@NotNull String reason) {
            return new ClickOutcome("pending", null, -1, reason);
        }

        public boolean isAllowed() {
            return "allowed".equals(kind);
        }

        public boolean isSuccess() {
            return "success".equals(kind);
        }

        public boolean isCredited() {
            return "credited".equals(kind);
        }

        public boolean isCreditRetained() {
            return "credit-retained".equals(kind);
        }

        public boolean isItemMismatch() {
            return "item-mismatch".equals(kind);
        }

        public boolean isSnapshotFailed() {
            return "snapshot-failed".equals(kind);
        }

        public boolean isInventoryFull() {
            return "inventory-full".equals(kind);
        }

        public boolean isRejected() {
            return "rejected".equals(kind);
        }

        public boolean isPending() {
            return "pending".equals(kind);
        }

        public long retainedValue() {
            return retainedValue;
        }

        public ItemStack banknote() {
            return banknote;
        }

        public String reason() {
            return reason;
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
