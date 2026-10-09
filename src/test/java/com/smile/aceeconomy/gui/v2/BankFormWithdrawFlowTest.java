package com.smile.aceeconomy.gui.v2;

import com.smile.acelib.form.FormResponse;
import com.smile.acelib.form.FormResponseStatus;
import com.smile.acelib.form.FormSendResult;
import com.smile.acelib.form.FormService;
import com.smile.acelib.form.FormSpec;
import com.smile.acelib.form.FormValue;
import com.smile.aceeconomy.api.v2.EconomyApi;
import com.smile.aceeconomy.api.v2.InMemoryTransactionEventPublisher;
import com.smile.aceeconomy.application.EconomyService;
import com.smile.aceeconomy.domain.Amount;
import com.smile.aceeconomy.domain.Currency;
import com.smile.aceeconomy.domain.CurrencyDisplayHolder;
import com.smile.aceeconomy.domain.CurrencyRegistry;
import com.smile.aceeconomy.domain.DebtPolicy;
import com.smile.aceeconomy.domain.EconomyResult;
import com.smile.aceeconomy.infrastructure.acelib.ConfigLangAdapter;
import com.smile.aceeconomy.infrastructure.banknote.BanknoteFlow;
import com.smile.aceeconomy.infrastructure.item.FakeBanknoteFactory;
import com.smile.aceeconomy.ports.BankGuiUseCase;
import com.smile.aceeconomy.ports.DepositResult;
import com.smile.aceeconomy.ports.FoliaContextExecutor;
import com.smile.aceeconomy.ports.WithdrawResult;
import com.smile.aceeconomy.ports.inmemory.InMemoryAccountRepository;
import com.smile.aceeconomy.ports.persistence.AtomicRedemptionStore;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * The Bedrock form withdraw must use the shared banknote pipeline, never the
 * synchronous use-case path on the region thread. A full inventory refuses
 * before any deduction; a committed deduction that cannot be delivered is
 * compensated; every reply runs on the region thread.
 */
class BankFormWithdrawFlowTest {

    static final class FakeForms implements FormService {
        record Sent(UUID playerId, FormSpec spec) {}

        final List<Sent> sent = new ArrayList<>();
        final List<Consumer<FormResponse>> consumers = new ArrayList<>();

        @Override
        public FormSendResult sendForm(UUID playerId, FormSpec form) {
            sent.add(new Sent(playerId, form));
            return FormSendResult.SENT;
        }

        @Override
        public FormSendResult sendForm(UUID playerId, FormSpec form,
                                       Consumer<FormResponse> onResponse) {
            sent.add(new Sent(playerId, form));
            consumers.add(onResponse);
            return FormSendResult.SENT;
        }

        @Override
        public String getModuleStatus() {
            return "READY";
        }

        @Override
        public void shutdown() {
        }

        void fireLast(FormResponse response) {
            consumers.get(consumers.size() - 1).accept(response);
        }
    }

    /** Region executor that runs both seams synchronously and records UUID dispatches. */
    static final class BothSeamFolia implements FoliaContextExecutor {
        int uuidDispatches;
        int playerDispatches;

        @Override
        public void runForPlayer(@NotNull Player player, @NotNull Runnable action) {
            playerDispatches++;
            action.run();
        }

        @Override
        public void runForEntity(@NotNull Entity entity, @NotNull Runnable action) {
        }

        @Override
        public void runAtLocation(@NotNull Location location, @NotNull Runnable action) {
        }

        @Override
        public void runGlobal(@NotNull Runnable action) {
        }

        @Override
        public void runAsync(@NotNull Runnable action) {
        }

        @Override
        public boolean runForPlayer(@NotNull UUID playerId, @NotNull Consumer<Player> action) {
            uuidDispatches++;
            Player player = Bukkit.getPlayer(playerId);
            if (player == null) {
                return false;
            }
            action.accept(player);
            return true;
        }
    }

    static final class CaptureWatchdogs extends ScheduledThreadPoolExecutor {
        final List<Runnable> armed = new ArrayList<>();

        CaptureWatchdogs() {
            super(0);
        }

        @Override
        public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            armed.add(command);
            return null;
        }
    }

    static final class RecordingUseCase implements BankGuiUseCase {
        int withdrawCalls;
        int depositCalls;

        @Override
        public WithdrawResult withdraw(UUID playerUuid, long amount) {
            return withdraw(playerUuid, amount, null);
        }

        @Override
        public WithdrawResult withdraw(UUID playerUuid, long amount, String currencyId) {
            withdrawCalls++;
            return WithdrawResult.success(Mockito.mock(ItemStack.class));
        }

        @Override
        public DepositResult deposit(UUID playerUuid, ItemStack heldItem) {
            depositCalls++;
            return DepositResult.success(100L, "dollar");
        }
    }

    private MockedStatic<Bukkit> bukkit;
    private final Map<UUID, Player> online = new HashMap<>();

    private FakeForms forms;
    private BothSeamFolia folia;
    private RecordingUseCase useCase;
    private EconomyApi api;
    private EconomyApi balances;
    private ConfigLangAdapter messages;
    private Supplier<CurrencyRegistry> currencies;
    private FakeBanknoteFactory banknotes;
    private CaptureWatchdogs watchdogs;
    private CurrencyRegistry registry;
    private CurrencyDisplayHolder display;

    @BeforeEach
    void setUp() {
        bukkit = Mockito.mockStatic(Bukkit.class);
        bukkit.when(() -> Bukkit.getPlayer(any(UUID.class)))
                .thenAnswer(inv -> online.get(inv.getArgument(0)));

        forms = new FakeForms();
        folia = new BothSeamFolia();
        useCase = new RecordingUseCase();
        api = Mockito.mock(EconomyApi.class);
        balances = Mockito.mock(EconomyApi.class);
        Mockito.when(balances.getBalance(any(UUID.class), anyString())).thenAnswer(inv ->
                EconomyResult.success(Amount.of(new java.math.BigDecimal("123.45"), 2)));
        messages = Mockito.mock(ConfigLangAdapter.class);
        Mockito.when(messages.plainMessage(anyString(), anyMap())).thenAnswer(inv ->
                inv.getArgument(0) + inv.getArgument(1).toString());

        registry = CurrencyRegistry.of(List.of(
                Currency.define("dollar", "Dollar", "$", 2, true)));
        display = new CurrencyDisplayHolder(registry);
        currencies = () -> registry;
        banknotes = new FakeBanknoteFactory();
        watchdogs = new CaptureWatchdogs();
    }

    @AfterEach
    void tearDown() {
        bukkit.close();
    }

    private BankFormSession sessionWithFlow() {
        EconomyService economy = new EconomyService(registry, DebtPolicy.disabled(), Amount.zero(2),
                new InMemoryAccountRepository(), tx -> {
                }, Instant::now, new InMemoryTransactionEventPublisher());
        AtomicRedemptionStore redemptions = Mockito.mock(AtomicRedemptionStore.class);
        BanknoteFlow flow = new BanknoteFlow(api, economy, redemptions, banknotes, display,
                folia, Runnable::run, watchdogs, Duration.ofMillis(200));
        return new BankFormSession(forms, folia, useCase, balances, currencies, messages, flow);
    }

    private Player onlinePlayer() {
        Player player = Mockito.mock(Player.class);
        UUID uuid = UUID.randomUUID();
        Mockito.when(player.getUniqueId()).thenReturn(uuid);
        Mockito.when(player.isOnline()).thenReturn(true);
        PlayerInventory inv = Mockito.mock(PlayerInventory.class);
        Mockito.when(player.getInventory()).thenReturn(inv);
        Mockito.when(inv.firstEmpty()).thenReturn(0);
        Mockito.when(inv.addItem(any(ItemStack.class))).thenReturn(new HashMap<>());
        online.put(uuid, player);
        return player;
    }

    private static FormResponse validButton(int button) {
        return new FormResponse(FormResponseStatus.VALID, button, List.of());
    }

    private static FormResponse customValid(String amountText, int currencyIndex) {
        return new FormResponse(FormResponseStatus.VALID, null,
                List.of(new FormValue.Text(amountText), new FormValue.Option(currencyIndex)));
    }

    @Test
    void withdrawUsesSharedFlowWithoutBlockingUseCase() {
        Player player = onlinePlayer();
        Amount amount = Amount.of(100L, 2);
        Mockito.when(api.withdraw(player.getUniqueId(), "dollar", amount))
                .thenReturn(EconomyResult.success(amount));
        Mockito.when(api.deposit(player.getUniqueId(), "dollar", amount))
                .thenReturn(EconomyResult.success(amount));

        BankFormSession session = sessionWithFlow();
        session.open(player.getUniqueId());
        forms.fireLast(validButton(1));
        forms.fireLast(customValid("100", 0));
        forms.fireLast(validButton(0));

        assertEquals(0, useCase.withdrawCalls,
                "form withdraw must go through the shared flow, never the sync use case");
        verify(api).withdraw(player.getUniqueId(), "dollar", amount);
        verify(player.getInventory()).addItem(any(ItemStack.class));
    }

    @Test
    void fullInventoryRefusesBeforeAnyDeductionThroughFlow() {
        Player player = onlinePlayer();
        Mockito.when(player.getInventory().firstEmpty()).thenReturn(-1);

        BankFormSession session = sessionWithFlow();
        session.open(player.getUniqueId());
        forms.fireLast(validButton(1));
        forms.fireLast(customValid("100", 0));
        forms.fireLast(validButton(0));

        assertEquals(0, useCase.withdrawCalls);
        verify(api, never()).withdraw(any(), any(), any());
        verify(player, Mockito.atLeastOnce()).sendMessage(anyString());
    }
}
