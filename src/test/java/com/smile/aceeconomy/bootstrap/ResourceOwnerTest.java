package com.smile.aceeconomy.bootstrap;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Deterministic unit tests for the per-module resource-owner cleanup boundary.
 * No server runtime is required: resources are plain callbacks that record order.
 */
class ResourceOwnerTest {

    @Test
    void resourceOwnerRunsCleanupsInReverseOrderAndOnce() {
        List<String> log = new ArrayList<>();
        ResourceOwner owner = new ResourceOwner();
        owner.register(() -> log.add("r1"));
        owner.register(() -> log.add("r2"));
        owner.register(() -> log.add("r3"));

        owner.close();
        owner.close(); // idempotent

        assertEquals(List.of("r3", "r2", "r1"), log);
    }

    @Test
    void resourceOwnerRejectsRegistrationAfterClose() {
        ResourceOwner owner = new ResourceOwner();
        owner.close();
        assertThrows(IllegalStateException.class, () -> owner.register(() -> {
        }));
        assertTrue(owner.isClosed());
    }

    @Test
    void resourceOwnerRunsAllCleanupsEvenIfOneThrowsAggregatingSuppressed() {
        List<String> log = new ArrayList<>();
        ResourceOwner owner = new ResourceOwner();
        owner.register(() -> log.add("r1"));
        owner.register(() -> {
            throw new RuntimeException("boom-r2");
        });
        owner.register(() -> {
            throw new RuntimeException("boom-r3");
        });

        RuntimeException thrown = assertThrows(RuntimeException.class, owner::close);
        // reverse execution order is r3, r2, r1; r3 is the first to throw -> primary
        assertEquals("boom-r3", thrown.getMessage());
        assertEquals(1, thrown.getSuppressed().length);
        assertEquals("boom-r2", thrown.getSuppressed()[0].getMessage());
        // r1 still ran despite the two throwers
        assertEquals(List.of("r1"), log);
        // close remains idempotent even after a throwing close
        assertTrue(owner.isClosed());
        owner.close();
        assertEquals(List.of("r1"), log);
    }
}
