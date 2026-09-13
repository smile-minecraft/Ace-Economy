package com.smile.aceeconomy.gui.v2;

import com.smile.acelib.gui.GuiSession;

import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Consumer-side click/drag/close wiring for the Java bank GUI. AceLib owns generation/session
 * validation and its own listener, but it never calls consumer business logic, so this listener
 * bridges Bukkit inventory events into the existing {@link V2BankGuiSession} path.
 *
 * <p>Ordering contract: the handlers run with {@code ignoreCancelled = false}, so an event AceLib
 * already cancelled still reaches the action dispatch below. Every bank top-inventory click is
 * cancelled first; only configured action slots are dispatched, unconfigured top slots and drags
 * touching the bank view are swallowed, and plain bottom-inventory clicks pass through untouched
 * (shift-clicks from the bottom are cancelled so items cannot shift-move into the bank view).
 *
 * <p>View guard: an event is treated as a bank event only while
 * {@link V2BankGuiSession#activeSession} reports a session for the player, the open top
 * inventory still matches that session's size and title, <em>and</em> the top inventory
 * object is the one the player-region render bound to that session generation. Object
 * identity is the ownership proof: a same-title/same-size inventory from another plugin
 * is a different object and is ignored completely (no cancel, no dispatch). No business
 * logic lives here; every action runs through {@code V2BankGuiSession}, which keeps the
 * stale generation recheck, the Folia region dispatch and the deposit click-time snapshot.
 *
 * <p>Failed-view guard: a render that painted only partially (or threw) binds the
 * exact failed shell by generation plus inventory identity. Until that view
 * closes, every top click, shift-click into it and drag touching it is
 * cancelled and never dispatched — the shell stays inoperable without ever
 * reaching the business layer.
 *
  * <p>Pending-view guard: an accepted async render whose renderer callback has
  * not run yet binds the exact open shell the same way, captured on the player
  * region. Until the paint converts it into a bound view (or a failed guard),
  * the same clicks and drags are cancelled and never dispatched. Bound, failed
  * and pending are mutually exclusive per generation and all match by identity,
  * so a same-title/same-size foreign inventory never matches any of them.
  *
  * <p>Pending-intent fallback: between the accepted render and the moment the
  * region capture pins the exact shell, no exact guard exists yet. While that
  * intent is active for the event's generation, the same dangerous clicks and
  * drags are cancelled without dispatching — the shell stays fail-closed
  * instead of falling through. The intent applies only while no exact guard
  * exists yet for the generation: once the capture pins the exact shell, a
  * same-title/same-size foreign inventory passes through untouched again.
  * In the narrow intent-only window the intent cannot tell the bank shell
  * apart from such a foreign inventory, so its risky clicks are cancelled
  * too; plain bottom-inventory clicks still pass through.
  *
 * <p>Close handles both listener orderings. When the backend session is still active the
 * close goes through the generation-aware path; when the backend already unlinked its
 * session, the local bookkeeping bound to the closing view is still dropped by identity
 * plus generation, never touching a newer reopen.
 */
public final class BankGuiClickListener implements Listener {

    private final V2BankGuiSession session;

    public BankGuiClickListener(@NotNull V2BankGuiSession session) {
        this.session = Objects.requireNonNull(session, "session");
    }

    @EventHandler(ignoreCancelled = false)
    public void onClick(@NotNull InventoryClickEvent event) {
        // Fail-closed guard resolution: an unreadable clicker, view, session or
        // top below cancels the whole event instead of falling through to
        // default handling. A genuine non-bank view still resolves to null and
        // passes through untouched.
        final Player player;
        final UUID uuid;
        final GuiSession active;
        final Inventory top;
        try {
            player = eventPlayer(event.getWhoClicked());
            if (player == null) {
                return;
            }
            uuid = player.getUniqueId();
            active = bankView(event.getView(), uuid);
            if (active == null) {
                return;
            }
            top = eventTop(event.getView());
            if (top == null) {
                cancelSafely(event);
                return;
            }
        } catch (Throwable t) {
            cancelSafely(event);
            return;
        }
        final boolean bound;
        try {
            bound = session.isBoundView(uuid, active.generation(), top);
        } catch (Throwable t) {
            cancelSafely(event);
            return;
        }
        if (!bound) {
            cancelFailedViewClick(event, uuid, active.generation(), top);
            cancelPendingViewClick(event, uuid, active.generation(), top);
            cancelPendingIntentClick(event, uuid, active.generation(), top);
            return;
        }
        int topSize = active.size();
        int rawSlot;
        try {
            rawSlot = event.getRawSlot();
        } catch (Throwable t) {
            cancelSafely(event);
            return;
        }
        if (rawSlot < 0) {
            // Click outside the inventory window: nothing to protect.
            return;
        }
        if (rawSlot < topSize) {
            // Every bank top click is cancelled first so no item can enter, leave
            // or move inside the bank view; only configured actions dispatch.
            try {
                event.setCancelled(true);
            } catch (Throwable ignored) {
                // The event may still be uncancelled, so no bank business may
                // dispatch from it. Staying put is the fail-closed side.
                return;
            }
            BankGuiAction action;
            try {
                action = session.actionForSlot(rawSlot);
            } catch (Throwable t) {
                return;
            }
            if (action == null || action.type() == BankGuiAction.Type.NONE) {
                return;
            }
            dispatch(player, uuid, active.generation(), rawSlot, action);
            return;
        }
        // Bottom (player inventory) clicks pass through untouched, except a
        // shift-click, which would shift-move the stack into the bank top view.
        boolean shift;
        try {
            shift = event.isShiftClick();
        } catch (Throwable t) {
            cancelSafely(event);
            return;
        }
        if (shift) {
            try {
                event.setCancelled(true);
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * Failed-shell guard for clicks. When the clicked view is the exact failed
     * shell for this generation, cancel every top click and every shift-click
     * that would shift-move a stack into it, and never dispatch bank business.
     * Any other inventory (including a same-title/same-size foreign one) passes
     * through untouched.
     */
    private void cancelFailedViewClick(@NotNull InventoryClickEvent event,
                                       @NotNull UUID uuid, long generation,
                                       @NotNull Inventory top) {
        boolean failed;
        try {
            failed = session.isFailedView(uuid, generation, top);
        } catch (Throwable t) {
            cancelSafely(event);
            return;
        }
        if (!failed) {
            return;
        }
        int topSize;
        try {
            topSize = top.getSize();
        } catch (Throwable t) {
            cancelSafely(event);
            return;
        }
        int rawSlot;
        try {
            rawSlot = event.getRawSlot();
        } catch (Throwable t) {
            cancelSafely(event);
            return;
        }
        if (rawSlot < 0) {
            return;
        }
        if (rawSlot < topSize) {
            try {
                event.setCancelled(true);
            } catch (Throwable ignored) {
            }
            return;
        }
        boolean shift;
        try {
            shift = event.isShiftClick();
        } catch (Throwable t) {
            cancelSafely(event);
            return;
        }
        if (shift) {
            try {
                event.setCancelled(true);
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * Pending-shell guard for clicks. When the clicked view is the exact
     * pending shell for this generation, cancel every top click and every
     * shift-click that would shift-move a stack into it, and never dispatch
     * bank business. Any other inventory (including a same-title/same-size
     * foreign one) passes through untouched.
     */
    private void cancelPendingViewClick(@NotNull InventoryClickEvent event,
                                        @NotNull UUID uuid, long generation,
                                        @NotNull Inventory top) {
        boolean pending;
        try {
            pending = session.isPendingView(uuid, generation, top);
        } catch (Throwable t) {
            cancelSafely(event);
            return;
        }
        if (!pending) {
            return;
        }
        int topSize;
        try {
            topSize = top.getSize();
        } catch (Throwable t) {
            cancelSafely(event);
            return;
        }
        int rawSlot;
        try {
            rawSlot = event.getRawSlot();
        } catch (Throwable t) {
            cancelSafely(event);
            return;
        }
        if (rawSlot < 0) {
            return;
        }
        if (rawSlot < topSize) {
            try {
                event.setCancelled(true);
            } catch (Throwable ignored) {
            }
            return;
        }
        boolean shift;
        try {
            shift = event.isShiftClick();
        } catch (Throwable t) {
            cancelSafely(event);
            return;
        }
        if (shift) {
            try {
                event.setCancelled(true);
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * Pending-intent fallback for clicks. While an accepted render's region
     * capture has not pinned the exact shell yet (no exact guard for this
     * generation), the active intent for this generation still cancels every
     * top click and every shift-click that would shift-move a stack into the
     * bank-sized view, without dispatching bank business. Plain bottom clicks
     * still pass through. Once the capture pins the exact shell, the exact
     * mechanism owns the decision again and a same-title/same-size foreign
     * inventory passes through untouched; only in the narrow intent-only
     * window is such a foreign view's risky clicks cancelled too — the
     * documented cost of staying fail-closed before the exact capture runs.
     */
    private void cancelPendingIntentClick(@NotNull InventoryClickEvent event,
                                          @NotNull UUID uuid, long generation,
                                          @NotNull Inventory top) {
        boolean intent;
        try {
            intent = !session.hasExactGuard(uuid, generation)
                    && session.hasPendingIntent(uuid, generation);
        } catch (Throwable t) {
            cancelSafely(event);
            return;
        }
        if (!intent) {
            return;
        }
        int topSize;
        try {
            topSize = top.getSize();
        } catch (Throwable t) {
            cancelSafely(event);
            return;
        }
        int rawSlot;
        try {
            rawSlot = event.getRawSlot();
        } catch (Throwable t) {
            cancelSafely(event);
            return;
        }
        if (rawSlot < 0) {
            return;
        }
        if (rawSlot < topSize) {
            try {
                event.setCancelled(true);
            } catch (Throwable ignored) {
            }
            return;
        }
        boolean shift;
        try {
            shift = event.isShiftClick();
        } catch (Throwable t) {
            cancelSafely(event);
            return;
        }
        if (shift) {
            try {
                event.setCancelled(true);
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * Dispatch an action-slot click into the session path. Deposit clicks carry the
     * click-time main-hand item read on this event thread; the session clones it
     * immediately into a private snapshot, so a later hand swap cannot credit one item
     * while removing another. Other actions need no item and use the snapshot-free path
     * so an unclonable held item can never block a withdraw or close.
     */
    private void dispatch(Player player, UUID uuid, long generation, int slot, BankGuiAction action) {
        try {
            if (action.type() == BankGuiAction.Type.DEPOSIT) {
                ItemStack held;
                try {
                    held = player.getInventory().getItemInMainHand();
                } catch (Throwable t) {
                    return;
                }
                session.handleClickAsync(uuid, generation, slot, held);
            } else {
                session.handleClickAsync(uuid, generation, slot);
            }
        } catch (Throwable ignored) {
            // Dispatch is best-effort on the event thread; the click stays
            // cancelled above, so at worst the player clicks again.
        }
    }

    @EventHandler(ignoreCancelled = false)
    public void onDrag(@NotNull InventoryDragEvent event) {
        // Fail-closed guard resolution, same contract as clicks: an unreadable
        // clicker, view, session or top cancels the whole drag instead of
        // falling through to default handling.
        final Player player;
        final UUID uuid;
        final GuiSession active;
        final Inventory top;
        try {
            player = eventPlayer(event.getWhoClicked());
            if (player == null) {
                return;
            }
            uuid = player.getUniqueId();
            active = bankView(event.getView(), uuid);
            if (active == null) {
                return;
            }
            top = eventTop(event.getView());
            if (top == null) {
                cancelSafely(event);
                return;
            }
        } catch (Throwable t) {
            cancelSafely(event);
            return;
        }
        final boolean bound;
        try {
            bound = session.isBoundView(uuid, active.generation(), top);
        } catch (Throwable t) {
            cancelSafely(event);
            return;
        }
        if (!bound) {
            cancelFailedViewDrag(event, uuid, active.generation(), top);
            cancelPendingViewDrag(event, uuid, active.generation(), top);
            cancelPendingIntentDrag(event, uuid, active.generation(), top);
            return;
        }
        int topSize = active.size();
        boolean touchesTop;
        try {
            touchesTop = event.getRawSlots().stream().anyMatch(raw -> raw < topSize);
        } catch (Throwable t) {
            cancelSafely(event);
            return;
        }
        if (touchesTop) {
            try {
                event.setCancelled(true);
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * Failed-shell guard for drags. A drag touching the exact failed shell is
     * cancelled; any other inventory passes through untouched. Never dispatches.
     */
    private void cancelFailedViewDrag(@NotNull InventoryDragEvent event,
                                      @NotNull UUID uuid, long generation,
                                      @NotNull Inventory top) {
        boolean failed;
        try {
            failed = session.isFailedView(uuid, generation, top);
        } catch (Throwable t) {
            cancelSafely(event);
            return;
        }
        if (!failed) {
            return;
        }
        int topSize;
        try {
            topSize = top.getSize();
        } catch (Throwable t) {
            cancelSafely(event);
            return;
        }
        boolean touchesTop;
        try {
            touchesTop = event.getRawSlots().stream().anyMatch(raw -> raw < topSize);
        } catch (Throwable t) {
            cancelSafely(event);
            return;
        }
        if (touchesTop) {
            try {
                event.setCancelled(true);
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * Pending-shell guard for drags. A drag touching the exact pending shell
     * is cancelled; any other inventory passes through untouched. Never
     * dispatches.
     */
    private void cancelPendingViewDrag(@NotNull InventoryDragEvent event,
                                       @NotNull UUID uuid, long generation,
                                       @NotNull Inventory top) {
        boolean pending;
        try {
            pending = session.isPendingView(uuid, generation, top);
        } catch (Throwable t) {
            cancelSafely(event);
            return;
        }
        if (!pending) {
            return;
        }
        int topSize;
        try {
            topSize = top.getSize();
        } catch (Throwable t) {
            cancelSafely(event);
            return;
        }
        boolean touchesTop;
        try {
            touchesTop = event.getRawSlots().stream().anyMatch(raw -> raw < topSize);
        } catch (Throwable t) {
            cancelSafely(event);
            return;
        }
        if (touchesTop) {
            try {
                event.setCancelled(true);
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * Pending-intent fallback for drags. While no exact guard exists yet for
     * the generation, a drag touching the bank-sized view is cancelled without
     * dispatching; other drags pass through untouched. Once the capture pins
     * the exact shell, the exact mechanism owns the decision again.
     */
    private void cancelPendingIntentDrag(@NotNull InventoryDragEvent event,
                                         @NotNull UUID uuid, long generation,
                                         @NotNull Inventory top) {
        boolean intent;
        try {
            intent = !session.hasExactGuard(uuid, generation)
                    && session.hasPendingIntent(uuid, generation);
        } catch (Throwable t) {
            cancelSafely(event);
            return;
        }
        if (!intent) {
            return;
        }
        int topSize;
        try {
            topSize = top.getSize();
        } catch (Throwable t) {
            cancelSafely(event);
            return;
        }
        boolean touchesTop;
        try {
            touchesTop = event.getRawSlots().stream().anyMatch(raw -> raw < topSize);
        } catch (Throwable t) {
            cancelSafely(event);
            return;
        }
        if (touchesTop) {
            try {
                event.setCancelled(true);
            } catch (Throwable ignored) {
            }
        }
    }

    @EventHandler(ignoreCancelled = false)
    public void onClose(@NotNull InventoryCloseEvent event) {
        Player player;
        try {
            player = event.getPlayer() instanceof Player p ? p : null;
        } catch (Throwable t) {
            return;
        }
        if (player == null) {
            return;
        }
        UUID uuid;
        Inventory top;
        GuiSession owned;
        try {
            uuid = player.getUniqueId();
            top = eventTop(event.getView());
            owned = bankView(event.getView(), uuid);
        } catch (Throwable t) {
            // Unreadable close: neither the generation-aware close nor the
            // identity cleanup can prove ownership, so do nothing. Closes never
            // dispatch business logic, so staying put is the safe side.
            return;
        }
        if (top == null) {
            return;
        }
        if (owned != null) {
            // The backend session is still active and the closing view passes the
            // size/title sanity. Only the bound, failed-guard, pending-guard or
            // pending-intent view may drive the generation-aware close: an exact
            // guard matches by object identity, so a same-title/same-size
            // inventory from another plugin must not drop our session once the
            // capture pinned the exact shell. In the narrow intent-only window
            // the closing view cannot be told apart, and closing through the
            // intent converges the fail-closed state instead of leaking it.
            boolean bound;
            try {
                bound = session.isBoundView(uuid, owned.generation(), top);
            } catch (Throwable t) {
                return;
            }
            if (!bound) {
                // A failed shell closes through the same generation-aware path:
                // its failed guard and local bookkeeping are dropped
                // conditionally, never touching a newer reopen. A still-pending
                // shell (accepted render, paint not yet run) closes the same
                // way through its pending guard, or through the pending intent
                // while the region capture has not pinned the exact shell yet.
                // A foreign same-title/same-size inventory matches none of the
                // exact guards and is ignored once one exists; only in the
                // narrow intent-only window does it close through the intent.
                boolean failed;
                try {
                    failed = session.isFailedView(uuid, owned.generation(), top);
                } catch (Throwable t) {
                    return;
                }
                if (!failed) {
                    boolean pending;
                    try {
                        pending = session.isPendingView(uuid, owned.generation(), top);
                    } catch (Throwable t) {
                        return;
                    }
                    if (!pending) {
                        boolean intent;
                        try {
                            intent = !session.hasExactGuard(uuid, owned.generation())
                                    && session.hasPendingIntent(uuid, owned.generation());
                        } catch (Throwable t) {
                            return;
                        }
                        if (!intent) {
                            return;
                        }
                    }
                }
            }
            long generation = owned.generation();
            try {
                session.close(uuid, generation);
            } catch (Throwable ignored) {
                // Local bookkeeping below still needs the same conditional cleanup.
            } finally {
                session.noteViewClosed(uuid, generation);
            }
            return;
        }
        // The backend already unlinked its session (its own close listener ran first
        // on this event). Drop the local bookkeeping bound to the closing view by
        // identity plus generation; a newer reopen is a different object and is kept.
        try {
            session.discardViewIfBound(uuid, top);
        } catch (Throwable ignored) {
        }
    }

    private static Player eventPlayer(HumanEntity clicker) {
        try {
            return clicker instanceof Player player ? player : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Best-effort fail-closed cancel for a click or drag whose guards or slot
     * reads failed. Cancelling an already-cancelled event is idempotent, and a
     * Bukkit read that already broke cannot be trusted for a narrower decision,
     * so the whole event is cancelled. Never throws.
     */
    private static void cancelSafely(InventoryClickEvent event) {
        try {
            event.setCancelled(true);
        } catch (Throwable ignored) {
        }
    }

    /** Fail-closed cancel for a drag whose guards or slot reads failed. Never throws. */
    private static void cancelSafely(InventoryDragEvent event) {
        try {
            event.setCancelled(true);
        } catch (Throwable ignored) {
        }
    }

    private static Inventory eventTop(InventoryView view) {
        try {
            return view == null ? null : view.getTopInventory();
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Return the active bank session when {@code view} still looks like that session's bank
     * view: the top inventory size and title must match what the open bound. This is only a
     * sanity pre-check, not the ownership proof — click and drag additionally require the
     * top inventory object to be the one bound to the session generation (see
     * {@link V2BankGuiSession#isBoundView}), so a same-title/same-size inventory from
     * another plugin is still rejected.
     *
     * <p>A genuine non-bank view resolves to {@code null} and passes through untouched.
     * Backend or Bukkit read errors propagate so click/drag callers can cancel
     * fail-closed instead of falling through to default handling.
     */
    private GuiSession bankView(InventoryView view, UUID playerUuid) {
        Optional<GuiSession> active = session.activeSession(playerUuid);
        if (active == null || active.isEmpty()) {
            return null;
        }
        GuiSession owned = active.get();
        if (view == null || view.getTopInventory() == null
                || view.getTopInventory().getSize() != owned.size()) {
            return null;
        }
        String title = view.getTitle();
        if (title == null || !title.equals(owned.title())) {
            return null;
        }
        return owned;
    }
}
