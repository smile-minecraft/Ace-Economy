package com.smile.aceeconomy.listener;

import com.smile.aceeconomy.AceEconomy;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Listens for chat input from players who are awaiting a custom banknote
 * withdrawal amount.
 * <p>
 * When a player Shift-Clicks the Withdraw slot in the BankMenu, their UUID is
 * added
 * to {@link #awaitingInput}. Their next chat message is intercepted, parsed as
 * a number,
 * and dispatched as a withdraw command.
 * </p>
 */
public class BanknoteInputListener implements Listener {

    private final AceEconomy plugin;

    /**
     * Thread-safe set of players currently awaiting custom withdraw input.
     */
    public static final Set<UUID> awaitingInput = ConcurrentHashMap.newKeySet();

    public BanknoteInputListener(AceEconomy plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onAsyncChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        if (!awaitingInput.contains(uuid)) {
            return;
        }

        // Cancel the chat event so the message is not broadcast
        event.setCancelled(true);
        awaitingInput.remove(uuid);

        // Extract raw text from the Component message
        String rawMessage = PlainTextComponentSerializer.plainText()
                .serialize(event.message()).trim();

        // Handle cancel command
        if (rawMessage.equalsIgnoreCase("cancel")) {
            plugin.getMessageManager().send(player, "gui.input-cancel");
            return;
        }

        // Parse the amount
        double amount;
        try {
            amount = Double.parseDouble(rawMessage);
            if (amount <= 0) {
                plugin.getMessageManager().send(player, "gui.input-invalid");
                return;
            }
        } catch (NumberFormatException e) {
            plugin.getMessageManager().send(player, "gui.input-invalid");
            return;
        }

        // Dispatch the withdraw command on the global region scheduler (Folia-safe)
        final double finalAmount = amount;
        Bukkit.getGlobalRegionScheduler().execute(plugin, () -> {
            Bukkit.dispatchCommand(player, "withdraw " + finalAmount);
        });
    }
}
