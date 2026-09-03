package com.smile.aceeconomy.operations;

import com.smile.aceeconomy.domain.Currency;
import com.smile.aceeconomy.domain.CurrencyRegistry;
import com.smile.aceeconomy.infrastructure.operations.CmiParser;
import com.smile.aceeconomy.infrastructure.operations.EssentialsParser;
import com.smile.aceeconomy.infrastructure.operations.ImportPathGate;
import com.smile.aceeconomy.infrastructure.operations.ImportPathRejectedException;
import com.smile.aceeconomy.ports.operations.ImportOptions;
import com.smile.aceeconomy.ports.operations.ImportSource;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Import orchestration: path gate, then currency check, then parse, then
 * either a zero-write preview or a backup-gated apply.
 *
 * <p>Ordering is deliberate. The path gate runs before anything is read; the
 * currency is validated before any backup is taken, so a typo never spends a
 * safety snapshot; the backup runs before any balance write, so a failed
 * snapshot aborts the whole apply. A preview never touches the backup service
 * or the idempotency guard — that guarantee comes from
 * {@code ImportService} and is asserted by tests here.</p>
 */
public final class ImportRunner {

    /** Label of the automatic safety snapshot taken before every apply. */
    public static final String SAFETY_LABEL = "pre-import";

    private final ImportService imports;
    private final BackupRestoreService backup;
    private final Path pluginDataFolder;
    private final CurrencyRegistry currencies;

    public ImportRunner(ImportService imports, BackupRestoreService backup,
                        Path pluginDataFolder, CurrencyRegistry currencies) {
        this.imports = Objects.requireNonNull(imports, "imports");
        this.backup = Objects.requireNonNull(backup, "backup");
        this.pluginDataFolder = Objects.requireNonNull(pluginDataFolder, "pluginDataFolder");
        this.currencies = Objects.requireNonNull(currencies, "currencies");
    }

    /** Zero-write preview: no backup, no balance writes, no idempotency consumption. */
    public ImportOutcome preview(ImportSource source, String userPath, String currencyId) {
        return run(source, userPath, currencyId, true);
    }

    /** Backup-gated apply: a failed safety snapshot aborts before any write. */
    public ImportOutcome apply(ImportSource source, String userPath, String currencyId) {
        return run(source, userPath, currencyId, false);
    }

    private ImportOutcome run(ImportSource source, String userPath, String currencyId, boolean dryRun) {
        if (source == null) {
            throw new ImportException(ImportFailureReason.SOURCE_UNKNOWN,
                    "unknown import source; supported: essentials, cmi");
        }
        ImportPathGate.GatedImport gated;
        try {
            // The gate identity travels with the path into the parser, which
            // re-verifies it before reading: a file, directory, or symlink
            // swapped in after this check is refused, never parsed.
            gated = ImportPathGate.gate(pluginDataFolder, userPath, source);
        } catch (ImportPathRejectedException e) {
            throw new ImportException(ImportFailureReason.PATH_REJECTED, e.getMessage(), e);
        }
        String normalizedCurrency = Currency.normalizeId(currencyId);
        if (normalizedCurrency.isEmpty() || !currencies.contains(normalizedCurrency)) {
            throw new ImportException(ImportFailureReason.CURRENCY_UNKNOWN,
                    "unknown currency: " + (currencyId == null ? "" : currencyId.trim()));
        }
        int scale = currencies.get(normalizedCurrency).scale();
        ImportParseResult parsed = source == ImportSource.ESSENTIALS
                ? EssentialsParser.parse(gated, normalizedCurrency, scale)
                : CmiParser.parse(gated, normalizedCurrency, scale);
        if (dryRun) {
            ImportReport report = imports.importRecords(parsed.records(),
                    new ImportOptions(true, true));
            return new ImportOutcome(true, null, report, parsed.failures());
        }
        BackupResult safety = backup.createBackup(SAFETY_LABEL);
        if (!safety.isSuccess()) {
            String detail = safety.message() == null ? "" : safety.message();
            throw new ImportException(ImportFailureReason.BACKUP_FAILED,
                    "pre-import safety snapshot failed; live state untouched — " + detail);
        }
        ImportReport report = imports.importRecords(parsed.records(),
                new ImportOptions(false, true));
        return new ImportOutcome(false, safety.backupId(), report, parsed.failures());
    }
}
