package com.smile.aceeconomy.bootstrap;

import org.jetbrains.annotations.NotNull;

/**
 * A unit of v2 plugin behaviour with an ordered start and a cleanup stop.
 *
 * <p>Implementations receive a {@link ResourceOwner} on {@link #start(ResourceOwner)} and must
 * register every external resource (scheduler, event registry, service registration) there so the
 * lifecycle can tear them down in reverse order. {@link #stop()} releases module-owned state; the
 * resource owner is closed by the lifecycle after {@link #stop()}.
 */
public interface LifecycleModule {

    @NotNull
    String name();

    void start(@NotNull ResourceOwner resources) throws Exception;

    void stop() throws Exception;
}
