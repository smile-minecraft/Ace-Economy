package com.smile.aceeconomy.infrastructure.integration.placeholder;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;

import java.util.Objects;

/** [TEST:P3] 測試用假物件，實作 {@link PlaceholderRegistration}，記錄註冊/反註冊與擁有權。 */
public final class FakePlaceholderRegistration implements PlaceholderRegistration {

    private PlaceholderExpansion registered;
    private int registerCalls;
    private int unregisterCalls;

    @Override
    public void register(PlaceholderExpansion expansion) {
        registered = Objects.requireNonNull(expansion, "expansion");
        registerCalls++;
    }

    @Override
    public void unregister(PlaceholderExpansion expansion) {
        if (registered == expansion) {
            registered = null;
        }
        unregisterCalls++;
    }

    @Override
    public boolean isRegistered(PlaceholderExpansion expansion) {
        return registered == expansion;
    }

    public PlaceholderExpansion registered() {
        return registered;
    }

    public int registerCalls() {
        return registerCalls;
    }

    public int unregisterCalls() {
        return unregisterCalls;
    }
}
