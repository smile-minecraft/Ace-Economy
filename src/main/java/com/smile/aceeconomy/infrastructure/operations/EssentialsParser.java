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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
        List<ImportPathGate.GatedImport> files;
        try {
            files = collect(gated, failures);
        } catch (ImportPathRejectedException e) {
            failures.add(display(gated.path()) + ": " + e.getMessage());
            return new ImportParseResult(records, failures);
        }
        for (ImportPathGate.GatedImport file : files) {
            if (records.size() >= MAX_RECORDS) {
                failures.add(display(gated.path()) + ": too many records (max " + MAX_RECORDS + ")");
                break;
            }
            parseFile(gated, file, currencyId, scale, records, failures);
        }
        return new ImportParseResult(records, failures);
    }

    private static List<ImportPathGate.GatedImport> collect(ImportPathGate.GatedImport gated,
                                                            List<String> failures) {
        Path root = gated.path();
        if (gated.directory()) {
            return ImportPathGate.listMembersSecure(gated, display(root)).stream()
                    .filter(member -> !Files.isSymbolicLink(member.path()))
                    .filter(member -> Files.isRegularFile(member.path(), LinkOption.NOFOLLOW_LINKS))
                    .filter(member -> {
                        String ext = ImportPathGate.extensionOf(
                                member.path().getFileName().toString().toLowerCase(java.util.Locale.ROOT));
                        return ext.equals("yml") || ext.equals("yaml");
                    })
                    .sorted(Comparator.comparing(member -> member.path().getFileName().toString()))
                    .toList();
        }
        return List.of(gated);
    }

    private static void parseFile(ImportPathGate.GatedImport gated, ImportPathGate.GatedImport file,
                                  String currencyId, int scale,
                                  List<ImportRecord> records, List<String> failures) {
        Path root = gated.path();
        String name = file.path().getFileName().toString();
        UUID accountUuid;
        try {
            accountUuid = UUID.fromString(stripExtension(name));
        } catch (IllegalArgumentException e) {
            failures.add(display(root, name) + ": file name is not a player uuid, refusing to guess");
            return;
        }
        String content;
        try {
            if (!gated.directory()) {
                content = ImportPathGate.readRootFileSecure(gated, display(root, name));
            } else {
                content = ImportPathGate.readMemberSecure(gated, file, display(root, name));
            }
        } catch (ImportPathRejectedException e) {
            failures.add(display(root, name) + ": " + e.getMessage());
            return;
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
