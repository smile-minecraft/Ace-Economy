package com.smile.aceeconomy.infrastructure.integration.vault;

import net.milkbowl.vault.economy.Economy;

import java.util.Objects;

/** [TEST:P3] 測試用假物件，實作 {@link VaultRegistration}，記錄註冊/反註冊與擁有權。 */
public final class FakeVaultRegistration implements VaultRegistration {

    private Economy registered;
    private int registerCalls;
    private int unregisterCalls;

    @Override
    public void register(Economy provider) {
        registered = Objects.requireNonNull(provider, "provider");
        registerCalls++;
    }

    @Override
    public void unregister(Economy provider) {
        if (registered == provider) {
            registered = null;
        }
        unregisterCalls++;
    }

    @Override
    public boolean isRegistered(Economy provider) {
        return registered == provider;
    }

    public Economy registered() {
        return registered;
    }

    public int registerCalls() {
        return registerCalls;
    }

    public int unregisterCalls() {
        return unregisterCalls;
    }
}
