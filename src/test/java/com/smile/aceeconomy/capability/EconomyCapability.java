package com.smile.aceeconomy.capability;

import java.util.UUID;

/**
 * v2 capability contract for the core economy engine.
 *
 * <p>This interface is the STABLE contract that the v2 rewrite must satisfy.
 * It deliberately references NO v1 implementation class, so the capability
 * tests built on top of it do not lock in v1 class names, legacy schema, or the
 * v1 binary API. The current v1 behaviour is adapted through
 * {@link V1CurrencyManagerAdapter} (test-only); in v2, swap in a different
 * adapter and the tests remain valid.</p>
 *
 * <p>Observable economic rules frozen from v1 (the baseline this contract locks):</p>
 * <ul>
 *   <li>Accounts start at a configured start balance.</li>
 *   <li>Deposit / withdraw are atomic transactions on the default currency.</li>
 *   <li>Non-positive amounts are rejected (transaction cancelled).</li>
 *   <li>Withdraw beyond available balance is rejected (or bounded by the debt
 *       limit) and throws {@link InsufficientFundsException}, leaving the balance
 *       unchanged (transaction cancelled).</li>
 *   <li>When negative balance is disabled, balance can never drop below 0.</li>
 *   <li>When negative balance is enabled, balance is bounded by the debt limit.</li>
 *   <li>Currency ids are matched case-insensitively and whitespace-safely.</li>
 * </ul>
 */
public interface EconomyCapability {

    /**
     * Thrown when a withdraw cannot be satisfied by the available balance
     * (minus the debt limit). This is the contract-level "transaction cancelled"
     * signal; the v1 adapter translates the v1 exception into this one so the
     * tests never reference v1 class names.
     */
    class InsufficientFundsException extends RuntimeException {
        public InsufficientFundsException(String message) {
            super(message);
        }
    }

    /** Create an in-memory account for the player at the configured start balance. */
    void createAccount(UUID uuid, String ownerName);

    /** Whether an account exists for the player. */
    boolean hasAccount(UUID uuid);

    /** Balance of the default currency; 0.0 if no account exists. */
    double getBalance(UUID uuid);

    /** Deposit a positive amount. Returns false for non-positive amounts. */
    boolean deposit(UUID uuid, double amount);

    /**
     * Withdraw a positive amount.
     *
     * @throws InsufficientFundsException if the balance (minus debt limit) is
     *                                    insufficient and not forced.
     */
    boolean withdraw(UUID uuid, double amount) throws InsufficientFundsException;

    /** Set absolute balance; rejected if it would violate the debt policy. */
    boolean setBalance(UUID uuid, double amount);

    /** Whether the configured policy allows negative balances (debt). */
    boolean isNegativeBalanceAllowed();

    /** Debt limit for the given player (0.0 when debt is disabled). */
    double getDebtLimit(UUID uuid);

    /** Whether a currency id is recognised (case/whitespace insensitive). */
    boolean currencyExists(String currencyId);

    /** Default currency id. */
    String getDefaultCurrencyId();
}
