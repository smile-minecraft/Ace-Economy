package com.smile.aceeconomy.ports.operations;

/**
 * Identity of an external economy plugin whose balances are being imported. Used as part of the
 * per-record idempotency key so a rerun of the same source record is detected as a duplicate
 * rather than re-applied.
 */
public enum ImportSource {
    ESSENTIALS,
    CMI
}
