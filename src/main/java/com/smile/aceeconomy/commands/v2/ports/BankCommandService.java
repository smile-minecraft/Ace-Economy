package com.smile.aceeconomy.commands.v2.ports;

import java.util.UUID;

/**
 * Opens the bank dashboard for a player. The GUI itself is deferred to a later task; this port
 * marks the boundary so the command layer stays presentation-only.
 */
public interface BankCommandService {

    void open(UUID playerUuid, String playerName);
}
