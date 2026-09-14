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
import java.util.stream.Collectors;
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

    /**
     * Upper bound on directory entries the gate is willing to list and bind.
     * The approved-listing fingerprint holds one canonical line per entry and
     * hashes every regular member, so an unbounded directory would let a
     * hostile or broken import tree dictate unbounded listing work. The bound
     * is well above any realistic userdata directory and above the parser's
     * own record cap.
     */
    static final int MAX_DIRECTORY_ENTRIES = 100_000;

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
     * <p>Directory identity cannot rest on {@code fileKey} alone: inode
     * numbers may be reused after a directory is deleted and recreated, so a
     * swap can keep the same identity and still change every member. Nor can it
     * rest on listing metadata alone: a rebuilt directory can reproduce every
     * name, kind, size, timestamp and identity while the member bytes differ.
     * Directories therefore carry {@code directoryFingerprint}, a digest of the
     * approved listing (per-entry kind, size, timestamp and identity, plus the
     * SHA-256 of every regular member's bytes) that changes whenever the
     * directory is rebuilt or any member's content changes, even on a
     * filesystem that hands out the same {@code fileKey} again.</p>
     *
     * @param path      the validated absolute real path returned by the gate
     * @param fileKey   filesystem identity at gate time, may be null when the
     *                  filesystem does not provide one
     * @param directory whether the path was a directory at gate time
     * @param size      file size at gate time, {@code -1} for directories
     * @param modified  last-modified time at gate time, null for directories
     * @param contentHash SHA-256 hex of the file bytes at gate time, null for
     *                  directories
     * @param directoryFingerprint digest of the approved listing including
     *                  member content, null for regular files
     */
    public record GatedImport(Path path, Object fileKey, boolean directory, long size, FileTime modified,
            String contentHash, String directoryFingerprint) {
    }

    /**
     * Gate the user path and capture its identity for the later read. Same
     * checks as {@link #resolve}, plus a filesystem-identity snapshot the
     * parsers re-verify before touching any content.
     *
     * @throws ImportPathRejectedException before any content is read
     */
    public static GatedImport gate(Path dataFolder, String userPath, ImportSource source) {
        return snapshotRoot(resolve(dataFolder, userPath, source), truncate(userPath == null ? "" : userPath));
    }

    /**
     * Capture the current identity of a path without gate containment checks.
     * Used by direct parser entry points whose input never went through the
     * gate; those reads are still bracketed by pre/post identity checks, but
     * only a gate-bound {@link GatedImport} can detect a swap that happened
     * before parsing started.
     */
    static GatedImport snapshot(Path real, String displayName) {
        return snapshot(real, displayName, false);
    }

    /**
     * Snapshot a root path whose directory listing must be bound, so a later
     * directory swap is detectable even when the filesystem reuses the
     * directory identity. Member snapshots taken while expanding an approved
     * directory use {@link #snapshot(Path, String)} instead: their parent
     * already carries the listing fingerprint, and expanding nested
     * directories is neither needed nor bounded here.
     */
    static GatedImport snapshotRoot(Path real, String displayName) {
        return snapshot(real, displayName, true);
    }

    private static GatedImport snapshot(Path real, String displayName, boolean fingerprintDirectory) {
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
            String fingerprint = fingerprintDirectory ? directoryFingerprint(real, displayName) : null;
            return new GatedImport(real, attrs.fileKey(), true, -1, null, null, fingerprint);
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
                again.lastModifiedTime(), sha256Hex(bytes), null);
    }

    /**
     * Re-verify that a gate-approved path is still the same filesystem object:
     * still no symlink, still the same kind (file stays a file, directory
     * stays a directory) and still the same identity. Anything else means the
     * path was swapped after the gate passed and must not be read.
     *
     * <p>For directories this is only the cheap identity check; a filesystem
     * that reuses the identity would slip past it, so directory enumeration
     * goes through {@link #listMembersSecure}, which also matches the approved
     * listing fingerprint.</p>
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
     * List the members of a gate-approved directory and bind them to the
     * approved listing in a single pass.
     *
     * <p>Snapshotting the members and then checking the directory afterwards
     * is not enough: a directory swapped for approved-looking content only
     * while the listing is read would let the swapped bytes become the
     * baseline the reader trusts. Here the canonical listing rebuilt from the
     * very snapshots that are returned is compared with the fingerprint the
     * gate approved, so those snapshots are only ever returned when their
     * content hashes are the approved ones. The member bytes are then checked
     * again at read time, so a swap after this point is refused too.</p>
     *
     * <p>The identity check brackets the pass. The approved fingerprint must
     * be present (a directory that was never bound fails closed) and must match
     * exactly; a directory deleted and recreated with the same {@code fileKey}
     * or the same listing metadata is still rejected because the member content
     * is part of the fingerprint.</p>
     *
     * @throws ImportPathRejectedException when the directory no longer matches
     */
    static List<GatedImport> listMembersSecure(GatedImport root, String displayName) {
        verifyUnchanged(root, displayName);
        if (!root.directory()) {
            throw new ImportPathRejectedException(
                    displayName + ": import path is not a directory; refusing to read");
        }
        String approved = root.directoryFingerprint();
        if (approved == null) {
            throw new ImportPathRejectedException(
                    displayName + ": import directory was never approved; refusing to read");
        }
        List<Path> names = listEntries(root.path(), displayName);
        List<GatedImport> members = new ArrayList<>(names.size());
        StringBuilder canonical = new StringBuilder();
        for (Path name : names) {
            GatedImport member = snapshot(name, displayName);
            members.add(member);
            appendMemberCanonicalEntry(canonical, member);
        }
        if (!approved.equals(sha256Hex(canonical.toString().getBytes(StandardCharsets.UTF_8)))) {
            throw new ImportPathRejectedException(
                    displayName + ": import directory was replaced after approval; refusing to read");
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
     * hashed against the digest bound into the approved listing, so a member
     * rewritten in place with the same size and timestamp is refused as well.
     * That binding is stronger than re-reading the directory fingerprint: the
     * expected digest is the gate-approved content, not a snapshot taken from
     * whatever the directory contained during listing. A whole-directory swap
     * after the listing, or a member swapped or rewritten in place, is refused
     * and the content is discarded, never parsed.
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
     * Digest of a directory's approved listing: one canonical line per entry
     * (name, kind, size, timestamp, identity and, for regular files, the
     * SHA-256 of the bytes), hashed. Binding member content is deliberate: a
     * directory deleted and recreated can reproduce every metadata field while
     * the bytes differ, so metadata alone would let a rebuilt directory collide
     * with the approved one. Each regular member is read through the bounded
     * reader, so a single entry never exceeds {@link #MAX_FILE_BYTES}, and the
     * listing itself is capped by {@link #MAX_DIRECTORY_ENTRIES}.
     */
    private static String directoryFingerprint(Path dir, String displayName) {
        StringBuilder canonical = new StringBuilder();
        for (Path entry : listEntries(dir, displayName)) {
            BasicFileAttributes attrs = readAttributes(entry, displayName);
            String name = entry.getFileName().toString();
            String identity = attrs.fileKey() == null ? null : attrs.fileKey().toString();
            if (attrs.isDirectory()) {
                // Directory members are not expanded, so bind the entry itself.
                appendCanonicalEntry(canonical, name, 'd', -1, null, identity, null);
            } else if (attrs.isRegularFile()) {
                appendCanonicalEntry(canonical, name, 'f', attrs.size(),
                        attrs.lastModifiedTime().toInstant().toString(), identity,
                        contentHashBounded(entry, displayName));
            } else {
                appendCanonicalEntry(canonical, name, 'o', attrs.size(),
                        attrs.lastModifiedTime().toInstant().toString(), identity, null);
            }
        }
        return sha256Hex(canonical.toString().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * List a directory and refuse it past {@link #MAX_DIRECTORY_ENTRIES} so
     * neither the fingerprint nor the enumeration can be forced to grow without
     * bound. The stream is opened here and closed with try-with-resources; the
     * bounded collection below only reads from it.
     */
    private static List<Path> listEntries(Path dir, String displayName) {
        try (Stream<Path> stream = Files.list(dir)) {
            return boundedEntries(stream, displayName, MAX_DIRECTORY_ENTRIES);
        } catch (IOException e) {
            throw new ImportPathRejectedException(displayName + ": cannot list input; refusing to read");
        }
    }

    /**
     * Collect at most {@code maxEntries + 1} entries and refuse the listing when
     * that one extra entry proves the source is past the cap, so neither the
     * fingerprint nor the enumeration can be forced to grow without bound.
     *
     * <p>The cap is applied while collecting, before any sorting: only
     * {@code maxEntries + 1} entries are ever pulled, which is enough to detect
     * that the source is past the limit. Collecting and sorting the whole source
     * first would let a hostile or broken tree dictate unbounded listing and
     * sort work. A source at or under the limit is returned sorted with the same
     * members as before. The caller owns {@code stream} and closes it.</p>
     *
     * <p>Package-private so tests can exercise the same bounded collection with
     * a small cap and a lazily generated stream: the fail-closed decision and
     * the size bound are then verified without a real directory of
     * {@code MAX_DIRECTORY_ENTRIES} members or static mocking of
     * {@code Files.list}. Production always passes
     * {@link #MAX_DIRECTORY_ENTRIES}.</p>
     */
    static List<Path> boundedEntries(Stream<Path> stream, String displayName, int maxEntries) {
        List<Path> names = stream.limit((long) maxEntries + 1)
                .collect(Collectors.toCollection(ArrayList::new));
        if (names.size() > maxEntries) {
            throw new ImportPathRejectedException(displayName + ": import directory has too many entries (max "
                    + maxEntries + "); refusing to read");
        }
        names.sort(Comparator.comparing(path -> path.getFileName().toString()));
        return names;
    }

    /**
     * One canonical listing line, shared by the approved-fingerprint computation
     * and the enumeration check so both agree on what a member contributes.
     */
    private static void appendCanonicalEntry(StringBuilder canonical, String name, char kind, long size,
            String modified, String identity, String contentHash) {
        canonical.append(name).append('\u0000')
                .append(kind).append('\u0000')
                .append(size).append('\u0000')
                .append(modified == null ? "-" : modified).append('\u0000')
                .append(identity == null ? "-" : identity).append('\u0000')
                .append(contentHash == null ? "-" : contentHash)
                .append('\n');
    }

    /** Canonical line for a member snapshot taken while enumerating a directory. */
    private static void appendMemberCanonicalEntry(StringBuilder canonical, GatedImport member) {
        String name = member.path().getFileName().toString();
        String identity = member.fileKey() == null ? null : member.fileKey().toString();
        if (member.directory()) {
            appendCanonicalEntry(canonical, name, 'd', -1, null, identity, null);
        } else {
            appendCanonicalEntry(canonical, name, 'f', member.size(),
                    member.modified().toInstant().toString(), identity, member.contentHash());
        }
    }

    /**
     * SHA-256 of a member's bytes, read through the bounded reader. A file
     * larger than the size bound is not read — it is refused when actually
     * parsed — so it contributes a fixed marker instead of an unbounded read.
     */
    private static String contentHashBounded(Path file, String displayName) {
        try {
            if (Files.size(file) > MAX_FILE_BYTES) {
                return "oversized";
            }
        } catch (IOException e) {
            throw new ImportPathRejectedException(
                    displayName + ": cannot inspect import path; refusing to read");
        }
        try {
            return sha256Hex(readBytesSecure(file, displayName));
        } catch (IOException e) {
            throw new ImportPathRejectedException(
                    displayName + ": cannot read import entry; refusing to read");
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
