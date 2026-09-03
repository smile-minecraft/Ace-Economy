package com.smile.aceeconomy.gui.v2;

import com.smile.acelib.form.FormResponse;
import com.smile.acelib.form.FormResponseStatus;
import com.smile.acelib.form.FormSendResult;
import com.smile.acelib.form.FormService;
import com.smile.acelib.form.FormSpec;
import com.smile.acelib.form.FormValue;
import com.smile.aceeconomy.api.v2.EconomyApi;
import com.smile.aceeconomy.domain.Amount;
import com.smile.aceeconomy.domain.Currency;
import com.smile.aceeconomy.domain.CurrencyRegistry;
import com.smile.aceeconomy.domain.EconomyResult;
import com.smile.aceeconomy.infrastructure.acelib.ConfigLangAdapter;
import com.smile.aceeconomy.infrastructure.acelib.RecordingFoliaContext;
import com.smile.aceeconomy.ports.BankGuiUseCase;
import com.smile.aceeconomy.ports.DepositResult;
import com.smile.aceeconomy.ports.WithdrawResult;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;

/**
 * Contract tests for the Bedrock native-form bank session.
 *
 * <p>The session shares the same {@link BankGuiUseCase} as the chest GUI but
 * renders through AceLib {@link FormService}: home is a Simple form, withdraw
 * input is a Custom form, and every balance-changing step ends in a Modal
 * confirm. Stale generations (reopen / invalidate / reload) must never reach
 * the business layer.
 */
class BankFormSessionTest {

    /** Deterministic in-memory {@link FormService} with manual response delivery. */
    static final class FakeFormService implements FormService {
        record Sent(UUID playerId, FormSpec spec) {}

        final List<Sent> sent = new ArrayList<>();
        final List<Consumer<FormResponse>> consumers = new ArrayList<>();
        boolean stopped;

        @Override
        public FormSendResult sendForm(UUID playerId, FormSpec form) {
            if (stopped) {
                throw new IllegalStateException("shutdown");
            }
            sent.add(new Sent(playerId, form));
            return FormSendResult.SENT;
        }

        @Override
        public FormSendResult sendForm(UUID playerId, FormSpec form,
                                       Consumer<FormResponse> onResponse) {
            if (stopped) {
                throw new IllegalStateException("shutdown");
            }
            sent.add(new Sent(playerId, form));
            consumers.add(onResponse);
            return FormSendResult.SENT;
        }

        @Override
        public String getModuleStatus() {
            return stopped ? "FAILED" : "READY";
        }

        @Override
        public void shutdown() {
            stopped = true;
        }

        void fireLast(FormResponse response) {
            consumers.get(consumers.size() - 1).accept(response);
        }

        void fireAt(int index, FormResponse response) {
            consumers.get(index).accept(response);
        }
    }

    /** Recording {@link BankGuiUseCase} that keeps the three-arg withdraw currency. */
    static final class RecordingUseCase implements BankGuiUseCase {
        UUID lastWithdrawPlayer;
        long lastWithdrawAmount;
        String lastWithdrawCurrency;
        int withdrawCalls;
        WithdrawResult nextWithdraw;

        UUID lastDepositPlayer;
        ItemStack lastDepositItem;
        int depositCalls;
        DepositResult nextDeposit = DepositResult.success(100L, "dollar");

        @Override
        public WithdrawResult withdraw(UUID playerUuid, long amount) {
            return withdraw(playerUuid, amount, null);
        }

        @Override
        public WithdrawResult withdraw(UUID playerUuid, long amount, String currencyId) {
            lastWithdrawPlayer = playerUuid;
            lastWithdrawAmount = amount;
            lastWithdrawCurrency = currencyId;
            withdrawCalls++;
            if (nextWithdraw != null) {
                return nextWithdraw;
            }
            return WithdrawResult.success(Mockito.mock(ItemStack.class));
        }

        @Override
        public DepositResult deposit(UUID playerUuid, ItemStack heldItem) {
            lastDepositPlayer = playerUuid;
            lastDepositItem = heldItem;
            depositCalls++;
            return nextDeposit;
        }
    }

    private MockedStatic<Bukkit> bukkit;
    private final Map<UUID, Player> online = new HashMap<>();

    private FakeFormService forms;
    private RecordingFoliaContext folia;
    private RecordingUseCase useCase;
    private EconomyApi balances;
    private ConfigLangAdapter messages;
    private Supplier<CurrencyRegistry> currencies;

    @BeforeEach
    void setUp() {
        bukkit = Mockito.mockStatic(Bukkit.class);
        bukkit.when(() -> Bukkit.getPlayer(any(UUID.class)))
                .thenAnswer(inv -> online.get(inv.getArgument(0)));

        forms = new FakeFormService();
        folia = new RecordingFoliaContext();
        useCase = new RecordingUseCase();

        balances = Mockito.mock(EconomyApi.class);
        Mockito.when(balances.getBalance(any(UUID.class), anyString())).thenAnswer(inv ->
                EconomyResult.success(Amount.of(new BigDecimal("123.45"), 2)));

        messages = Mockito.mock(ConfigLangAdapter.class);
        Mockito.when(messages.plainMessage(anyString(), anyMap())).thenAnswer(inv ->
                inv.getArgument(0) + inv.getArgument(1).toString());

        currencies = () -> CurrencyRegistry.of(List.of(
                Currency.define("dollar", "Dollar", "$", 2, true),
                Currency.define("token", "Token", "T", 0, false)));
    }

    @AfterEach
    void tearDown() {
        bukkit.close();
    }

    private BankFormSession session() {
        return new BankFormSession(forms, folia, useCase, balances, currencies, messages);
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

    private ItemStack solidStack(int amount) {
        Material material = Mockito.mock(Material.class);
        Mockito.when(material.isAir()).thenReturn(false);
        ItemStack stack = Mockito.mock(ItemStack.class);
        Mockito.when(stack.getType()).thenReturn(material);
        Mockito.when(stack.getAmount()).thenReturn(amount);
        return stack;
    }

    private static FormResponse validButton(int button) {
        return new FormResponse(FormResponseStatus.VALID, button, List.of());
    }

    private static FormResponse closed() {
        return new FormResponse(FormResponseStatus.CLOSED, null, List.of());
    }

    private static FormResponse invalid() {
        return new FormResponse(FormResponseStatus.INVALID, null, List.of());
    }

    private static FormResponse customValid(String amountText, int currencyIndex) {
        return new FormResponse(FormResponseStatus.VALID, null,
                List.of(new FormValue.Text(amountText), new FormValue.Option(currencyIndex)));
    }

    @Test
    void homeOpenSendsSimpleFormWithBalance() {
        Player player = onlinePlayer();
        session().open(player.getUniqueId());

        assertEquals(1, forms.sent.size());
        FormSpec spec = forms.sent.get(0).spec();
        assertTrue(spec instanceof FormSpec.Simple, "home must be a Simple form");
        FormSpec.Simple home = (FormSpec.Simple) spec;
        assertEquals(3, home.buttons().size());
        Mockito.verify(messages).plainMessage(
                Mockito.eq("gui.bank-form-home-content"), Mockito.argThat(vars ->
                        vars.containsKey("balance")
                                && vars.get("balance").toString().contains("123.45")));
    }

    @Test
    void withdrawNeedsConfirmStepBeforeAnyWrite() {
        Player player = onlinePlayer();
        BankFormSession session = session();
        session.open(player.getUniqueId());

        // Home -> withdraw button (index 1) opens the Custom input form, no write yet.
        forms.fireLast(validButton(1));
        assertEquals(0, useCase.withdrawCalls);
        assertEquals(2, forms.sent.size());
        assertTrue(forms.sent.get(1).spec() instanceof FormSpec.Custom);

        // Valid input opens the Modal confirm, still no write.
        forms.fireLast(customValid("100", 0));
        assertEquals(0, useCase.withdrawCalls);
        assertEquals(3, forms.sent.size());
        assertTrue(forms.sent.get(2).spec() instanceof FormSpec.Modal);

        // Confirm executes exactly once through the shared use case.
        forms.fireLast(validButton(0));
        assertEquals(1, useCase.withdrawCalls);
        assertEquals(player.getUniqueId(), useCase.lastWithdrawPlayer);
        assertEquals(100L, useCase.lastWithdrawAmount);
        assertEquals("dollar", useCase.lastWithdrawCurrency);
        assertTrue(folia.playerCalled(), "inventory mutation must go through the Folia context");
    }

    @Test
    void withdrawInputValidationWritesNothing() {
        Player player = onlinePlayer();
        BankFormSession session = session();
        session.open(player.getUniqueId());
        forms.fireLast(validButton(1));

        String[] badAmounts = {"", "abc", "-5", "0", "10.123", "99999999999999999999"};
        for (String bad : badAmounts) {
            forms.fireLast(customValid(bad, 0));
        }
        // Unknown currency index as well.
        forms.fireLast(customValid("100", 99));

        assertEquals(0, useCase.withdrawCalls);
        assertEquals(2, forms.sent.size(), "invalid input must not advance to confirm");
        Mockito.verify(player, Mockito.atLeastOnce()).sendMessage(anyString());
    }

    @Test
    void closedAndInvalidHomeWriteNothing() {
        Player player = onlinePlayer();
        BankFormSession session = session();
        session.open(player.getUniqueId());
        forms.fireLast(closed());

        session.open(player.getUniqueId());
        forms.fireLast(invalid());

        assertEquals(0, useCase.withdrawCalls);
        assertEquals(0, useCase.depositCalls);
        assertEquals(2, forms.sent.size(), "cancelled homes must not open follow-up forms");
    }

    @Test
    void staleResponseAfterReopenIsDropped() {
        Player player = onlinePlayer();
        BankFormSession session = session();
        session.open(player.getUniqueId());
        session.open(player.getUniqueId());

        // The first home response arrives after the second open: stale, must be dropped.
        forms.fireAt(0, validButton(0));

        assertEquals(0, useCase.depositCalls);
        assertEquals(2, forms.sent.size(), "stale home must not open a follow-up form");
    }

    @Test
    void invalidateAllDropsPendingWithdraw() {
        Player player = onlinePlayer();
        BankFormSession session = session();
        session.open(player.getUniqueId());
        forms.fireLast(validButton(1));
        assertEquals(2, forms.sent.size());

        session.invalidateAll();
        forms.fireLast(customValid("100", 0));

        assertEquals(0, useCase.withdrawCalls);
        assertEquals(2, forms.sent.size(), "post-invalidate response must not reach confirm");
    }

    @Test
    void offlineResponseExecutesNothing() {
        Player player = onlinePlayer();
        UUID uuid = player.getUniqueId();
        BankFormSession session = session();
        session.open(uuid);

        // Player leaves before answering the home form.
        online.remove(uuid);
        Mockito.when(player.isOnline()).thenReturn(false);
        forms.fireLast(validButton(0));

        assertEquals(0, useCase.depositCalls);
        assertEquals(1, forms.sent.size());
    }

    @Test
    void confirmCancelWritesNothing() {
        Player player = onlinePlayer();
        BankFormSession session = session();
        session.open(player.getUniqueId());
        forms.fireLast(validButton(1));
        forms.fireLast(customValid("50", 1));

        forms.fireLast(validButton(1));
        assertEquals(0, useCase.withdrawCalls);

        // A fresh flow whose confirm form is closed is equally silent on the ledger.
        session.open(player.getUniqueId());
        int homeIndex = forms.consumers.size() - 1;
        forms.fireAt(homeIndex, validButton(1));
        int customIndex = forms.consumers.size() - 1;
        forms.fireAt(customIndex, customValid("50", 1));
        forms.fireLast(closed());
        assertEquals(0, useCase.withdrawCalls);
    }

    @Test
    void depositRedeemsHeldNoteThroughSharedUseCase() {
        Player player = onlinePlayer();
        ItemStack held = solidStack(1);
        Mockito.when(player.getInventory().getItemInMainHand()).thenReturn(held);
        BankFormSession session = session();
        session.open(player.getUniqueId());

        forms.fireLast(validButton(0));

        assertEquals(1, useCase.depositCalls);
        assertEquals(player.getUniqueId(), useCase.lastDepositPlayer);
        assertTrue(folia.playerCalled());
        Mockito.verify(player.getInventory()).setItemInMainHand(null);
    }

    @Test
    void depositRejectionKeepsItem() {
        Player player = onlinePlayer();
        ItemStack held = solidStack(2);
        Mockito.when(player.getInventory().getItemInMainHand()).thenReturn(held);
        useCase.nextDeposit = DepositResult.rejected("banknote.invalid");
        BankFormSession session = session();
        session.open(player.getUniqueId());

        forms.fireLast(validButton(0));

        assertEquals(1, useCase.depositCalls);
        Mockito.verify(player.getInventory(), Mockito.never()).setItemInMainHand(any());
        Mockito.verify(player, Mockito.atLeastOnce()).sendMessage(anyString());
    }

    @Test
    void absentTransportFailsClosed() {
        Player player = onlinePlayer();
        BankFormSession session = new BankFormSession(
                null, folia, useCase, balances, currencies, messages);
        session.open(player.getUniqueId());

        assertEquals(0, useCase.withdrawCalls);
        assertEquals(0, useCase.depositCalls);
        Mockito.verify(player).sendMessage(anyString());
    }

    @Test
    void fullInventoryBlocksWithdrawWrite() {
        Player player = onlinePlayer();
        Mockito.when(player.getInventory().firstEmpty()).thenReturn(-1);
        BankFormSession session = session();
        session.open(player.getUniqueId());
        forms.fireLast(validButton(1));
        forms.fireLast(customValid("100", 0));
        forms.fireLast(validButton(0));

        assertEquals(0, useCase.withdrawCalls);
        Mockito.verify(player, Mockito.atLeastOnce()).sendMessage(anyString());
    }

    @Test
    void routerKeepsJavaOnChestAndSendsBedrockToForms() {
        AtomicReference<UUID> chestOpened = new AtomicReference<>();
        AtomicReference<UUID> formOpened = new AtomicReference<>();
        BankOpenRouter router = new BankOpenRouter(
                (uuid, name) -> chestOpened.set(uuid),
                formOpened::set,
                uuid -> uuid.equals(bedrockUuid),
                Runnable::run);

        router.open(javaUuid, "Java");
        router.open(bedrockUuid, "Bedrock");

        assertEquals(javaUuid, chestOpened.get());
        assertEquals(bedrockUuid, formOpened.get());
    }

    private final UUID javaUuid = UUID.randomUUID();
    private final UUID bedrockUuid = UUID.randomUUID();
}
