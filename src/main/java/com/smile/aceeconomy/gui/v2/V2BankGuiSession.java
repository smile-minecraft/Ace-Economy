package com.smile.aceeconomy.gui.v2;

import com.smile.acelib.gui.GuiArgument;
import com.smile.acelib.gui.GuiPage;
import com.smile.acelib.gui.GuiResult;
import com.smile.acelib.gui.GuiService;
import com.smile.acelib.gui.GuiSession;
import com.smile.aceeconomy.ports.BankGuiUseCase;
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
 * <p>Wired by the CompositionRoot (Task 12); this class is constructed with its collaborators so it
 * is fully unit-testable with fakes.
 */
public final class V2BankGuiSession {

    private final GuiService guiService;
    private final FoliaContextExecutor folia;
    private final BankGuiUseCase useCase;
    private final Function<Integer, BankGuiAction> actionResolver;

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
     * A valid withdraw is dispatched to the player's region thread via {@link FoliaContextExecutor}.
     */
    public @NotNull ClickOutcome handleClick(@NotNull UUID playerUuid, long generation, int slot) {
        GuiResult validation = guiService.validateClick(playerUuid, generation, slot);
        if (!validation.isAllowed()) {
            return ClickOutcome.rejected(validation.errorCode());
        }
        Player player = players.get(playerUuid);
        if (player == null) {
            return ClickOutcome.rejected("no-player");
        }
        BankGuiAction action = actionResolver.apply(slot);
        if (action == null || action.type() == BankGuiAction.Type.NONE) {
            return ClickOutcome.allowed();
        }
        final ClickOutcome[] outcome = {ClickOutcome.accepted()};
        folia.runForPlayer(player, () -> {
            if (action.type() != BankGuiAction.Type.WITHDRAW) {
                return;
            }
            if (player.getInventory().firstEmpty() == -1) {
                outcome[0] = ClickOutcome.inventoryFull();
                return;
            }
            WithdrawResult r = useCase.withdraw(playerUuid, action.amount());
            if (r.success()) {
                player.getInventory().addItem(r.banknote());
                outcome[0] = ClickOutcome.success(r.banknote());
            } else if (r.isInventoryFull()) {
                outcome[0] = ClickOutcome.inventoryFull();
            } else {
                outcome[0] = ClickOutcome.rejected(r.reason());
            }
        });
        return outcome[0];
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
        private enum Kind { ACCEPTED, ALLOWED, SUCCESS, INVENTORY_FULL, REJECTED }

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

        public static ClickOutcome inventoryFull() {
            return new ClickOutcome(Kind.INVENTORY_FULL, null, null);
        }

        public static ClickOutcome rejected(String reason) {
            return new ClickOutcome(Kind.REJECTED, null, reason);
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

        public boolean isInventoryFull() {
            return kind == Kind.INVENTORY_FULL;
        }

        public boolean isRejected() {
            return kind == Kind.REJECTED;
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
