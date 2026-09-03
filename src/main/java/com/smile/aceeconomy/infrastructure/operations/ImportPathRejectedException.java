package com.smile.aceeconomy.infrastructure.operations;

/** The user path failed the import safety gate; nothing was read. */
public final class ImportPathRejectedException extends RuntimeException {

    public ImportPathRejectedException(String message) {
        super(message);
    }
}
