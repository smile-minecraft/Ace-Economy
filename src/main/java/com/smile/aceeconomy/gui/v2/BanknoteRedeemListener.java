package com.smile.aceeconomy.gui.v2;

import com.smile.aceeconomy.infrastructure.acelib.ConfigLangAdapter;
import com.smile.aceeconomy.ports.BankGuiUseCase;
import com.smile.aceeconomy.ports.BanknoteClaim;
import com.smile.aceeconomy.ports.BanknoteFactory;
import com.smile.aceeconomy.ports.DepositResult;
import com.smile.aceeconomy.ports.FoliaContextExecutor;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Right-click redemption for v2 banknotes. A right-click holding a recognisable banknote in the
 * main or off hand is credited through the same atomic {@link BankGuiUseCase} path as the bank GUI
 * deposit button, so the durable nonce consumption and the balance credit commit together and a
 * replay can never credit twice.
 *
 * <p>Ordering guarantees:
 *
 * <ul>
 *   <li>Only the main-hand event pass is processed; the off-hand pass of the same click is
 *       ignored, so one click reaches the business layer at most once per hand examined.</li>
 *   <li>The click-time item is cloned immediately on the event thread. The clone is what the
 *       business layer decodes, and removal later requires the live hand to still match it, so a
 *       swap between click and execution never removes the wrong item.</li>
 *   <li>Every Bukkit touch after the event thread (decode of the snapshot, credit, removal and
 *       player feedback) runs inside the player's region context via {@link FoliaContextExecutor}.</li>
 *   <li>Exactly one item is consumed, and only after a committed credit. Any rejection, replay,
 *       snapshot mismatch or unclonable item keeps the physical item untouched.</li>
 * </ul>
 *
 * <p>The interaction event is cancelled as soon as a banknote is accepted for redemption (before
 * the region dispatch runs). Cancelling after the deferred credit would have no effect on the
 * already-processed interaction, while items that are not banknotes return before this point and
 * keep their vanilla and third-party behaviour untouched.
 */
public final class BanknoteRedeemListener implements Listener {

    private final BankGuiUseCase useCase;
    private final BanknoteFactory banknotes;
    private final FoliaContextExecutor folia;
    private final ConfigLangAdapter messages;
    private final Logger audit;

    public BanknoteRedeemListener(@NotNull BankGuiUseCase useCase,
                                  @NotNull BanknoteFactory banknotes,
                                  @NotNull FoliaContextExecutor folia,
                                  @NotNull ConfigLangAdapter messages,
                                  @NotNull Logger audit) {
        this.useCase = Objects.requireNonNull(useCase, "useCase");
        this.banknotes = Objects.requireNonNull(banknotes, "banknotes");
        this.folia = Objects.requireNonNull(folia, "folia");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.audit = Objects.requireNonNull(audit, "audit");
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteract(@NotNull PlayerInteractEvent event) {
        if (event.isCancelled()) {
            return;
        }
        EquipmentSlot hand = event.getHand();
        if (hand != null && hand != EquipmentSlot.HAND) {
            return;
        }
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Player player = event.getPlayer();
        if (player == null) {
            return;
        }
        ItemStack mainHand;
        ItemStack offHand;
        try {
            mainHand = player.getInventory().getItemInMainHand();
            offHand = player.getInventory().getItemInOffHand();
        } catch (Throwable t) {
            return;
        }
        ItemStack target = null;
        boolean fromMainHand = true;
        Optional<BanknoteClaim> decoded = Optional.empty();
        if (isCandidate(mainHand)) {
            decoded = safeDecode(mainHand);
            if (decoded.isPresent()) {
                target = mainHand;
            }
        }
        if (target == null && isCandidate(offHand)) {
            decoded = safeDecode(offHand);
            if (decoded.isPresent()) {
                target = offHand;
                fromMainHand = false;
            }
        }
        if (target == null || decoded.isEmpty()) {
            return;
        }
        ItemStack snapshot;
        try {
            ItemStack cloned = target.clone();
            if (cloned == null || cloned == target) {
                return;
            }
            snapshot = cloned;
        } catch (Throwable t) {
            return;
        }
        event.setCancelled(true);
        BanknoteClaim claim = decoded.get();
        boolean redeemMainHand = fromMainHand;
        folia.runForPlayer(player, () -> redeemInContext(player, claim, snapshot, redeemMainHand));
    }

    private void redeemInContext(Player player, BanknoteClaim claim, ItemStack snapshot, boolean fromMainHand) {
        DepositResult result;
        try {
            result = useCase.deposit(player.getUniqueId(), snapshot);
        } catch (Throwable t) {
            notifyFailed(player);
            return;
        }
        if (!result.success()) {
            notifyFailed(player);
            return;
        }
        ItemStack current = fromMainHand
                ? player.getInventory().getItemInMainHand()
                : player.getInventory().getItemInOffHand();
        if (!isCandidate(current) || !matches(current, snapshot)) {
            audit.log(Level.SEVERE, () -> "AceEconomy banknote credit retained: nonce=" + claim.nonce()
                    + " player=" + player.getUniqueId()
                    + " value=" + claim.value()
                    + " currency=" + claim.currency()
                    + " — hand no longer matches the redeemed note; item left untouched for manual review");
            try {
                messages.sendChatWithFallback(player, messages.renderMessage("banknote.redeem-retained",
                        Map.of("amount", Long.toString(result.value()))), null);
            } catch (Throwable ignored) {
            }
            return;
        }
        if (current.getAmount() <= 1) {
            setHeld(player, fromMainHand, null);
        } else {
            current.setAmount(current.getAmount() - 1);
            setHeld(player, fromMainHand, current);
        }
        try {
            messages.sendChatWithFallback(player, messages.renderMessage("banknote.redeem-success",
                    Map.of("amount", Long.toString(result.value()),
                            "issuer", String.valueOf(claim.issuer()))), null);
        } catch (Throwable ignored) {
        }
    }

    private void notifyFailed(Player player) {
        try {
            messages.sendChatWithFallback(player, messages.renderMessage("banknote.redeem-failed", Map.of()), null);
        } catch (Throwable ignored) {
        }
    }

    private void setHeld(Player player, boolean mainHand, ItemStack stack) {
        if (mainHand) {
            player.getInventory().setItemInMainHand(stack);
        } else {
            player.getInventory().setItemInOffHand(stack);
        }
    }

    private static boolean isCandidate(ItemStack stack) {
        if (stack == null || stack.getAmount() <= 0) {
            return false;
        }
        Material type;
        try {
            type = stack.getType();
        } catch (Throwable t) {
            return false;
        }
        return type != null && !type.isAir();
    }

    private Optional<BanknoteClaim> safeDecode(ItemStack stack) {
        try {
            Optional<BanknoteClaim> decoded = banknotes.decode(stack);
            return decoded == null ? Optional.empty() : decoded;
        } catch (Throwable t) {
            return Optional.empty();
        }
    }

    private static boolean matches(ItemStack current, ItemStack snapshot) {
        if (current == snapshot) {
            return true;
        }
        try {
            return current.isSimilar(snapshot);
        } catch (Throwable t) {
            return false;
        }
    }
}
