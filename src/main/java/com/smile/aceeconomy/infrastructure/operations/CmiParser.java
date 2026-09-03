package com.smile.aceeconomy.infrastructure.operations;

import com.smile.aceeconomy.domain.Amount;
import com.smile.aceeconomy.operations.ImportParseResult;
import com.smile.aceeconomy.ports.operations.ImportRecord;
import com.smile.aceeconomy.ports.operations.ImportSource;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Parses the v1 CMI input: an operator-prepared UTF-8 balance sheet, one
 * record per line as {@code uuid,name,balance} with an optional header row.
 * Comment lines ({@code #}) and blank lines are skipped. The raw CMI SQLite
 * database ({@code cmi.sqlite.db}) is binary and unsupported — the path gate
 * rejects it before this parser ever runs.
 *
 * <p>Malformed lines become per-line failures with line numbers and never
 * silently shift columns. A missing name stays {@code null} so
 * {@code ImportService} can fall back to the uuid string; negative balances
 * stay in the record so the service can fail them per-record.</p>
 */
public final class CmiParser {

    /** Upper bound on parsed records per call; larger inputs fail closed. */
    static final int MAX_RECORDS = 100_000;

    private CmiParser() {
    }

    /**
     * Parse a single balance-sheet file or a directory of them
     * ({@code .csv}/{@code .txt}, sorted by name; symlinks skipped).
     *
     * @param root file or directory that already passed the path gate
     * @param currencyId currency id stamped onto every record
     * @param scale currency scale used to build amounts
     */
    public static ImportParseResult parse(Path root, String currencyId, int scale) {
        ImportPathGate.GatedImport baseline;
        try {
            baseline = ImportPathGate.snapshot(root, display(root));
        } catch (ImportPathRejectedException e) {
            return new ImportParseResult(List.of(), List.of(display(root) + ": " + e.getMessage()));
        }
        return parse(baseline, currencyId, scale);
    }

    /**
     * Parse a gate-approved path, refusing anything the gate approved but that
     * changed before the read: a replaced file or directory, or a file turned
     * into a directory or a symlink, yields failures instead of records.
     *
     * @param gated gate identity captured by {@link ImportPathGate#gate}
     */
    public static ImportParseResult parse(ImportPathGate.GatedImport gated, String currencyId, int scale) {
        List<ImportRecord> records = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        List<Path> files;
        try {
            files = collect(gated, failures);
        } catch (ImportPathRejectedException e) {
            failures.add(display(gated.path()) + ": " + e.getMessage());
            return new ImportParseResult(records, failures);
        }
        Path rootReal;
        try {
            rootReal = gated.path().toRealPath();
        } catch (IOException e) {
            failures.add(display(gated.path()) + ": cannot resolve import path; refusing to read");
            return new ImportParseResult(records, failures);
        }
        for (Path file : files) {
            if (records.size() >= MAX_RECORDS) {
                failures.add(display(gated.path()) + ": too many records (max " + MAX_RECORDS + ")");
                break;
            }
            parseFile(gated, rootReal, file, currencyId, scale, records, failures);
        }
        return new ImportParseResult(records, failures);
    }

    private static List<Path> collect(ImportPathGate.GatedImport gated, List<String> failures) {
        Path root = gated.path();
        if (gated.directory()) {
            return ImportPathGate.listMembersSecure(gated, display(root)).stream()
                    .filter(path -> !Files.isSymbolicLink(path))
                    .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> {
                        String ext = ImportPathGate.extensionOf(
                                path.getFileName().toString().toLowerCase(java.util.Locale.ROOT));
                        return ext.equals("csv") || ext.equals("txt");
                    })
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
        }
        return List.of(root);
    }

    private static void parseFile(ImportPathGate.GatedImport gated, Path rootReal, Path file,
                                  String currencyId, int scale,
                                  List<ImportRecord> records, List<String> failures) {
        Path root = gated.path();
        String name = display(root, file.getFileName().toString());
        String content;
        try {
            if (!gated.directory() && file.equals(gated.path())) {
                content = ImportPathGate.readRootFileSecure(gated, name);
            } else {
                content = ImportPathGate.readMemberSecure(rootReal, file, name);
            }
        } catch (ImportPathRejectedException e) {
            failures.add(name + ": " + e.getMessage());
            return;
        } catch (IOException e) {
            failures.add(name + ": cannot read file (" + e.getMessage() + ")");
            return;
        }
        List<String> lines = new ArrayList<>();
        for (String raw : content.split("\n", -1)) {
            lines.add(raw.endsWith("\r") ? raw.substring(0, raw.length() - 1) : raw);
        }
        int lineNumber = 0;
        for (String raw : lines) {
            lineNumber++;
            String line = raw.endsWith("\r") ? raw.substring(0, raw.length() - 1) : raw;
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.charAt(0) == '#') {
                continue;
            }
            if (lineNumber == 1 && isHeader(trimmed)) {
                continue;
            }
            if (records.size() >= MAX_RECORDS) {
                failures.add(name + ": too many records (max " + MAX_RECORDS + ")");
                break;
            }
            parseLine(name, lineNumber, trimmed, currencyId, scale, records, failures);
        }
    }

    private static boolean isHeader(String line) {
        List<String> cells = split(line);
        return !cells.isEmpty() && cells.get(0).trim().equalsIgnoreCase("uuid");
    }

    private static void parseLine(String file, int lineNumber, String line, String currencyId, int scale,
                                  List<ImportRecord> records, List<String> failures) {
        List<String> cells = split(line);
        if (cells.size() != 2 && cells.size() != 3) {
            failures.add(file + " line " + lineNumber + ": expected uuid,name,balance");
            return;
        }
        String uuidText = cells.get(0).trim();
        String ownerText = cells.size() == 3 ? cells.get(1).trim() : "";
        String balanceText = cells.get(cells.size() - 1).trim();
        UUID accountUuid;
        try {
            accountUuid = UUID.fromString(uuidText);
        } catch (IllegalArgumentException e) {
            failures.add(file + " line " + lineNumber + ": invalid uuid '" + abbreviate(uuidText) + "'");
            return;
        }
        BigDecimal balance;
        try {
            balance = new BigDecimal(balanceText);
        } catch (NumberFormatException e) {
            failures.add(file + " line " + lineNumber + ": invalid balance '" + abbreviate(balanceText) + "'");
            return;
        }
        Amount amount;
        try {
            amount = Amount.of(balance, scale);
        } catch (IllegalArgumentException e) {
            failures.add(file + " line " + lineNumber + ": balance does not fit currency scale");
            return;
        }
        String owner = ownerText.isEmpty() ? null : ownerText;
        records.add(new ImportRecord(ImportSource.CMI, accountUuid.toString(),
                accountUuid, owner, currencyId, amount));
    }

    /** Minimal comma splitter with double-quote support ("" escapes a quote). */
    static List<String> split(String line) {
        List<String> cells = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        current.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    current.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                cells.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        cells.add(current.toString());
        return cells;
    }

    private static String display(Path root) {
        return root.getFileName() == null ? root.toString() : root.getFileName().toString();
    }

    private static String display(Path root, String file) {
        return display(root) + "/" + file;
    }

    private static String abbreviate(String value) {
        String sanitized = value.replaceAll("[\\p{Cntrl}]", "?");
        return sanitized.length() <= 32 ? sanitized : sanitized.substring(0, 29) + "...";
    }
}
