package com.smile.aceeconomy.infrastructure.operations;

import com.smile.aceeconomy.ports.operations.ImportSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

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
     * Identity of a gate-approved path, captured at check time. Parsers must
     * re-verify it right before reading: the gate runs first and the read
     * happens later, so anything swapped in between (replaced file or
     * directory, file turned into a directory or a symlink) no longer matches
     * this identity and is refused instead of parsed.
     *
     * @param path      the validated absolute real path returned by the gate
     * @param fileKey   filesystem identity at gate time, may be null when the
     *                  filesystem does not provide one
     * @param directory whether the path was a directory at gate time
     * @param size      file size at gate time, {@code -1} for directories
     * @param modified  last-modified time at gate time, null for directories
     */
    public record GatedImport(Path path, Object fileKey, boolean directory, long size, FileTime modified) {
    }

    /**
     * Gate the user path and capture its identity for the later read. Same
     * checks as {@link #resolve}, plus a filesystem-identity snapshot the
     * parsers re-verify before touching any content.
     *
     * @throws ImportPathRejectedException before any content is read
     */
    public static GatedImport gate(Path dataFolder, String userPath, ImportSource source) {
        return snapshot(resolve(dataFolder, userPath, source), truncate(userPath == null ? "" : userPath));
    }

    /**
     * Capture the current identity of a path without gate containment checks.
     * Used by direct parser entry points whose input never went through the
     * gate; those reads are still bracketed by pre/post identity checks, but
     * only a gate-bound {@link GatedImport} can detect a swap that happened
     * before parsing started.
     */
    static GatedImport snapshot(Path real, String displayName) {
        if (Files.isSymbolicLink(real)) {
            throw new ImportPathRejectedException(
                    "import path must not be a symbolic link: " + displayName);
        }
        BasicFileAttributes attrs = readAttributes(real, displayName);
        boolean dir = attrs.isDirectory();
        if (!dir && !attrs.isRegularFile()) {
            throw new ImportPathRejectedException(
                    "import path is not a regular file or directory: " + displayName);
        }
        return new GatedImport(real, attrs.fileKey(), dir, dir ? -1 : attrs.size(),
                dir ? null : attrs.lastModifiedTime());
    }

    /**
     * Re-verify that a gate-approved path is still the same filesystem object:
     * still no symlink, still the same kind (file stays a file, directory
     * stays a directory) and still the same identity. Anything else means the
     * path was swapped after the gate passed and must not be read.
     *
     * @throws ImportPathRejectedException when the path no longer matches
     */
    static void verifyUnchanged(GatedImport gated, String displayName) {
        if (Files.isSymbolicLink(gated.path())) {
            throw new ImportPathRejectedException(
                    displayName + ": import path was replaced after approval; refusing to read");
        }
        BasicFileAttributes now = readAttributes(gated.path(), displayName);
        if (now.isDirectory() != gated.directory()) {
            throw new ImportPathRejectedException(
                    displayName + ": import path changed shape after approval; refusing to read");
        }
        if (!now.isDirectory() && !now.isRegularFile()) {
            throw new ImportPathRejectedException(
                    displayName + ": import path is no longer readable; refusing to read");
        }
        if (gated.fileKey() != null && now.fileKey() != null) {
            if (!gated.fileKey().equals(now.fileKey())) {
                throw new ImportPathRejectedException(
                        displayName + ": import path was replaced after approval; refusing to read");
            }
            return;
        }
        if (!gated.directory()) {
            // Filesystem without identity keys: fall back to size plus timestamp.
            // A same-size, same-timestamp replacement slips through here; the
            // pre/post read bracket below still bars symlinks and escapes.
            if (gated.size() != now.size() || !gated.modified().equals(now.lastModifiedTime())) {
                throw new ImportPathRejectedException(
                        displayName + ": import path was replaced after approval; refusing to read");
            }
        }
    }

    /**
     * List the members of a gate-approved directory, failing closed when the
     * directory itself was swapped while being listed. Returned members are
     * still unvalidated candidates: each one goes through
     * {@link #readMemberSecure} before its content is used.
     *
     * @throws ImportPathRejectedException when the directory no longer matches
     */
    static List<Path> listMembersSecure(GatedImport root, String displayName) {
        verifyUnchanged(root, displayName);
        List<Path> members;
        try (Stream<Path> stream = Files.list(root.path())) {
            members = stream.sorted(Comparator.comparing(path -> path.getFileName().toString())).toList();
        } catch (IOException e) {
            throw new ImportPathRejectedException(displayName + ": cannot list input; refusing to read");
        }
        verifyUnchanged(root, displayName);
        return new ArrayList<>(members);
    }

    /**
     * Read a gate-approved single file, bracketed by identity checks so a swap
     * to a symlink, a directory, or a different file during the read is
     * refused and the foreign content is discarded.
     *
     * @throws ImportPathRejectedException when the file no longer matches
     * @throws IOException                 when the read itself fails
     */
    static String readRootFileSecure(GatedImport gated, String displayName) throws IOException {
        verifyUnchanged(gated, displayName);
        if (Files.size(gated.path()) > MAX_FILE_BYTES) {
            throw new ImportPathRejectedException("import file is too large (max "
                    + MAX_FILE_BYTES + " bytes): " + displayName);
        }
        String content = Files.readString(gated.path(), StandardCharsets.UTF_8);
        verifyUnchanged(gated, displayName);
        return content;
    }

    /**
     * Read one member of a gate-approved directory: the member must be a plain
     * regular file both before and after the read, still the same file, still
     * contained in the directory, and within the size bound. Anything else is
     * refused and the content is discarded, never parsed.
     *
     * @param rootReal the freshly resolved real path of the approved directory
     * @throws ImportPathRejectedException when the member is unsafe
     * @throws IOException                 when the read itself fails
     */
    static String readMemberSecure(Path rootReal, Path member, String displayName) throws IOException {
        BasicFileAttributes before = preCheckMember(rootReal, member, displayName);
        String content = Files.readString(member, StandardCharsets.UTF_8);
        if (Files.isSymbolicLink(member)) {
            throw new ImportPathRejectedException(displayName + ": import entry changed during read; refusing");
        }
        BasicFileAttributes after = readAttributes(member, displayName);
        if (!after.isRegularFile() || !sameFile(before, after)) {
            throw new ImportPathRejectedException(displayName + ": import entry changed during read; refusing");
        }
        Path memberReal;
        try {
            memberReal = member.toRealPath();
        } catch (IOException e) {
            throw new ImportPathRejectedException(displayName + ": cannot resolve import entry; refusing");
        }
        if (!memberReal.startsWith(rootReal)) {
            throw new ImportPathRejectedException(displayName + ": import entry left the import directory; refusing");
        }
        return content;
    }

    private static BasicFileAttributes preCheckMember(Path rootReal, Path member, String displayName)
            throws IOException {
        if (Files.isSymbolicLink(member)) {
            throw new ImportPathRejectedException(displayName + ": import entry is not a regular file; refusing");
        }
        BasicFileAttributes attrs = readAttributes(member, displayName);
        if (!attrs.isRegularFile()) {
            throw new ImportPathRejectedException(displayName + ": import entry is not a regular file; refusing");
        }
        Path memberReal;
        try {
            memberReal = member.toRealPath();
        } catch (IOException e) {
            throw new ImportPathRejectedException(displayName + ": cannot resolve import entry; refusing");
        }
        if (!memberReal.startsWith(rootReal)) {
            throw new ImportPathRejectedException(displayName + ": import entry left the import directory; refusing");
        }
        if (attrs.size() > MAX_FILE_BYTES) {
            throw new ImportPathRejectedException("import file is too large (max "
                    + MAX_FILE_BYTES + " bytes): " + displayName);
        }
        return attrs;
    }

    private static boolean sameFile(BasicFileAttributes before, BasicFileAttributes after) {
        if (before.fileKey() != null && after.fileKey() != null) {
            return before.fileKey().equals(after.fileKey());
        }
        return before.size() == after.size()
                && before.lastModifiedTime().equals(after.lastModifiedTime());
    }

    private static BasicFileAttributes readAttributes(Path path, String displayName) {
        try {
            return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException e) {
            throw new ImportPathRejectedException(displayName + ": cannot inspect import path; refusing to read");
        }
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
