package com.smile.aceeconomy.acelib;

import com.smile.acelib.AceLibApi;
import com.smile.acelib.event.SafeEventRegistry;
import com.smile.acelib.scheduler.SafeScheduler;
import com.smile.aceeconomy.bootstrap.LifecycleModule;
import com.smile.aceeconomy.bootstrap.ResourceOwner;
import org.jetbrains.annotations.NotNull;

/**
 * Base {@link LifecycleModule} that requires a ready AceLib facade before it can start.
 *
 * <p>On start the current facade is resolved (never cached); if it is not ready the module refuses
 * to start, which lets the lifecycle roll back earlier modules. When ready, a {@link SafeScheduler}
 * and {@link SafeEventRegistry} are created from the facade and their teardown is registered with the
 * module's {@link ResourceOwner}, establishing the owner-cleanup boundary for external resources.
 *
 * <p>Subclasses implement {@link #onStart(ResourceOwner, AceLibApi, SafeScheduler, SafeEventRegistry)}
 * and may override {@link #onStop()}. The scheduler/event teardown is owned by the resource owner and
 * is closed by the lifecycle after {@link #stop()}; the accessor helpers {@link #api()},
 * {@link #scheduler()} and {@link #events()} throw once the module is no longer started.
 */
public abstract class AceLibModule implements LifecycleModule {

    private final AceLibAccess access;
    private AceLibApi api;
    private SafeScheduler scheduler;
    private SafeEventRegistry events;

    protected AceLibModule(@NotNull AceLibAccess access) {
        this.access = access;
    }

    @Override
    public final void start(@NotNull ResourceOwner resources) throws Exception {
        AceLibApi ready = access.resolveReadyApi().orElseThrow(
                () -> new IllegalStateException(name() + ": AceLib facade not ready; module not started"));
        this.api = ready;
        // Create the scheduler first and hand its teardown to the owner immediately, so a later
        // failure (e.g. the event-registry factory throwing) still tears the scheduler down.
        SafeScheduler scheduler = access.createScheduler(ready);
        this.scheduler = scheduler;
        resources.register(scheduler::cancelAll);
        SafeEventRegistry events = access.createEventRegistry(ready);
        this.events = events;
        resources.register(events::unregisterAll);
        onStart(resources, ready, scheduler, events);
    }

    @Override
    public final void stop() throws Exception {
        try {
            onStop();
        } finally {
            this.api = null;
            this.scheduler = null;
            this.events = null;
        }
    }

    protected abstract void onStart(@NotNull ResourceOwner resources,
                                    @NotNull AceLibApi api,
                                    @NotNull SafeScheduler scheduler,
                                    @NotNull SafeEventRegistry events) throws Exception;

    protected void onStop() throws Exception {
    }

    @NotNull
    protected final AceLibApi api() {
        if (api == null) {
            throw new IllegalStateException(name() + " is not started");
        }
        return api;
    }

    @NotNull
    protected final SafeScheduler scheduler() {
        if (scheduler == null) {
            throw new IllegalStateException(name() + " is not started");
        }
        return scheduler;
    }

    @NotNull
    protected final SafeEventRegistry events() {
        if (events == null) {
            throw new IllegalStateException(name() + " is not started");
        }
        return events;
    }
}
