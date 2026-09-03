package com.smile.aceeconomy.gui.v2;

import com.smile.acelib.command.CommandException;
import com.smile.acelib.form.FormResponse;
import com.smile.acelib.form.FormSendResult;
import com.smile.acelib.form.FormService;
import com.smile.acelib.form.FormSpec;
import com.smile.acelib.form.FormValue;
import com.smile.aceeconomy.api.v2.EconomyApi;
import com.smile.aceeconomy.commands.v2.AmountParser;
import com.smile.aceeconomy.domain.Amount;
import com.smile.aceeconomy.domain.Currency;
import com.smile.aceeconomy.domain.CurrencyRegistry;
import com.smile.aceeconomy.domain.EconomyResult;
import com.smile.aceeconomy.infrastructure.acelib.ConfigLangAdapter;
import com.smile.aceeconomy.ports.BankGuiUseCase;
import com.smile.aceeconomy.ports.DepositResult;
import com.smile.aceeconomy.ports.FoliaContextExecutor;
import com.smile.aceeconomy.ports.WithdrawResult;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Bedrock native-form bank session. It shares the same {@link BankGuiUseCase}
 * as the chest GUI ({@link V2BankGuiSession}) so no transaction logic is
 * duplicated: every balance change still flows through the use case with its
 * nonce, anti-replay and region guarantees.
 *
 * <p>Flow: home (Simple: balance, deposit, withdraw, close) → withdraw input
 * (Custom: amount text plus currency choice) → confirm (Modal) → execute.
 * Only a {@code VALID} response ever advances; {@code CLOSED} cancels silently
 * or with a short note and {@code INVALID} reports a readable error, both with
 * zero writes.
 *
 * <p>Stale protection is layered on top of the form service: each open binds a
 * monotonic generation, and a response whose generation no longer matches the
 * active entry is dropped before any business call. {@link #invalidateAll()}
 * clears every pending entry so reload, disable or reopen leaves no late
 * response executable. The underlying service additionally guarantees
 * at-most-once delivery on the player region context with zero execution after
 * offline, shutdown or reload.
 *
 * <p>Wired by the production composition root; constructed with collaborators
 * so it is fully unit-testable with fakes.
 */
public final class BankFormSession {

    static final int HOME_DEPOSIT = 0;
    static final int HOME_WITHDRAW = 1;

    private final FormService forms;
    private final FoliaContextExecutor folia;
    private final BankGuiUseCase useCase;
    private final EconomyApi balances;
    private final Supplier<CurrencyRegistry> currencies;
    private final ConfigLangAdapter messages;

    private final AtomicLong openGeneration = new AtomicLong(0);
    private final Map<UUID, Long> active = new ConcurrentHashMap<>();
    private final Map<UUID, PendingWithdraw> pendingWithdraw = new ConcurrentHashMap<>();

    private record PendingWithdraw(long generation, long amount, String currencyId) {}

    public BankFormSession(@Nullable FormService forms,
                           @NotNull FoliaContextExecutor folia,
                           @NotNull BankGuiUseCase useCase,
                           @NotNull EconomyApi balances,
                           @NotNull Supplier<CurrencyRegistry> currencies,
                           @NotNull ConfigLangAdapter messages) {
        this.forms = forms;
        this.folia = Objects.requireNonNull(folia, "folia");
        this.useCase = Objects.requireNonNull(useCase, "useCase");
        this.balances = Objects.requireNonNull(balances, "balances");
        this.currencies = Objects.requireNonNull(currencies, "currencies");
        this.messages = Objects.requireNonNull(messages, "messages");
    }

    /**
     * Open the home form for a Bedrock player. Offline players and an absent
     * form transport fail closed with no writes; the transport-absent case
     * tells the player instead of throwing.
     */
    public void open(@NotNull UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        Player player = Bukkit.getPlayer(playerId);
        if (player == null) {
            return;
        }
        if (forms == null) {
            tell(player, "gui.bank-form-unavailable", Map.of());
            return;
        }
        long generation = openGeneration.incrementAndGet();
        active.put(playerId, generation);
        String title = text("gui.bank-form-home-title", Map.of());
        String content = text("gui.bank-form-home-content",
                Map.of("balance", readBalance(playerId)));
        FormSpec home = FormSpec.simple(title)
                .content(content)
                .button(text("gui.bank-form-home-deposit", Map.of()))
                .button(text("gui.bank-form-home-withdraw", Map.of()))
                .button(text("gui.bank-form-home-close", Map.of()))
                .build();
        try {
            FormSendResult result = forms.sendForm(playerId, home,
                    response -> onHome(playerId, generation, response));
            if (result == FormSendResult.REJECTED) {
                active.remove(playerId, generation);
                tell(player, "gui.bank-form-unavailable", Map.of());
            }
        } catch (IllegalStateException unavailable) {
            active.remove(playerId, generation);
            tell(player, "gui.bank-form-unavailable", Map.of());
        } catch (RuntimeException sendFailure) {
            active.remove(playerId, generation);
            tell(player, "gui.bank-form-unavailable", Map.of());
        }
    }

    private void onHome(UUID playerId, long generation, FormResponse response) {
        if (!isCurrent(playerId, generation) || response == null) {
            return;
        }
        switch (response.status()) {
            case CLOSED -> active.remove(playerId, generation);
            case INVALID -> {
                active.remove(playerId, generation);
                Player player = Bukkit.getPlayer(playerId);
                if (player != null) {
                    tell(player, "gui.bank-form-invalid", Map.of());
                }
            }
            case VALID -> {
                int button = response.clickedButton().orElse(HOME_DEPOSIT + 2);
                if (button == HOME_DEPOSIT) {
                    runDeposit(playerId, generation);
                } else if (button == HOME_WITHDRAW) {
                    sendWithdrawForm(playerId, generation);
                } else {
                    active.remove(playerId, generation);
                }
            }
        }
    }

    private void runDeposit(UUID playerId, long generation) {
        Player player = Bukkit.getPlayer(playerId);
        if (player == null) {
            active.remove(playerId, generation);
            return;
        }
        try {
            folia.runForPlayer(player, () -> {
                if (!isCurrent(playerId, generation)) {
                    return;
                }
                Player current = Bukkit.getPlayer(playerId);
                if (current == null || !current.isOnline()) {
                    active.remove(playerId, generation);
                    return;
                }
                ItemStack held = current.getInventory().getItemInMainHand();
                if (held == null || held.getType().isAir() || held.getAmount() <= 0) {
                    active.remove(playerId, generation);
                    tell(current, "gui.bank-form-deposit-no-item", Map.of());
                    return;
                }
                DepositResult result = useCase.deposit(playerId, held);
                if (!result.success()) {
                    active.remove(playerId, generation);
                    tell(current, "general.transaction-failed", Map.of());
                    return;
                }
                if (held.getAmount() <= 1) {
                    current.getInventory().setItemInMainHand(null);
                } else {
                    held.setAmount(held.getAmount() - 1);
                    current.getInventory().setItemInMainHand(held);
                }
                active.remove(playerId, generation);
                tell(current, "gui.bank-form-deposit-success",
                        Map.of("amount", result.value(), "currency", result.currencyId()));
            });
        } catch (Throwable dispatchFailure) {
            active.remove(playerId, generation);
        }
    }

    private void sendWithdrawForm(UUID playerId, long generation) {
        Player player = Bukkit.getPlayer(playerId);
        if (player == null) {
            active.remove(playerId, generation);
            return;
        }
        List<Currency> snapshot;
        try {
            snapshot = List.copyOf(currencies.get().all());
        } catch (Throwable registryFailure) {
            active.remove(playerId, generation);
            tell(player, "gui.bank-form-unavailable", Map.of());
            return;
        }
        if (snapshot.isEmpty()) {
            active.remove(playerId, generation);
            tell(player, "gui.bank-form-unavailable", Map.of());
            return;
        }
        List<String> options = snapshot.stream()
                .map(currency -> currency.displayName() + " (" + currency.id() + ")")
                .toList();
        FormSpec input = FormSpec.custom(text("gui.bank-form-withdraw-title", Map.of()))
                .input(text("gui.bank-form-withdraw-amount", Map.of()),
                        text("gui.bank-form-withdraw-amount-hint", Map.of()), "")
                .dropdown(text("gui.bank-form-withdraw-currency", Map.of()), options)
                .build();
        try {
            FormSendResult result = forms.sendForm(playerId, input,
                    response -> onWithdrawInput(playerId, generation, snapshot, response));
            if (result == FormSendResult.REJECTED) {
                active.remove(playerId, generation);
                tell(player, "gui.bank-form-unavailable", Map.of());
            }
        } catch (RuntimeException sendFailure) {
            active.remove(playerId, generation);
            tell(player, "gui.bank-form-unavailable", Map.of());
        }
    }

    private void onWithdrawInput(UUID playerId, long generation, List<Currency> snapshot,
                                 FormResponse response) {
        if (!isCurrent(playerId, generation) || response == null) {
            return;
        }
        if (response.status() == com.smile.acelib.form.FormResponseStatus.CLOSED) {
            active.remove(playerId, generation);
            Player player = Bukkit.getPlayer(playerId);
            if (player != null) {
                tell(player, "gui.bank-form-cancelled", Map.of());
            }
            return;
        }
        if (response.status() != com.smile.acelib.form.FormResponseStatus.VALID) {
            active.remove(playerId, generation);
            Player player = Bukkit.getPlayer(playerId);
            if (player != null) {
                tell(player, "gui.bank-form-invalid", Map.of());
            }
            return;
        }
        List<FormValue> values = response.values();
        if (values.size() < 2
                || !(values.get(0) instanceof FormValue.Text amountText)
                || !(values.get(1) instanceof FormValue.Option currencyOption)) {
            active.remove(playerId, generation);
            Player player = Bukkit.getPlayer(playerId);
            if (player != null) {
                tell(player, "gui.bank-form-invalid", Map.of());
            }
            return;
        }
        int index = currencyOption.index();
        if (index < 0 || index >= snapshot.size()) {
            active.remove(playerId, generation);
            Player player = Bukkit.getPlayer(playerId);
            if (player != null) {
                tell(player, "general.unknown-currency", Map.of("currency", String.valueOf(index)));
            }
            return;
        }
        Currency currency = snapshot.get(index);
        Amount amount;
        try {
            amount = AmountParser.parse(messages, amountText.value(), currency.scale());
        } catch (CommandException invalid) {
            active.remove(playerId, generation);
            Player player = Bukkit.getPlayer(playerId);
            if (player != null) {
                String rendered = invalid.getMessage();
                player.sendMessage(rendered == null ? "" : rendered);
            }
            return;
        } catch (RuntimeException invalid) {
            active.remove(playerId, generation);
            Player player = Bukkit.getPlayer(playerId);
            if (player != null) {
                tell(player, "general.invalid-amount",
                        Map.of("amount", amountText.value() == null ? "" : amountText.value()));
            }
            return;
        }
        long longValue;
        try {
            longValue = amount.value().longValueExact();
        } catch (ArithmeticException notIntegral) {
            active.remove(playerId, generation);
            Player player = Bukkit.getPlayer(playerId);
            if (player != null) {
                tell(player, "general.invalid-amount",
                        Map.of("amount", amountText.value() == null ? "" : amountText.value()));
            }
            return;
        }
        pendingWithdraw.put(playerId, new PendingWithdraw(generation, longValue, currency.id()));
        sendConfirm(playerId, generation, longValue, currency);
    }

    private void sendConfirm(UUID playerId, long generation, long amount, Currency currency) {
        Player player = Bukkit.getPlayer(playerId);
        if (player == null) {
            pendingWithdraw.remove(playerId);
            active.remove(playerId, generation);
            return;
        }
        FormSpec confirm = FormSpec.modal(text("gui.bank-form-confirm-title", Map.of()))
                .content(text("gui.bank-form-confirm-content",
                        Map.of("amount", amount, "currency", currency.id())))
                .button1(text("gui.bank-form-confirm-yes", Map.of()))
                .button2(text("gui.bank-form-confirm-no", Map.of()))
                .build();
        try {
            FormSendResult result = forms.sendForm(playerId, confirm,
                    response -> onConfirm(playerId, generation, amount, currency.id(), response));
            if (result == FormSendResult.REJECTED) {
                pendingWithdraw.remove(playerId);
                active.remove(playerId, generation);
                tell(player, "gui.bank-form-unavailable", Map.of());
            }
        } catch (RuntimeException sendFailure) {
            pendingWithdraw.remove(playerId);
            active.remove(playerId, generation);
            tell(player, "gui.bank-form-unavailable", Map.of());
        }
    }

    private void onConfirm(UUID playerId, long generation, long amount, String currencyId,
                           FormResponse response) {
        if (!isCurrent(playerId, generation) || response == null) {
            return;
        }
        PendingWithdraw pending = pendingWithdraw.get(playerId);
        if (pending == null || pending.generation() != generation
                || pending.amount() != amount || !pending.currencyId().equals(currencyId)) {
            return;
        }
        boolean confirmed = response.status() == com.smile.acelib.form.FormResponseStatus.VALID
                && response.clickedButton().orElse(1) == 0;
        if (!confirmed) {
            pendingWithdraw.remove(playerId, pending);
            active.remove(playerId, generation);
            Player player = Bukkit.getPlayer(playerId);
            if (player != null) {
                if (response.status() == com.smile.acelib.form.FormResponseStatus.INVALID) {
                    tell(player, "gui.bank-form-invalid", Map.of());
                } else {
                    tell(player, "gui.bank-form-cancelled", Map.of());
                }
            }
            return;
        }
        Player player = Bukkit.getPlayer(playerId);
        if (player == null) {
            pendingWithdraw.remove(playerId, pending);
            active.remove(playerId, generation);
            return;
        }
        try {
            folia.runForPlayer(player, () -> {
                if (!isCurrent(playerId, generation)) {
                    return;
                }
                PendingWithdraw current = pendingWithdraw.get(playerId);
                if (current == null || current.generation() != generation
                        || current.amount() != amount || !current.currencyId().equals(currencyId)) {
                    return;
                }
                Player live = Bukkit.getPlayer(playerId);
                if (live == null || !live.isOnline()) {
                    pendingWithdraw.remove(playerId, current);
                    active.remove(playerId, generation);
                    return;
                }
                if (live.getInventory().firstEmpty() == -1) {
                    pendingWithdraw.remove(playerId, current);
                    active.remove(playerId, generation);
                    tell(live, "general.inventory-full", Map.of());
                    return;
                }
                WithdrawResult result = useCase.withdraw(playerId, amount, currencyId);
                pendingWithdraw.remove(playerId, current);
                active.remove(playerId, generation);
                if (result.success()) {
                    live.getInventory().addItem(result.banknote());
                    tell(live, "gui.bank-form-withdraw-success",
                            Map.of("amount", amount, "currency", currencyId));
                } else if (result.isInventoryFull()) {
                    tell(live, "general.inventory-full", Map.of());
                } else {
                    tell(live, "general.transaction-failed", Map.of());
                }
            });
        } catch (Throwable dispatchFailure) {
            pendingWithdraw.remove(playerId, pending);
            active.remove(playerId, generation);
        }
    }

    private boolean isCurrent(UUID playerId, long generation) {
        Long current = active.get(playerId);
        return current != null && current == generation;
    }

    private String readBalance(UUID playerId) {
        try {
            CurrencyRegistry registry = currencies.get();
            String defaultId = registry.defaultCurrencyId();
            EconomyResult<Amount> result = balances.getBalance(playerId, defaultId);
            if (result != null && result.isSuccess() && result.value() != null) {
                return result.value().value().toPlainString() + " " + defaultId;
            }
        } catch (Throwable ignored) {
            // Fall through to the not-loaded note below.
        }
        return text("general.account-not-loaded", Map.of());
    }

    private String text(String key, Map<String, Object> vars) {
        try {
            return messages.plainMessage(key, vars);
        } catch (Throwable ignored) {
            return key;
        }
    }

    private void tell(Player player, String key, Map<String, Object> vars) {
        try {
            player.sendMessage(text(key, vars));
        } catch (Throwable ignored) {
            // Messaging is best-effort; the ledger state above is already settled.
        }
    }

    /**
     * Drop every pending form so no pre-reload generation can act under
     * post-reload rules. Late responses find no active entry and are discarded
     * without touching the business layer.
     *
     * @return the number of locally tracked pending entries dropped
     */
    public int invalidateAll() {
        int dropped = active.size();
        active.clear();
        pendingWithdraw.clear();
        openGeneration.incrementAndGet();
        return dropped;
    }
}
