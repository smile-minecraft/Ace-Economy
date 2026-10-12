package com.smile.aceeconomy.infrastructure.persistence.sql;

import com.smile.aceeconomy.domain.AdminAudit;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reason-length compatibility between the audit policy and the SQL store.
 *
 * <p>The policy accepts a single-word reason of up to
 * {@link AdminAudit#MAX_REASON_LENGTH} characters, and persisted reversal and
 * import records additionally carry a machine prefix in front of it
 * ({@code rollback:<category>}, {@code import:<source>}). The MySQL
 * transactions table declares {@code reason VARCHAR(255)}, which cannot hold
 * even a bare policy-legal reason, let alone a prefixed one. SQLite and JSON
 * use unbounded text and are unaffected.
 *
 * <p>The capacity assertion below is intentionally Red: it pins the required
 * width so the hazard cannot be closed by truncating the operator reason or
 * by lowering the policy. Widening the column touches an existing field and
 * is therefore NOT applied here: under the additive-only migration rule it
 * needs explicit approval, together with a migration story for databases
 * created by older builds (whose narrow column would reject a 256-character
 * insert) and a reader-compatibility check for old builds opening a widened
 * database.
 */
final class AdminAuditMysqlReasonCapacityTest {

    /**
     * Longest machine prefix known to be prepended to a persisted reason:
     * {@code rollback:transfer} (17) and {@code import:ESSENTIALS} (17).
     * The separator between the prefix and the operator reason is still part
     * of the unfinished composition design and is provisionally counted as
     * one character; the next implementor finalizes the exact shape.
     */
    private static final int LONGEST_KNOWN_PREFIX = 17;
    private static final int PROVISIONAL_SEPARATOR = 1;

    private static int mysqlReasonWidth() {
        List<String> ddl = V2Schema.ddlStatements(new MySqlDialect());
        String transactions = ddl.stream()
                .filter(stmt -> stmt.contains(V2Schema.transactionsTable()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("MySQL DDL must create "
                        + V2Schema.transactionsTable()));
        Matcher width = Pattern.compile("reason VARCHAR\\((\\d+)\\)").matcher(transactions);
        assertTrue(width.find(),
                "the MySQL transactions table must declare a reason width: " + transactions);
        return Integer.parseInt(width.group(1));
    }

    @Test
    void policyAcceptsExactlyTheMaximumAndRejectsOneMore() {
        String exactlyMax = "r".repeat(AdminAudit.MAX_REASON_LENGTH);
        assertEquals(exactlyMax, AdminAudit.validateReason(exactlyMax),
                "a reason of exactly the policy maximum must pass through unchanged");
        assertThrows(IllegalArgumentException.class,
                () -> AdminAudit.validateReason(exactlyMax + "r"),
                "a reason one character over the policy maximum must be rejected");
    }

    @Test
    void sqliteTransactionsReasonStaysUnbounded() {
        List<String> ddl = V2Schema.ddlStatements(new SqliteDialect());
        String transactions = ddl.stream()
                .filter(stmt -> stmt.contains(V2Schema.transactionsTable()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("SQLite DDL must create "
                        + V2Schema.transactionsTable()));
        assertTrue(transactions.contains("reason TEXT"),
                "SQLite must keep the reason unbounded: " + transactions);
    }

    @Test
    void mysqlTransactionsReasonColumnMustHoldPolicyMaxPlusMachinePrefix() {
        int required = AdminAudit.MAX_REASON_LENGTH + LONGEST_KNOWN_PREFIX + PROVISIONAL_SEPARATOR;
        int actual = mysqlReasonWidth();
        assertTrue(actual >= required,
                "MySQL reason column is VARCHAR(" + actual + ") but must hold a "
                        + AdminAudit.MAX_REASON_LENGTH + "-character policy-legal reason plus a "
                        + LONGEST_KNOWN_PREFIX + "-character machine prefix and separator (needs "
                        + required + "). Do NOT fix this by truncating the operator reason or by "
                        + "lowering the policy: widening the column needs approval under the "
                        + "additive-only migration rule, with a migration story for databases "
                        + "created by older builds.");
    }
}
