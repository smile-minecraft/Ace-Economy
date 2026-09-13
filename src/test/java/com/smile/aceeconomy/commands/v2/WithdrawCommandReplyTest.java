package com.smile.aceeconomy.commands.v2;

import com.smile.acelib.command.CommandRegistry;
import com.smile.acelib.command.CommandRegistryImpl;
import com.smile.acelib.command.PlayerHandle;
import com.smile.acelib.command.ReplySink;
import com.smile.acelib.command.Sender;
import com.smile.aceeconomy.commands.v2.CommandModels.CurrencyInfo;
import com.smile.aceeconomy.commands.v2.CommandModels.WithdrawReceipt;
import com.smile.aceeconomy.commands.v2.ports.AdminCommandService;
import com.smile.aceeconomy.commands.v2.ports.BackupCommandService;
import com.smile.aceeconomy.commands.v2.ports.BankCommandService;
import com.smile.aceeconomy.commands.v2.ports.EconomyCommandService;
import com.smile.aceeconomy.commands.v2.ports.HistoryQueryService;
import com.smile.aceeconomy.commands.v2.ports.LeaderboardQueryService;
import com.smile.aceeconomy.commands.v2.ports.PlayerLookupService;
import com.smile.aceeconomy.commands.v2.ports.RollbackCommandService;
import com.smile.aceeconomy.commands.v2.ports.WithdrawCommandService;
import com.smile.aceeconomy.domain.Amount;
import com.smile.aceeconomy.domain.EconomyError;
import com.smile.aceeconomy.domain.EconomyResult;
import com.smile.aceeconomy.infrastructure.acelib.ConfigLangAdapter;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression for the live Bedrock {@code /withdraw cash} symptom: the command's success reply
 * must reach the player through the region-safe {@link CommandReply.RegionDispatch} seam (the
 * same path used for every other player reply), so a Floodgate player sees the note receipt
 * instead of the reply being lost on the IO completion thread. A failed withdraw must surface a
 * typed error and never silently succeed.
 */
class WithdrawCommandReplyTest {

    /** Recording seam: resolves a programmed player and runs the action synchronously. */
    private static final class RecordingDispatch implements CommandReply.RegionDispatch {
        Player player;
        final List<UUID> ids = new ArrayList<>();
        final List<Consumer<Player>> actions = new ArrayList<>();

        @Override
        public boolean runForPlayer(UUID playerId, Consumer<Player> action) {
            ids.add(playerId);
            actions.add(action);
            if (player == null) {
                return false;
            }
            action.accept(player);
            return true;
        }
    }

    private final RecordingDispatch dispatch = new RecordingDispatch();

    @BeforeEach
    void installSeam() {
        CommandReply.installRegionDispatch(dispatch);
    }

    @AfterEach
    void uninstallSeam() {
        CommandReply.installRegionDispatch(null);
    }

    private record Harness(CommandRegistry registry, ReplySink sink, Sender sender,
                           PlayerHandle handle, Player player, ConfigLangAdapter messages) {
    }

    private Harness harness(WithdrawCommandService withdrawals) {
        EconomyCommandService economy = mock(EconomyCommandService.class);
        when(economy.defaultCurrencyId()).thenReturn("dollar");
        when(economy.resolveCurrency("dollar")).thenReturn(Optional.of(
                new CurrencyInfo("dollar", "Dollar", "$", 2, true)));
        when(economy.getBalance(any(UUID.class), anyString())).thenReturn(
                CompletableFuture.completedFuture(EconomyResult.success(Amount.of(1000, 2))));

        ConfigLangAdapter messages = mock(ConfigLangAdapter.class);
        CommandServices services = new CommandServices(economy, mock(PlayerLookupService.class),
                withdrawals, mock(LeaderboardQueryService.class), mock(BankCommandService.class),
                mock(AdminCommandService.class), mock(HistoryQueryService.class),
                mock(RollbackCommandService.class), mock(BackupCommandService.class), null, messages);

        ReplySink sink = mock(ReplySink.class);
        CommandRegistryImpl registry = new CommandRegistryImpl(sink);
        V2CommandRegistry.create(services).register(registry);

        UUID playerId = UUID.randomUUID();
        Sender sender = mock(Sender.class);
        when(sender.isPlayer()).thenReturn(true);
        when(sender.hasPermission(anyString())).thenReturn(true);
        PlayerHandle handle = mock(PlayerHandle.class);
        when(handle.getUniqueId()).thenReturn(playerId);
        when(handle.isOnline()).thenReturn(true);
        when(handle.getName()).thenReturn(".Smile666878");
        when(sender.asPlayer()).thenReturn(handle);
        Player player = mock(Player.class);
        dispatch.player = player;

        return new Harness(registry, sink, sender, handle, player, messages);
    }

    @Test
    void successfulWithdrawReplyIsDeliveredOnTheRegionSeam() {
        WithdrawCommandService withdrawals = mock(WithdrawCommandService.class);
        UUID noteId = UUID.randomUUID();
        when(withdrawals.withdraw(any(UUID.class), eq("dollar"), any(Amount.class)))
                .thenReturn(CompletableFuture.completedFuture(EconomyResult.success(
                        new WithdrawReceipt(noteId, "issuer", "dollar", new BigDecimal("100.00")))));
        Harness h = harness(withdrawals);
        Component rendered = Component.text("withdrew 100.00");
        when(h.messages().renderMessage(eq("economy.withdraw-note"), any())).thenReturn(rendered);

        h.registry().dispatch(h.sender(), "withdraw", List.of("cash", "100"));

        assertEquals(1, dispatch.ids.size(), "the reply must be dispatched onto the player region");
        assertEquals(h.handle().getUniqueId(), dispatch.ids.get(0));
        verify(h.messages()).sendChatWithFallback(same(h.player()), same(rendered), isNull());
        verify(h.sink(), never()).sendError(same(h.sender()), any(Throwable.class));
    }

    @Test
    void failedWithdrawSurfacesATypedErrorAndDeliversNoNoteMessage() {
        WithdrawCommandService withdrawals = mock(WithdrawCommandService.class);
        when(withdrawals.withdraw(any(UUID.class), eq("dollar"), any(Amount.class)))
                .thenReturn(CompletableFuture.completedFuture(
                        EconomyResult.failure(EconomyError.INSUFFICIENT_FUNDS, "not enough")));
        Harness h = harness(withdrawals);

        h.registry().dispatch(h.sender(), "withdraw", List.of("cash", "100"));

        verify(h.messages(), never()).sendChatWithFallback(any(), any(), any());
        verify(h.sink()).sendError(same(h.sender()), any(Throwable.class));
    }
}
