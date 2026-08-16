package com.smile.aceeconomy.commands.v2;

import com.smile.aceeconomy.commands.v2.ports.AdminCommandService;
import com.smile.aceeconomy.commands.v2.ports.BankCommandService;
import com.smile.aceeconomy.commands.v2.ports.EconomyCommandService;
import com.smile.aceeconomy.commands.v2.ports.LeaderboardQueryService;
import com.smile.aceeconomy.commands.v2.ports.PlayerLookupService;
import com.smile.aceeconomy.commands.v2.ports.WithdrawCommandService;

import java.util.Objects;

/** Dependencies consumed by the v2 command presentation slice. */
public record CommandServices(
        EconomyCommandService economy,
        PlayerLookupService players,
        WithdrawCommandService withdrawals,
        LeaderboardQueryService leaderboard,
        BankCommandService bank,
        AdminCommandService admin) {

    public CommandServices {
        Objects.requireNonNull(economy, "economy");
        Objects.requireNonNull(players, "players");
        Objects.requireNonNull(withdrawals, "withdrawals");
        Objects.requireNonNull(leaderboard, "leaderboard");
        Objects.requireNonNull(bank, "bank");
        Objects.requireNonNull(admin, "admin");
    }
}
