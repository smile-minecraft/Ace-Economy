package com.smile.aceeconomy.ports.inmemory;

import com.smile.aceeconomy.ports.Clock;

import java.time.Instant;

/** Mutable {@link Clock} for tests that need to advance time (e.g. cache TTL expiry). */
public final class MutableClock implements Clock {

    private Instant now;

    public MutableClock(Instant now) {
        this.now = now;
    }

    public void set(Instant now) {
        this.now = now;
    }

    @Override
    public Instant instant() {
        return now;
    }
}
