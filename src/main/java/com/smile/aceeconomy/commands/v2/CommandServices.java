package com.smile.aceeconomy.commands.v2;

import com.smile.aceeconomy.commands.v2.ports.AdminCommandService;
import com.smile.aceeconomy.commands.v2.ports.BackupCommandService;
import com.smile.aceeconomy.commands.v2.ports.BankCommandService;
import com.smile.aceeconomy.commands.v2.ports.EconomyCommandService;
import com.smile.aceeconomy.commands.v2.ports.HistoryQueryService;
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
        ConfigLangAdapter messages) {

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
        // messages may be null in offline unit tests that don't exercise i18n; production always supplies it
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
            BackupCommandService backupRestore) {
        this(economy, players, withdrawals, leaderboard, bank, admin, history, rollback, backupRestore, null);
    }
}
