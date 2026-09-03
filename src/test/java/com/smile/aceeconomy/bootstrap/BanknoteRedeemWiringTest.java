package com.smile.aceeconomy.bootstrap;

import com.smile.acelib.item.ItemIdentity;
import com.smile.aceeconomy.api.v2.EconomyApi;
import com.smile.aceeconomy.api.v2.InMemoryTransactionEventPublisher;
import com.smile.aceeconomy.application.EconomyService;
import com.smile.aceeconomy.domain.Account;
import com.smile.aceeconomy.domain.Amount;
import com.smile.aceeconomy.domain.Currency;
import com.smile.aceeconomy.domain.CurrencyRegistry;
import com.smile.aceeconomy.domain.DebtPolicy;
import com.smile.aceeconomy.domain.Transaction;
import com.smile.aceeconomy.gui.v2.BanknoteRedeemListener;
import com.smile.aceeconomy.infrastructure.acelib.ConfigLangAdapter;
import com.smile.aceeconomy.infrastructure.acelib.DeferredFoliaContext;
import com.smile.aceeconomy.infrastructure.item.BanknoteValidator;
import com.smile.aceeconomy.infrastructure.item.FakeBanknoteFactory;
import com.smile.aceeconomy.ports.BanknoteClaim;
import com.smile.aceeconomy.ports.inmemory.InMemoryAccountRepository;
import com.smile.aceeconomy.ports.inmemory.InMemoryIdempotencyGuard;
import com.smile.aceeconomy.ports.persistence.AtomicRedemptionStore;
import com.smile.aceeconomy.ports.persistence.RedemptionResult;

import net.kyori.adventure.text.Component;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Listener-to-production wiring for right-click banknote redemption: the listener is wired to the
 * real {@code ProductionAdapters.BankUseCase} (full {@link EconomyService} contract with lock,
 * pre-commit event and debt policy) backed by an in-memory atomic store, so two rapid clicks
 * carrying the same nonce credit the balance exactly once and the replayed note stays in hand.
 *
 * <p>No live server is started: Bukkit state is mocked, while every production class on the
 * credit path is real. The note factory stays a test double because real {@link ItemStack}
 * instances cannot be constructed offline; it exercises the same {@code BanknoteFactory} contract
 * the production {@code V2BanknoteFactory} implements.
 */
class BanknoteRedeemWiringTest {

    /** In-memory atomic store: persists account, audit and nonce together under one lock. */
    private static final class MemoryRedemptionStore implements AtomicRedemptionStore {
        private final InMemoryAccountRepository repo;
        private final Set<UUID> consumed = ConcurrentHashMap.newKeySet();
        private final List<Transaction> audits = new CopyOnWriteArrayList<>();
        private final ReentrantLock lock = new ReentrantLock();
        int preparedCalls;

        MemoryRedemptionStore(InMemoryAccountRepository repo) {
            this.repo = repo;
        }

        @Override
        public RedemptionResult redeem(UUID nonce, UUID accountId, String currencyId, Amount amount) {
            throw new UnsupportedOperationException("production path uses redeemPrepared");
        }

        @Override
        public RedemptionResult redeemPrepared(UUID nonce, Account account, Transaction transaction,
                                               DebtPolicy debtPolicy) {
            lock.lock();
            try {
                preparedCalls++;
                if (!consumed.add(nonce)) {
                    return RedemptionResult.replay();
                }
                Account current = repo.load(account.owner()).orElse(null);
                if (current == null) {
                    consumed.remove(nonce);
                    return RedemptionResult.accountMissing();
                }
                repo.save(current, account);
                audits.add(transaction);
                return RedemptionResult.committed(
                        transaction.balanceBefore(), transaction.balanceAfter(), transaction.id());
            } finally {
                lock.unlock();
            }
        }
    }

    private InMemoryAccountRepository repo;
    private MemoryRedemptionStore store;
    private FakeBanknoteFactory banknotes;
    private ConfigLangAdapter messages;
    private Player player;
    private PlayerInventory inv;
    private UUID playerId;
    private ItemStack air;
    private BanknoteRedeemListener listener;
    private DeferredFoliaContext folia;

    @BeforeEach
    void setUp() {
        CurrencyRegistry currencies = CurrencyRegistry.of(List.of(
                Currency.define("dollar", "金幣", "$", 2, true)));
        repo = new InMemoryAccountRepository();
        playerId = UUID.randomUUID();
        repo.create(playerId, "alice", Map.of("dollar", Amount.of(0L, 2)));
        store = new MemoryRedemptionStore(repo);
        banknotes = new FakeBanknoteFactory();
        EconomyService economy = new EconomyService(currencies, DebtPolicy.disabled(), Amount.zero(2),
                repo, tx -> {}, () -> Instant.now(), new InMemoryTransactionEventPublisher());
        ProductionAdapters.BankUseCase useCase = new ProductionAdapters.BankUseCase(
                Mockito.mock(EconomyApi.class), economy, currencies, banknotes,
                new BanknoteValidator(new InMemoryIdempotencyGuard()), store);
        messages = Mockito.mock(ConfigLangAdapter.class);
        Mockito.when(messages.renderMessage(anyString(), anyMap()))
                .thenAnswer(invocation -> Component.text("msg:" + invocation.getArgument(0)));
        player = Mockito.mock(Player.class);
        inv = Mockito.mock(PlayerInventory.class);
        Mockito.when(player.getUniqueId()).thenReturn(playerId);
        Mockito.when(player.getInventory()).thenReturn(inv);
        air = stack(true, 0);
        folia = new DeferredFoliaContext();
        listener = new BanknoteRedeemListener(useCase, banknotes, folia, messages,
                Logger.getLogger("BanknoteRedeemWiringTest"));
    }

    private ItemStack stack(boolean isAir, int amount) {
        Material material = Mockito.mock(Material.class);
        Mockito.when(material.isAir()).thenReturn(isAir);
        ItemStack stack = Mockito.mock(ItemStack.class);
        Mockito.when(stack.getType()).thenReturn(material);
        Mockito.when(stack.getAmount()).thenReturn(amount);
        return stack;
    }

    private ItemStack noteWith(UUID nonce) {
        ItemStack note = stack(false, 1);
        ItemStack snapshot = stack(false, 1);
        Mockito.when(note.clone()).thenReturn(snapshot);
        Mockito.when(note.isSimilar(snapshot)).thenReturn(true);
        BanknoteClaim claim = new BanknoteClaim(
                new ItemIdentity(BanknoteClaim.V2_NAMESPACE, BanknoteClaim.V2_KEY, 2, 0),
                BanknoteClaim.V2_SCHEMA, 100L, UUID.randomUUID(), nonce, "dollar");
        banknotes.register(note, claim);
        banknotes.register(snapshot, claim);
        return note;
    }

    private PlayerInteractEvent interact() {
        PlayerInteractEvent event = Mockito.mock(PlayerInteractEvent.class);
        Mockito.when(event.getHand()).thenReturn(EquipmentSlot.HAND);
        Mockito.when(event.getAction()).thenReturn(Action.RIGHT_CLICK_AIR);
        Mockito.when(event.isCancelled()).thenReturn(false);
        Mockito.when(event.getPlayer()).thenReturn(player);
        return event;
    }

    @Test
    @DisplayName("two rapid clicks with the same nonce credit once and keep the replayed note")
    void sameNonceDoubleClickCreditsOnce() {
        UUID nonce = UUID.randomUUID();
        ItemStack first = noteWith(nonce);
        ItemStack second = noteWith(nonce);
        // Click-time reads on the event thread, then region-thread reads after dispatch.
        Mockito.when(inv.getItemInMainHand()).thenReturn(first, second, first, second);
        Mockito.when(inv.getItemInOffHand()).thenReturn(air);

        listener.onInteract(interact());
        listener.onInteract(interact());
        assertEquals(2, folia.queuedCount(), "both clicks must reach the atomic path for the store to decide");
        folia.flush();

        assertEquals(2, store.preparedCalls, "both attempts reach the atomic store; the store owns replay");
        assertEquals(1, store.consumed.size(), "the nonce is consumed exactly once");
        assertEquals(0, Amount.of(100L, 2).compareTo(
                repo.load(playerId).orElseThrow().balanceOf("dollar")),
                "the balance is credited exactly once");
        verify(inv, times(1)).setItemInMainHand((ItemStack) null);
        verify(second, never()).setAmount(any(int.class));
        verify(messages, times(1)).renderMessage(eq("banknote.redeem-success"), anyMap());
        verify(messages, times(1)).renderMessage(eq("banknote.redeem-failed"), anyMap());
    }
}
