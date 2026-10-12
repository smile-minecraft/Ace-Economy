package com.smile.aceeconomy.commands.v2;

import com.smile.aceeconomy.commands.v2.ports.AdminCommandService;
import com.smile.aceeconomy.commands.v2.ports.BackupCommandService;
import com.smile.aceeconomy.commands.v2.ports.BankCommandService;
import com.smile.aceeconomy.commands.v2.ports.EconomyCommandService;
import com.smile.aceeconomy.commands.v2.ports.HistoryQueryService;
import com.smile.aceeconomy.commands.v2.ports.ImportCommandService;
import com.smile.aceeconomy.commands.v2.ports.LeaderboardQueryService;
import com.smile.aceeconomy.commands.v2.ports.PlayerLookupService;
import com.smile.aceeconomy.commands.v2.ports.RollbackCommandService;
import com.smile.aceeconomy.commands.v2.ports.WithdrawCommandService;
import com.smile.aceeconomy.infrastructure.acelib.ConfigLangAdapter;

import java.util.Objects;

/** Dependencies consumed by the v2 command presentation slice. */
public record CommandServices(
        EconomyCommandService economy,
        PlayerLookupService players,
        WithdrawCommandService withdrawals,
        LeaderboardQueryService leaderboard,
        BankCommandService bank,
        AdminCommandService admin,
        HistoryQueryService history,
        RollbackCommandService rollback,
        BackupCommandService backupRestore,
        ImportCommandService imports,
        ConfigLangAdapter messages,
        com.smile.aceeconomy.operations.security.SecurityControlService securityControl,
        java.util.concurrent.Executor controlExecutor) {

    public CommandServices {
        Objects.requireNonNull(economy, "economy");
        Objects.requireNonNull(players, "players");
        Objects.requireNonNull(withdrawals, "withdrawals");
        Objects.requireNonNull(leaderboard, "leaderboard");
        Objects.requireNonNull(bank, "bank");
        Objects.requireNonNull(admin, "admin");
        Objects.requireNonNull(history, "history");
        Objects.requireNonNull(rollback, "rollback");
        Objects.requireNonNull(backupRestore, "backupRestore");
        // imports, messages, securityControl and controlExecutor may be null
        // in offline unit tests that don't exercise them; production always
        // supplies all four (the executor is the shared IO executor).
    }

    /** Full wiring with a control but the legacy direct executor — used by offline tests. */
    public CommandServices(
            EconomyCommandService economy,
            PlayerLookupService players,
            WithdrawCommandService withdrawals,
            LeaderboardQueryService leaderboard,
            BankCommandService bank,
            AdminCommandService admin,
            HistoryQueryService history,
            RollbackCommandService rollback,
            BackupCommandService backupRestore,
            ImportCommandService imports,
            ConfigLangAdapter messages,
            com.smile.aceeconomy.operations.security.SecurityControlService securityControl) {
        this(economy, players, withdrawals, leaderboard, bank, admin, history, rollback,
                backupRestore, imports, messages, securityControl, Runnable::run);
    }

    /** Full wiring without a control — used by offline tests predating freeze. */
    public CommandServices(
            EconomyCommandService economy,
            PlayerLookupService players,
            WithdrawCommandService withdrawals,
            LeaderboardQueryService leaderboard,
            BankCommandService bank,
            AdminCommandService admin,
            HistoryQueryService history,
            RollbackCommandService rollback,
            BackupCommandService backupRestore,
            ImportCommandService imports,
            ConfigLangAdapter messages) {
        this(economy, players, withdrawals, leaderboard, bank, admin, history, rollback,
                backupRestore, imports, messages, null, Runnable::run);
    }

    /** Legacy constructor without messages — used by offline tests that don't exercise i18n. */
    public CommandServices(
            EconomyCommandService economy,
            PlayerLookupService players,
            WithdrawCommandService withdrawals,
            LeaderboardQueryService leaderboard,
            BankCommandService bank,
            AdminCommandService admin,
            HistoryQueryService history,
            RollbackCommandService rollback,
            BackupCommandService backupRestore,
            ImportCommandService imports) {
        this(economy, players, withdrawals, leaderboard, bank, admin, history, rollback,
                backupRestore, imports, null, null);
    }

    /** Legacy constructor without import/messages — used by offline tests predating import. */
    public CommandServices(
            EconomyCommandService economy,
            PlayerLookupService players,
            WithdrawCommandService withdrawals,
            LeaderboardQueryService leaderboard,
            BankCommandService bank,
            AdminCommandService admin,
            HistoryQueryService history,
            RollbackCommandService rollback,
            BackupCommandService backupRestore) {
        this(economy, players, withdrawals, leaderboard, bank, admin, history, rollback,
                backupRestore, null, null, null);
    }
}
