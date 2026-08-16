package com.smile.aceeconomy.ports.inmemory;

import com.smile.aceeconomy.ports.Clock;

import java.time.Instant;

/** Fixed {@link Clock} for deterministic tests. */
public final class FixedClock implements Clock {

    private final Instant instant;

    public FixedClock(Instant instant) {
        this.instant = instant;
    }

    public FixedClock() {
        this(Instant.ofEpochMilli(1_700_000_000_000L));
    }

    @Override
    public Instant instant() {
        return instant;
    }
}
