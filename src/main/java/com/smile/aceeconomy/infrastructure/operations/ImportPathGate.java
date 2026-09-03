package com.smile.aceeconomy.infrastructure.operations;

import com.smile.aceeconomy.ports.operations.ImportSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Safety gate for every import read. The user path must stay inside the
 * plugin-controlled {@code <dataFolder>/import} directory: absolute paths,
 * {@code ..} segments, symlink escapes, missing entries, non-regular files,
 * oversized files, sensitive names and per-source unsupported extensions are
 * all rejected before anything is read.
 *
 * <p>Extension rule: Essentials userdata is {@code .yml}/{@code .yaml};
 * the v1 CMI input is an operator-prepared balance sheet
 * ({@code .csv}/{@code .txt}). Anything else — including SQLite binaries such
 * as {@code cmi.sqlite.db} — fails closed here and is never parsed.</p>
 */
public final class ImportPathGate {

    /** Single input files larger than this are rejected to bound memory. */
    public static final long MAX_FILE_BYTES = 8L * 1024 * 1024;

    private static final Set<String> ESSENTIALS_EXTENSIONS = Set.of("yml", "yaml");
    private static final Set<String> CMI_EXTENSIONS = Set.of("csv", "txt");
    private static final String IMPORT_DIR_NAME = "import";

    /**
     * Well-known server/plugin files that must never be treated as import input
     * even when they sit inside the import directory with an allowed extension.
     */
    private static final Set<String> SENSITIVE_NAMES = Set.of(
            "config.yml", "bukkit.yml", "spigot.yml", "commands.yml", "permissions.yml",
            "help.yml", "paper-global.yml", "paper-world-defaults.yml",
            "data-v2.json", "data-v2.sqlite");

    private static final Pattern WINDOWS_ABSOLUTE = Pattern.compile("^[A-Za-z]:[/\\\\].*");

    private ImportPathGate() {
    }

    /**
     * Resolve the user path to a validated absolute path (file or directory)
     * inside the controlled import directory.
     *
     * @throws ImportPathRejectedException before any content is read
     */
    public static Path resolve(Path dataFolder, String userPath, ImportSource source) {
        if (dataFolder == null) {
            throw new IllegalArgumentException("dataFolder must not be null");
        }
        if (source == null) {
            throw new IllegalArgumentException("source must not be null");
        }
        String raw = userPath == null ? "" : userPath.trim();
        if (raw.isEmpty()) {
            throw new ImportPathRejectedException("import path is required");
        }
        if (raw.indexOf(0) >= 0) {
            throw new ImportPathRejectedException("import path must not contain NUL characters");
        }
        String unified = raw.replace('\\', '/');
        if (unified.startsWith("/") || unified.startsWith("\\\\")
                || WINDOWS_ABSOLUTE.matcher(raw).matches() || Path.of(raw).isAbsolute()) {
            throw new ImportPathRejectedException("absolute import paths are not allowed: " + truncate(raw));
        }
        for (String segment : unified.split("/", -1)) {
            if ("..".equals(segment)) {
                throw new ImportPathRejectedException(
                        "import path must not escape the import directory: " + truncate(raw));
            }
        }

        Path importDir = dataFolder.toAbsolutePath().normalize().resolve(IMPORT_DIR_NAME);
        Path candidate = importDir.resolve(unified).normalize();
        if (!candidate.startsWith(importDir)) {
            throw new ImportPathRejectedException(
                    "import path resolves outside the import directory: " + truncate(raw));
        }
        Path realImport;
        try {
            if (Files.isSymbolicLink(importDir)) {
                throw new ImportPathRejectedException("import directory must not be a symbolic link");
            }
            if (!Files.isDirectory(importDir, LinkOption.NOFOLLOW_LINKS)) {
                throw new ImportPathRejectedException(
                        "import directory does not exist; create <plugin data folder>/import first");
            }
            realImport = importDir.toRealPath();
        } catch (IOException e) {
            throw new ImportPathRejectedException(
                    "cannot verify the import directory; refusing unguarded access");
        }
        if (!Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
            throw new ImportPathRejectedException("import path does not exist: " + truncate(raw));
        }
        if (Files.isSymbolicLink(candidate)) {
            throw new ImportPathRejectedException(
                    "import path must not be a symbolic link: " + truncate(raw));
        }
        Path realCandidate;
        try {
            realCandidate = candidate.toRealPath();
        } catch (IOException e) {
            throw new ImportPathRejectedException("cannot resolve import path: " + truncate(raw));
        }
        if (!realCandidate.startsWith(realImport)) {
            throw new ImportPathRejectedException(
                    "import path escapes the import directory: " + truncate(raw));
        }
        if (Files.isDirectory(realCandidate, LinkOption.NOFOLLOW_LINKS)) {
            return realCandidate;
        }
        if (!Files.isRegularFile(realCandidate, LinkOption.NOFOLLOW_LINKS)) {
            throw new ImportPathRejectedException(
                    "import path is not a regular file or directory: " + truncate(raw));
        }
        try {
            checkFile(realCandidate, source, truncate(raw));
        } catch (IOException e) {
            throw new ImportPathRejectedException("cannot inspect import path: " + truncate(raw));
        }
        return realCandidate;
    }

    /**
     * Re-check a single member discovered while expanding a gated directory:
     * no symlinks, regular file, allowed extension and name, bounded size.
     */
    static void checkFile(Path file, ImportSource source, String displayName) throws IOException {
        if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new ImportPathRejectedException("import entry is not a regular file: " + displayName);
        }
        String name = file.getFileName().toString();
        String lower = name.toLowerCase(Locale.ROOT);
        if (SENSITIVE_NAMES.contains(lower)) {
            throw new ImportPathRejectedException(
                    "refusing sensitive file as import input: " + displayName);
        }
        String extension = extensionOf(lower);
        Set<String> allowed = source == ImportSource.ESSENTIALS ? ESSENTIALS_EXTENSIONS : CMI_EXTENSIONS;
        if (!allowed.contains(extension)) {
            throw new ImportPathRejectedException("unsupported file type for " + source.name().toLowerCase(Locale.ROOT)
                    + " import (allowed: " + String.join("/", allowed) + "): " + displayName);
        }
        if (Files.size(file) > MAX_FILE_BYTES) {
            throw new ImportPathRejectedException("import file is too large (max "
                    + MAX_FILE_BYTES + " bytes): " + displayName);
        }
    }

    static String extensionOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(dot + 1);
    }

    private static String truncate(String value) {
        String sanitized = value.replaceAll("[\\p{Cntrl}]", "?");
        return sanitized.length() <= 80 ? sanitized : sanitized.substring(0, 77) + "...";
    }
}
