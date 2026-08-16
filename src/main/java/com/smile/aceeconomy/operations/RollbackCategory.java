package com.smile.aceeconomy.operations;

/**
 * Category of a transaction being rolled back. Determines how the reversal is constructed:
 *
 * <ul>
 *   <li>{@link #DEPOSIT} — original was a deposit; reverse by withdrawing the same amount.</li>
 *   <li>{@link #WITHDRAW} — original was a withdrawal; reverse by depositing the same amount.</li>
 *   <li>{@link #SET} — original set a balance; reverse by restoring the prior balance.</li>
 *   <li>{@link #TRANSFER} — original was one leg of a transfer; reverse both legs atomically.</li>
 * </ul>
 */
public enum RollbackCategory {
    DEPOSIT,
    WITHDRAW,
    SET,
    TRANSFER
}
