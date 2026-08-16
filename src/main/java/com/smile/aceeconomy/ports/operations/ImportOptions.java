package com.smile.aceeconomy.ports.operations;

/**
 * Options for an import run. {@code dryRun} performs full validation and reports what would happen
 * with zero writes; {@code createMissingAccounts} controls whether an unknown uuid is created.
 */
public final class ImportOptions {

    private final boolean dryRun;
    private final boolean createMissingAccounts;

    public ImportOptions(boolean dryRun, boolean createMissingAccounts) {
        this.dryRun = dryRun;
        this.createMissingAccounts = createMissingAccounts;
    }

    public boolean dryRun() {
        return dryRun;
    }

    public boolean createMissingAccounts() {
        return createMissingAccounts;
    }
}
