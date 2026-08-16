package com.smile.aceeconomy.infrastructure.persistence.sql;

/** v2 schema version contract, shared by every SQL backend. */
final class SchemaVersion {

    static final int CURRENT = 1;

    private SchemaVersion() {
    }

    static boolean isCompatible(int stored) {
        return stored == CURRENT;
    }
}
