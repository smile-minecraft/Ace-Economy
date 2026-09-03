package com.smile.aceeconomy.infrastructure.operations;

import com.smile.aceeconomy.ports.operations.ImportSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
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
     * <p>Metadata alone (identity, size, timestamp) cannot tell an in-place
     * rewrite with a restored timestamp apart from the approved content, so
     * regular files also carry the SHA-256 of the bytes seen at gate time.
     * The secure readers hash what they actually read and refuse the content
     * when the two digests differ.</p>
     *
     * @param path      the validated absolute real path returned by the gate
     * @param fileKey   filesystem identity at gate time, may be null when the
     *                  filesystem does not provide one
     * @param directory whether the path was a directory at gate time
     * @param size      file size at gate time, {@code -1} for directories
     * @param modified  last-modified time at gate time, null for directories
     * @param contentHash SHA-256 hex of the file bytes at gate time, null for
     *                  directories
     */
    public record GatedImport(Path path, Object fileKey, boolean directory, long size, FileTime modified,
            String contentHash) {
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
        if (dir) {
            return new GatedImport(real, attrs.fileKey(), true, -1, null, null);
        }
        if (attrs.size() > MAX_FILE_BYTES) {
            throw new ImportPathRejectedException("import file is too large (max "
                    + MAX_FILE_BYTES + " bytes): " + displayName);
        }
        byte[] bytes;
        try {
            bytes = readBytesSecure(real, displayName);
        } catch (IOException e) {
            throw new ImportPathRejectedException(
                    displayName + ": cannot read import file; refusing to read");
        }
        // The content was read in a separate step from the first stat, so
        // re-stat and fail closed when the file moved underneath the snapshot;
        // otherwise the digest below could bind bytes from a different version
        // than the recorded size and timestamp.
        if (Files.isSymbolicLink(real)) {
            throw new ImportPathRejectedException(
                    "import path must not be a symbolic link: " + displayName);
        }
        BasicFileAttributes again = readAttributes(real, displayName);
        if (!again.isRegularFile() || !sameIdentity(attrs, again) || bytes.length != again.size()) {
            throw new ImportPathRejectedException(
                    displayName + ": import path changed during approval; refusing to read");
        }
        return new GatedImport(real, again.fileKey(), false, again.size(),
                again.lastModifiedTime(), sha256Hex(bytes));
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
            if (!gated.directory()) {
                // Same filesystem object, but the content may have been
                // rewritten in place (truncate + write keeps the identity).
                if (gated.size() != now.size() || !gated.modified().equals(now.lastModifiedTime())) {
                    throw new ImportPathRejectedException(
                            displayName + ": import path was replaced after approval; refusing to read");
                }
            }
            return;
        }
        if (!gated.directory()) {
            // Filesystem without identity keys: fall back to size plus timestamp.
            // A same-size, same-timestamp replacement passes this metadata check;
            // the digest comparison in the secure readers still refuses it, so
            // this stays a fast pre-check, never the final word on content.
            if (gated.size() != now.size() || !gated.modified().equals(now.lastModifiedTime())) {
                throw new ImportPathRejectedException(
                        displayName + ": import path was replaced after approval; refusing to read");
            }
        }
    }

    /**
     * List the members of a gate-approved directory, failing closed when the
     * directory itself was swapped while being listed. Each returned member
     * carries the identity seen at enumeration time; {@link #readMemberSecure}
     * refuses the member when it no longer matches that snapshot, so a file
     * swapped in after the listing is never parsed.
     *
     * @throws ImportPathRejectedException when the directory no longer matches
     */
    static List<GatedImport> listMembersSecure(GatedImport root, String displayName) {
        verifyUnchanged(root, displayName);
        List<Path> names;
        try (Stream<Path> stream = Files.list(root.path())) {
            names = stream.sorted(Comparator.comparing(path -> path.getFileName().toString())).toList();
        } catch (IOException e) {
            throw new ImportPathRejectedException(displayName + ": cannot list input; refusing to read");
        }
        List<GatedImport> members = new ArrayList<>(names.size());
        for (Path name : names) {
            members.add(snapshot(name, displayName));
        }
        verifyUnchanged(root, displayName);
        return members;
    }

    /**
     * Read a gate-approved single file, bracketed by identity checks so a swap
     * to a symlink, a directory, or a different file during the read is
     * refused and the foreign content is discarded. The bytes actually read
     * are hashed and compared with the gate-time digest, so an in-place
     * rewrite that keeps the size and timestamp is refused as well.
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
        byte[] bytes = readBytesSecure(gated.path(), displayName);
        verifyContentHash(gated, bytes,
                displayName + ": import file changed after approval; refusing to read");
        verifyUnchanged(gated, displayName);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /**
     * Read one member of a gate-approved directory: the approved directory
     * itself is re-verified before and after the read, and the member must
     * still match the identity captured at enumeration time, be a plain
     * regular file both before and after the read, still be contained in the
     * directory, and stay within the size bound. The bytes actually read are
     * hashed against the enumeration-time digest, so a member rewritten in
     * place with the same size and timestamp is refused as well. A
     * whole-directory swap after the listing, or a member swapped or rewritten
     * in place, is refused and the content is discarded, never parsed.
     *
     * @param root     the gate identity of the approved directory
     * @param expected the member identity captured by {@link #listMembersSecure}
     * @throws ImportPathRejectedException when the member is unsafe
     * @throws IOException                 when the read itself fails
     */
    static String readMemberSecure(GatedImport root, GatedImport expected, String displayName)
            throws IOException {
        verifyUnchanged(root, displayName);
        Path member = expected.path();
        BasicFileAttributes before = preCheckMember(root, member, displayName);
        if (!matchesSnapshot(expected, before)) {
            throw new ImportPathRejectedException(displayName + ": import entry changed after listing; refusing");
        }
        byte[] bytes = readBytesSecure(member, displayName);
        verifyContentHash(expected, bytes, displayName + ": import entry changed after listing; refusing");
        if (Files.isSymbolicLink(member)) {
            throw new ImportPathRejectedException(displayName + ": import entry changed during read; refusing");
        }
        BasicFileAttributes after = readAttributes(member, displayName);
        if (!after.isRegularFile() || !sameFile(before, after)) {
            throw new ImportPathRejectedException(displayName + ": import entry changed during read; refusing");
        }
        Path rootReal;
        Path memberReal;
        try {
            rootReal = root.path().toRealPath();
            memberReal = member.toRealPath();
        } catch (IOException e) {
            throw new ImportPathRejectedException(displayName + ": cannot resolve import entry; refusing");
        }
        if (!memberReal.startsWith(rootReal)) {
            throw new ImportPathRejectedException(displayName + ": import entry left the import directory; refusing");
        }
        verifyUnchanged(root, displayName);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static BasicFileAttributes preCheckMember(GatedImport root, Path member, String displayName)
            throws IOException {
        if (Files.isSymbolicLink(member)) {
            throw new ImportPathRejectedException(displayName + ": import entry is not a regular file; refusing");
        }
        BasicFileAttributes attrs = readAttributes(member, displayName);
        if (!attrs.isRegularFile()) {
            throw new ImportPathRejectedException(displayName + ": import entry is not a regular file; refusing");
        }
        Path rootReal;
        Path memberReal;
        try {
            rootReal = root.path().toRealPath();
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

    /**
     * Metadata pre-check before the read: identity, size and timestamp still
     * match the snapshot. Content equality is established separately by
     * {@link #verifyContentHash} over the bytes actually read.
     */
    private static boolean matchesSnapshot(GatedImport expected, BasicFileAttributes actual) {
        if (expected.directory() || !actual.isRegularFile()) {
            return false;
        }
        if (expected.fileKey() != null && actual.fileKey() != null) {
            return expected.fileKey().equals(actual.fileKey())
                    && expected.size() == actual.size()
                    && expected.modified().equals(actual.lastModifiedTime());
        }
        return expected.size() == actual.size()
                && expected.modified().equals(actual.lastModifiedTime());
    }

    /**
     * Metadata post-check around the read: the file was not swapped or
     * rewritten with a visible size or timestamp change while being read.
     * A rewrite that preserves both is caught by {@link #verifyContentHash}.
     */
    private static boolean sameFile(BasicFileAttributes before, BasicFileAttributes after) {
        if (before.fileKey() != null && after.fileKey() != null) {
            // Same identity is not enough: an in-place rewrite keeps the
            // identity while replacing the content.
            return before.fileKey().equals(after.fileKey())
                    && before.size() == after.size()
                    && before.lastModifiedTime().equals(after.lastModifiedTime());
        }
        return before.size() == after.size()
                && before.lastModifiedTime().equals(after.lastModifiedTime());
    }

    private static boolean sameIdentity(BasicFileAttributes before, BasicFileAttributes after) {
        if (before.fileKey() != null && after.fileKey() != null
                && !before.fileKey().equals(after.fileKey())) {
            return false;
        }
        return before.size() == after.size()
                && before.lastModifiedTime().equals(after.lastModifiedTime());
    }

    /**
     * Single read quantum for the bounded file read below. Small enough to
     * stop just past the size bound, large enough to keep full-size reads fast.
     */
    private static final int READ_CHUNK_BYTES = 64 * 1024;

    /**
     * Read the whole file after a symlink pre-check. A size probe first would
     * still leave a window where the file grows before the bytes are pulled
     * in, so the stream itself is capped: anything past the limit fails closed
     * here instead of being buffered into memory first. Symlinks are still
     * rejected before and after by the callers.
     */
    private static byte[] readBytesSecure(Path path, String displayName) throws IOException {
        if (Files.isSymbolicLink(path)) {
            throw new ImportPathRejectedException(
                    displayName + ": import path must not be a symbolic link; refusing to read");
        }
        try (InputStream in = Files.newInputStream(path)) {
            return readBoundedBytes(in, displayName);
        }
    }

    /**
     * Drain at most {@code MAX_FILE_BYTES + 1} bytes: exactly-at-limit input
     * is returned whole, anything larger is refused before the oversized tail
     * is buffered. The one-byte over-read is what tells "exactly full" apart
     * from "too big" without trusting a pre-read size probe.
     */
    static byte[] readBoundedBytes(InputStream in, String displayName) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(READ_CHUNK_BYTES);
        byte[] chunk = new byte[READ_CHUNK_BYTES];
        long total = 0;
        int read;
        while ((read = in.read(chunk)) != -1) {
            total += read;
            if (total > MAX_FILE_BYTES) {
                throw new ImportPathRejectedException("import file is too large (max "
                        + MAX_FILE_BYTES + " bytes): " + displayName);
            }
            out.write(chunk, 0, read);
        }
        return out.toByteArray();
    }

    /**
     * Refuse bytes whose digest no longer matches the gate-time snapshot. This
     * is the check that catches an in-place rewrite which keeps the file
     * identity, size and timestamp: the metadata still matches, but the
     * content does not.
     */
    private static void verifyContentHash(GatedImport expected, byte[] bytes, String message) {
        if (expected.directory() || expected.contentHash() == null) {
            return;
        }
        if (!expected.contentHash().equals(sha256Hex(bytes))) {
            throw new ImportPathRejectedException(message);
        }
    }

    private static String sha256Hex(byte[] bytes) {
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 digest unavailable", e);
        }
        byte[] out = digest.digest(bytes);
        StringBuilder hex = new StringBuilder(out.length * 2);
        for (byte b : out) {
            hex.append(Character.forDigit((b >> 4) & 0xF, 16));
            hex.append(Character.forDigit(b & 0xF, 16));
        }
        return hex.toString();
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
