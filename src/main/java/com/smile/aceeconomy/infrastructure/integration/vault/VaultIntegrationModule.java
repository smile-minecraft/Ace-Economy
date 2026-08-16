package com.smile.aceeconomy.infrastructure.integration.vault;

import com.smile.aceeconomy.infrastructure.integration.acelib.IntegrationModule;

import java.util.Objects;

/**
 * Integration module adapter that drives {@link VaultEconomyLifecycle} from the external-service
 * coordinator.
 *
 * <p>The required external module name (e.g. {@code "vault"}) is supplied by the caller so the
 * coordinator can probe AceLib readiness before initializing the provider. When the module is not
 * ready, the coordinator simply skips this module — no provider is registered and no half
 * initialized service is left behind.</p>
 */
public final class VaultIntegrationModule implements IntegrationModule {

    private final String name;
    private final String requiredExternalModule;
    private final VaultEconomyLifecycle lifecycle;

    public VaultIntegrationModule(String name, String requiredExternalModule, VaultEconomyLifecycle lifecycle) {
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
        // Lifecycle.stop() is idempotent and only unregisters the owned provider.
        lifecycle.stop();
    }

    @Override
    public boolean isInitialized() {
        return lifecycle.isRegistered();
    }
}
