package com.smile.aceeconomy.infrastructure.integration.placeholder;

import com.smile.aceeconomy.infrastructure.integration.acelib.IntegrationModule;

import java.util.Objects;

/**
 * Integration module adapter that drives {@link PlaceholderLifecycle} from the external-service
 * coordinator.
 *
 * <p>The required external module name (e.g. {@code "placeholderapi"}) is supplied by the caller so
 * the coordinator can probe AceLib readiness before registering the expansion. When the module is
 * not ready, the coordinator skips it — no expansion is registered and no half initialized service
 * is left behind.</p>
 */
public final class PlaceholderIntegrationModule implements IntegrationModule {

    private final String name;
    private final String requiredExternalModule;
    private final PlaceholderLifecycle lifecycle;

    public PlaceholderIntegrationModule(String name, String requiredExternalModule, PlaceholderLifecycle lifecycle) {
        this.name = Objects.requireNonNull(name, "name");
        this.requiredExternalModule = requiredExternalModule;
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String requiredExternalModule() {
        return requiredExternalModule;
    }

    @Override
    public void initialize() {
        lifecycle.start();
    }

    @Override
    public void shutdown() {
        lifecycle.stop();
    }

    @Override
    public boolean isInitialized() {
        return lifecycle.isRegistered();
    }
}
