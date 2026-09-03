package com.smile.aceeconomy.operations;

/** Typed reason for an {@link ImportException}: every fatal import gate fails closed with one of these. */
public enum ImportFailureReason {
    SOURCE_UNKNOWN,
    PATH_REJECTED,
    CURRENCY_UNKNOWN,
    BACKUP_FAILED
}
