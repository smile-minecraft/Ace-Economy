package com.smile.aceeconomy.infrastructure.acelib;

import com.smile.acelib.scheduler.SafeScheduler;
import com.smile.aceeconomy.ports.FoliaContextExecutor;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

/**
 * Compile + delegation evidence for the production {@code SafeScheduler} binding. The adapter must
 * route every player/entity/location mutation to the scheduler's region-aware method so it runs on
 * the correct Folia region thread; it must never call a Bukkit API directly.
 */
class SafeSchedulerFoliaContextContractTest {

    @Test
    void delegatesPlayerEntityLocationToSchedulerRegionMethods() {
        SafeScheduler scheduler = mock(SafeScheduler.class);
        FoliaContextExecutor ctx = new SafeSchedulerFoliaContext(scheduler);
        Player player = mock(Player.class);
        Entity entity = mock(Entity.class);
        Location location = mock(Location.class);
        Runnable action = () -> { };

        ctx.runForPlayer(player, action);
        ctx.runForEntity(entity, action);
        ctx.runAtLocation(location, action);
        ctx.runGlobal(action);
        ctx.runAsync(action);

        verify(scheduler).runForPlayer(player, action);
        verify(scheduler).runForEntity(entity, action);
        verify(scheduler).runAtLocation(location, action);
        verify(scheduler).runGlobal(action);
        verify(scheduler).runAsync(action);
        verifyNoMoreInteractions(scheduler);
    }
}
