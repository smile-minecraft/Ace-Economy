package com.smile.aceeconomy.infrastructure.operations;

import com.smile.aceeconomy.domain.Amount;
import com.smile.aceeconomy.operations.ImportParseResult;
import com.smile.aceeconomy.ports.operations.ImportRecord;
import com.smile.aceeconomy.ports.operations.ImportSource;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

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
        List<ImportRecord> records = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        for (Path file : collect(root, failures)) {
            if (records.size() >= MAX_RECORDS) {
                failures.add(display(root) + ": too many records (max " + MAX_RECORDS + ")");
                break;
            }
            parseFile(root, file, currencyId, scale, records, failures);
        }
        return new ImportParseResult(records, failures);
    }

    private static List<Path> collect(Path root, List<String> failures) {
        try {
            if (Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
                try (Stream<Path> stream = Files.list(root)) {
                    return stream
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
            }
            if (Files.isRegularFile(root, LinkOption.NOFOLLOW_LINKS)
                    && !Files.isSymbolicLink(root)) {
                return List.of(root);
            }
        } catch (IOException e) {
            failures.add(display(root) + ": cannot list input (" + e.getMessage() + ")");
            return List.of();
        }
        failures.add(display(root) + ": not a regular file or directory");
        return List.of();
    }

    private static void parseFile(Path root, Path file, String currencyId, int scale,
                                  List<ImportRecord> records, List<String> failures) {
        String name = display(root, file.getFileName().toString());
        List<String> lines;
        try {
            if (Files.size(file) > ImportPathGate.MAX_FILE_BYTES) {
                failures.add(name + ": file is too large");
                return;
            }
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            failures.add(name + ": cannot read file (" + e.getMessage() + ")");
            return;
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
