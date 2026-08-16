package com.smile.aceeconomy.infrastructure.integration.acelib;

import java.util.Map;
import java.util.Objects;

/** [TEST:P3] 測試用假物件，實作 {@link ExternalServiceReadiness}，對每個模組回傳固定的 {@link Readiness}。 */
public final class FakeExternalServiceReadiness implements ExternalServiceReadiness {

    private final Map<String, Readiness> mapping;

    public FakeExternalServiceReadiness(Map<String, Readiness> mapping) {
        this.mapping = Objects.requireNonNull(mapping, "mapping");
    }

    @Override
    public Readiness probe(String moduleName) {
        return mapping.getOrDefault(moduleName, Readiness.UNAVAILABLE);
    }
}
