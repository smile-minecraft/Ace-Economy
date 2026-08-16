package com.smile.aceeconomy.commands.v2;

import java.util.UUID;

/**
 * Immutable value types shared by the v2 command presentation layer.
 *
 * <p>These are presentation-contract DTOs, not domain entities. They carry just enough
 * information for the command handlers to validate input, format replies and route async
 * results without depending on the application/domain internals beyond the public types.</p>
 */
public final class CommandModels {

    private CommandModels() {
    }

    /** Read-only view of a currency for command-layer formatting and amount-scale validation. */
    public record CurrencyInfo(String id, String displayName, String symbol, int scale, boolean isDefault) {
    }

    /** Resolved player identity used as a command target. */
    public record PlayerIdentity(UUID uuid, String name, boolean online) {
    }

    /** Receipt of a successful banknote withdrawal (the physical item is handed out by the port impl). */
    public record WithdrawReceipt(UUID noteId, String issuerName, String currencyId, java.math.BigDecimal value) {
    }

    /** A single leaderboard row. */
    public record LeaderboardEntry(int rank, String name, java.math.BigDecimal balance, String currencyId) {
    }
}
