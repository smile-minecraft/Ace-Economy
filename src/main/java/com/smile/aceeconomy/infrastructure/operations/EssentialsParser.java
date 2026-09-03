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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Parses EssentialsX userdata flat files ({@code <uuid>.yml} with a top-level
 * {@code money:} balance and an optional {@code last-account-name:}) into
 * normalized {@link ImportRecord} values. Supported input is the EssentialsX
 * 2.x userdata shape; anything else fails closed per file.
 *
 * <p>Only flat top-level keys are read with a small line scanner — nested
 * sections (homes, kits, mail) are irrelevant to balances and ignored. The
 * parser never writes to any repository and never guesses a missing balance:
 * a file without {@code money:} is reported as a failure, not imported as
 * zero. A missing name stays {@code null} so {@code ImportService} can fall
 * back to the uuid string.</p>
 */
public final class EssentialsParser {

    /** Upper bound on parsed records per call; larger inputs fail closed. */
    static final int MAX_RECORDS = 100_000;

    private static final Pattern TOP_LEVEL_ENTRY =
            Pattern.compile("^([A-Za-z0-9_.\\-]+)\\s*:(.*)$");

    private EssentialsParser() {
    }

    /**
     * Parse a single {@code <uuid>.yml} file or a directory of them.
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
                                return ext.equals("yml") || ext.equals("yaml");
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
        String name = file.getFileName().toString();
        UUID accountUuid;
        try {
            accountUuid = UUID.fromString(stripExtension(name));
        } catch (IllegalArgumentException e) {
            failures.add(display(root, name) + ": file name is not a player uuid, refusing to guess");
            return;
        }
        String content;
        try {
            if (Files.size(file) > ImportPathGate.MAX_FILE_BYTES) {
                failures.add(display(root, name) + ": file is too large");
                return;
            }
            content = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            failures.add(display(root, name) + ": cannot read file (" + e.getMessage() + ")");
            return;
        }
        String money = null;
        String owner = null;
        for (String line : content.split("\n", -1)) {
            String stripped = line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
            if (stripped.isEmpty() || stripped.charAt(0) == '#' || stripped.charAt(0) == ' '
                    || stripped.charAt(0) == '\t') {
                continue;
            }
            Matcher matcher = TOP_LEVEL_ENTRY.matcher(stripped);
            if (!matcher.matches()) {
                continue;
            }
            String key = matcher.group(1);
            String value = unquote(matcher.group(2).trim());
            if ("money".equals(key)) {
                money = value;
            } else if ("last-account-name".equals(key)) {
                owner = value.isEmpty() ? null : value;
            }
        }
        if (money == null || money.isEmpty()) {
            failures.add(display(root, name) + ": missing money field, refusing to guess a balance");
            return;
        }
        BigDecimal balance;
        try {
            balance = new BigDecimal(money);
        } catch (NumberFormatException e) {
            failures.add(display(root, name) + ": invalid money value '" + abbreviate(money) + "'");
            return;
        }
        Amount amount;
        try {
            amount = Amount.of(balance, scale);
        } catch (IllegalArgumentException e) {
            failures.add(display(root, name) + ": money value does not fit currency scale (" + e.getMessage() + ")");
            return;
        }
        records.add(new ImportRecord(ImportSource.ESSENTIALS, accountUuid.toString(),
                accountUuid, owner, currencyId, amount));
    }

    private static String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? fileName : fileName.substring(0, dot);
    }

    /** Strip one layer of matching single/double quotes and unquoted inline comments. */
    static String unquote(String value) {
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '\'' && last == '\'') || (first == '"' && last == '"')) {
                String inner = value.substring(1, value.length() - 1);
                return first == '\'' ? inner.replace("''", "'") : inner;
            }
        }
        int comment = value.indexOf(" #");
        if (comment >= 0) {
            return value.substring(0, comment).trim();
        }
        return value;
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
