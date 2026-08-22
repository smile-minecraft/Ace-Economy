package com.smile.aceeconomy.infrastructure.persistence;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Focused contract for storage type selection. Normalization rules are part of the
 * production wiring contract so unknown / mistyped values fail fast with a clear
 * message before any JDBC or Hikari resource is opened.
 */
final class StorageBackendKindTest {

    @Test
    void jsonParses() {
        assertEquals(StorageBackendKind.JSON, StorageBackendKind.fromConfig("json"));
    }

    @Test
    void sqliteParses() {
        assertEquals(StorageBackendKind.SQLITE, StorageBackendKind.fromConfig("sqlite"));
    }

    @Test
    void mysqlParses() {
        assertEquals(StorageBackendKind.MYSQL, StorageBackendKind.fromConfig("mysql"));
    }

    @Test
    void nullDefaultsToJson() {
        assertEquals(StorageBackendKind.JSON, StorageBackendKind.fromConfig(null));
    }

    @Test
    void blankDefaultsToJson() {
        assertEquals(StorageBackendKind.JSON, StorageBackendKind.fromConfig(""));
        assertEquals(StorageBackendKind.JSON, StorageBackendKind.fromConfig("   "));
    }

    @Test
    void whitespaceAndCaseAreNormalized() {
        assertEquals(StorageBackendKind.JSON, StorageBackendKind.fromConfig(" JSON "));
        assertEquals(StorageBackendKind.SQLITE, StorageBackendKind.fromConfig("\tSqlite\n"));
        assertEquals(StorageBackendKind.MYSQL, StorageBackendKind.fromConfig("MySQL"));
    }

    @Test
    void unknownValueFailsFastWithAllValidOptionsInMessage() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> StorageBackendKind.fromConfig("postgres"));
        String msg = ex.getMessage();
        assertTrue(msg.contains("postgres"), "message must echo the bad value: " + msg);
        assertTrue(msg.contains("json"), "message must list json: " + msg);
        assertTrue(msg.contains("sqlite"), "message must list sqlite: " + msg);
        assertTrue(msg.contains("mysql"), "message must list mysql: " + msg);
    }
}