package com.smile.aceeconomy.infrastructure.integration.acelib;

import java.util.Objects;

import javax.annotation.Nullable;

/** [TEST:P3] 測試用假物件，實作 {@link IntegrationModule}，可觀察初始化/關閉並選擇性拋出失敗。 */
public final class FakeIntegrationModule implements IntegrationModule {

    private final String name;
    private final String requiredExternalModule;
    private final boolean throwOnInit;

    private boolean initialized = false;
    private boolean partialWork = false;
    private int initCalls = 0;
    private int shutdownCalls = 0;

    public FakeIntegrationModule(String name, @Nullable String requiredExternalModule, boolean throwOnInit) {
        this.name = Objects.requireNonNull(name, "name");
        this.requiredExternalModule = requiredExternalModule;
        this.throwOnInit = throwOnInit;
    }

    public FakeIntegrationModule(String name, @Nullable String requiredExternalModule) {
        this(name, requiredExternalModule, false);
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    @Nullable
    public String requiredExternalModule() {
        return requiredExternalModule;
    }

    @Override
    public void initialize() {
        initCalls++;
        if (throwOnInit) {
            partialWork = true; // simulate partial initialization before failure
            throw new RuntimeException("simulated init failure");
        }
        initialized = true;
    }

    @Override
    public void shutdown() {
        shutdownCalls++;
        initialized = false;
        partialWork = false;
    }

    @Override
    public boolean isInitialized() {
        return initialized;
    }

    public int initCalls() {
        return initCalls;
    }

    public int shutdownCalls() {
        return shutdownCalls;
    }

    public boolean partialWork() {
        return partialWork;
    }
}
