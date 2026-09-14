package com.smile.aceeconomy.infrastructure.operations;

import com.smile.aceeconomy.operations.ImportParseResult;
import com.smile.aceeconomy.ports.operations.ImportRecord;
import com.smile.aceeconomy.ports.operations.ImportSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TOCTOU reproduction: the path gate checks first, the parser reads later.
 * Anything swapped in between (replaced directory, file turned into a
 * directory, file turned into a symlink) must be rejected — the parser must
 * never return content the gate never approved.
 */
class ImportToctouTest {

    private static final UUID ATTACKER = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID VICTIM = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");

    private Path importDir(Path dataFolder) throws Exception {
        Path dir = dataFolder.resolve("import");
        Files.createDirectories(dir);
        return dir;
    }

    private static void deleteTree(Path root) throws Exception {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
    }

    private static boolean containsAttacker(ImportParseResult result) {
        return result.records().stream()
                .map(ImportRecord::accountUuid)
                .anyMatch(ATTACKER::equals);
    }

    @Test
    void replacedDirectoryMustNotLeakAttackerRecords(@TempDir Path dataFolder) throws Exception {
        Path dir = importDir(dataFolder);
        Path sheets = dir.resolve("sheets");
        Files.createDirectories(sheets);
        Files.writeString(sheets.resolve("benign.csv"),
                "uuid,name,balance\n" + VICTIM + ",Victim,10\n", StandardCharsets.UTF_8);

        ImportPathGate.GatedImport gated = ImportPathGate.gate(dataFolder, "sheets", ImportSource.CMI);

        // Gate passed on the benign directory; now the whole directory is swapped
        // for one carrying attacker content before the parser reads.
        deleteTree(sheets);
        Files.createDirectories(sheets);
        Files.writeString(sheets.resolve("evil.csv"),
                "uuid,name,balance\n" + ATTACKER + ",Attacker,999999\n", StandardCharsets.UTF_8);

        ImportParseResult result = CmiParser.parse(gated, "coin", 2);

        assertTrue(!containsAttacker(result),
                "parser must not return records from a directory swapped in after the gate, got: "
                        + result.records());
        assertTrue(!result.failures().isEmpty(), "the swap must be reported, got: " + result);
    }

    /**
     * Deterministic reproduction of the Linux failure: a directory deleted and
     * rebuilt after the gate can come back with the same {@code fileKey} on
     * filesystems that recycle inodes, so identity alone cannot tell it apart.
     * The test reproduces that on any filesystem by rebuilding the snapshot
     * with the recreated directory's identity but the original listing
     * fingerprint, which is exactly what the gate approved.
     */
    @Test
    void recreatedDirectoryWithReusedIdentityMustNotLeakAttackerRecords(@TempDir Path dataFolder)
            throws Exception {
        Path dir = importDir(dataFolder);
        Path sheets = dir.resolve("sheets");
        Files.createDirectories(sheets);
        Files.writeString(sheets.resolve("benign.csv"),
                "uuid,name,balance\n" + VICTIM + ",Victim,10\n", StandardCharsets.UTF_8);

        ImportPathGate.GatedImport gated = ImportPathGate.gate(dataFolder, "sheets", ImportSource.CMI);

        // The approved directory is deleted and rebuilt with attacker content.
        deleteTree(sheets);
        Files.createDirectories(sheets);
        Files.writeString(sheets.resolve("evil.csv"),
                "uuid,name,balance\n" + ATTACKER + ",Attacker,999999\n", StandardCharsets.UTF_8);

        // Simulate an inode-reusing filesystem: keep the recreated directory's
        // fileKey so the identity check cannot tell the swap apart, but the
        // original approved listing fingerprint.
        Object reusedFileKey = Files
                .readAttributes(sheets, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS)
                .fileKey();
        ImportPathGate.GatedImport forged = new ImportPathGate.GatedImport(
                gated.path(), reusedFileKey, true, -1, null, null, gated.directoryFingerprint());

        ImportParseResult result = CmiParser.parse(forged, "coin", 2);

        assertTrue(!containsAttacker(result),
                "a rebuilt directory with a reused identity must not leak attacker records, got: "
                        + result.records());
        assertTrue(!result.failures().isEmpty(), "the swap must be reported, got: " + result);
    }

    @Test
    void fileReplacedByDirectoryMustNotParse(@TempDir Path dataFolder) throws Exception {
        Path dir = importDir(dataFolder);
        Path target = dir.resolve("balances.csv");
        Files.writeString(target,
                "uuid,name,balance\n" + VICTIM + ",Victim,10\n", StandardCharsets.UTF_8);

        ImportPathGate.GatedImport gated = ImportPathGate.gate(dataFolder, "balances.csv", ImportSource.CMI);

        // Gate approved a single file; it is replaced by a directory of attacker sheets.
        Files.delete(target);
        Files.createDirectories(target);
        Files.writeString(target.resolve("evil.csv"),
                "uuid,name,balance\n" + ATTACKER + ",Attacker,999999\n", StandardCharsets.UTF_8);

        ImportParseResult result = CmiParser.parse(gated, "coin", 2);

        assertTrue(!containsAttacker(result),
                "a file approved by the gate must not turn into a parsed directory, got: "
                        + result.records());
        assertTrue(!result.failures().isEmpty(), "the swap must be reported, got: " + result);
    }

    @Test
    void essentialsFileReplacedByDirectoryMustNotParse(@TempDir Path dataFolder) throws Exception {
        Path dir = importDir(dataFolder);
        Path target = dir.resolve(VICTIM + ".yml");
        Files.writeString(target, "money: 10\n", StandardCharsets.UTF_8);

        ImportPathGate.GatedImport gated =
                ImportPathGate.gate(dataFolder, VICTIM + ".yml", ImportSource.ESSENTIALS);

        Files.delete(target);
        Files.createDirectories(target);
        Files.writeString(target.resolve(ATTACKER + ".yml"), "money: 999999\n", StandardCharsets.UTF_8);

        ImportParseResult result = EssentialsParser.parse(gated, "coin", 2);

        assertTrue(!containsAttacker(result),
                "a file approved by the gate must not turn into a parsed directory, got: "
                        + result.records());
        assertTrue(!result.failures().isEmpty(), "the swap must be reported, got: " + result);
    }

    @Test
    void directorySwappedBetweenListAndReadMustBeRefused(@TempDir Path dataFolder) throws Exception {
        Path dir = importDir(dataFolder);
        Path sheets = dir.resolve("sheets");
        Files.createDirectories(sheets);
        Files.writeString(sheets.resolve("benign.csv"),
                "uuid,name,balance\n" + VICTIM + ",Victim,10\n", StandardCharsets.UTF_8);

        ImportPathGate.GatedImport gated = ImportPathGate.gate(dataFolder, "sheets", ImportSource.CMI);
        List<ImportPathGate.GatedImport> members = ImportPathGate.listMembersSecure(gated, "sheets");
        assertEquals(1, members.size());

        // Whole directory is swapped after enumeration, keeping the same member
        // name but with attacker content — the stale member snapshot must not be read.
        deleteTree(sheets);
        Files.createDirectories(sheets);
        Files.writeString(sheets.resolve("benign.csv"),
                "uuid,name,balance\n" + ATTACKER + ",Attacker,999999\n", StandardCharsets.UTF_8);

        assertThrows(ImportPathRejectedException.class,
                () -> ImportPathGate.readMemberSecure(gated, members.get(0), "sheets/benign.csv"));
    }

    @Test
    void memberRewrittenInPlaceMustBeRefused(@TempDir Path dataFolder) throws Exception {
        Path dir = importDir(dataFolder);
        Path sheets = dir.resolve("sheets");
        Files.createDirectories(sheets);
        Files.writeString(sheets.resolve("benign.csv"),
                "uuid,name,balance\n" + VICTIM + ",Victim,10\n", StandardCharsets.UTF_8);

        ImportPathGate.GatedImport gated = ImportPathGate.gate(dataFolder, "sheets", ImportSource.CMI);
        List<ImportPathGate.GatedImport> members = ImportPathGate.listMembersSecure(gated, "sheets");
        assertEquals(1, members.size());

        // Same member rewritten in place (identity kept, content changed).
        Files.writeString(sheets.resolve("benign.csv"),
                "uuid,name,balance\n" + ATTACKER + ",Attacker,999999\n", StandardCharsets.UTF_8);

        assertThrows(ImportPathRejectedException.class,
                () -> ImportPathGate.readMemberSecure(gated, members.get(0), "sheets/benign.csv"));
    }

    @Test
    void fileRewrittenInPlaceMustNotParseAttackerContent(@TempDir Path dataFolder) throws Exception {
        Path dir = importDir(dataFolder);
        Path target = dir.resolve("balances.csv");
        Files.writeString(target,
                "uuid,name,balance\n" + VICTIM + ",Victim,10\n", StandardCharsets.UTF_8);

        ImportPathGate.GatedImport gated = ImportPathGate.gate(dataFolder, "balances.csv", ImportSource.CMI);

        // In-place rewrite keeps the same filesystem identity but changes the
        // content (different size); the stale approval must not cover it.
        Files.writeString(target,
                "uuid,name,balance\n" + ATTACKER + ",Attacker,999999\n", StandardCharsets.UTF_8);

        ImportParseResult result = CmiParser.parse(gated, "coin", 2);

        assertFalse(containsAttacker(result),
                "in-place rewrite must not be parsed as approved content, got: "
                        + result.records());
        assertTrue(!result.failures().isEmpty(), "the rewrite must be reported, got: " + result);
    }

    @Test
    void fileSwappedToSymlinkMustNotReadOutside(@TempDir Path dataFolder) throws Exception {        Path dir = importDir(dataFolder);
        Path target = dir.resolve("balances.csv");
        Files.writeString(target,
                "uuid,name,balance\n" + VICTIM + ",Victim,10\n", StandardCharsets.UTF_8);
        Path outside = dataFolder.resolve("outside.csv");
        Files.writeString(outside,
                "uuid,name,balance\n" + ATTACKER + ",Attacker,999999\n", StandardCharsets.UTF_8);

        ImportPathGate.GatedImport gated = ImportPathGate.gate(dataFolder, "balances.csv", ImportSource.CMI);

        Files.delete(target);
        try {
            Files.createSymbolicLink(target, outside);
        } catch (UnsupportedOperationException e) {
            return;
        }

        // The gate itself must refuse the swapped path on re-check ...
        assertThrows(ImportPathRejectedException.class,
                () -> ImportPathGate.gate(dataFolder, "balances.csv", ImportSource.CMI));
        // ... and the parser must not follow it to the outside file either.
        ImportParseResult result = CmiParser.parse(gated, "coin", 2);
        assertTrue(!containsAttacker(result),
                "parser must not follow a symlink swapped in after the gate, got: "
                        + result.records());
        assertEquals(0, result.records().size(),
                "swapped symlink must yield no records, got: " + result.records());
    }

    @Test
    void fileRewrittenSameSizeAndTimestampMustNotParseAttackerContent(@TempDir Path dataFolder)
            throws Exception {
        Path dir = importDir(dataFolder);
        Path target = dir.resolve("balances.csv");
        // Both lines are 52 bytes before the newline: same inode, same size,
        // same timestamp after the restore, but different content.
        Files.writeString(target,
                "uuid,name,balance\n" + VICTIM + ",Victim,000010  \n", StandardCharsets.UTF_8);
        FileTime stamped = Files.getLastModifiedTime(target);

        ImportPathGate.GatedImport gated = ImportPathGate.gate(dataFolder, "balances.csv", ImportSource.CMI);

        Files.writeString(target,
                "uuid,name,balance\n" + ATTACKER + ",Attacker,999999\n", StandardCharsets.UTF_8);
        Files.setLastModifiedTime(target, stamped);

        ImportParseResult result = CmiParser.parse(gated, "coin", 2);

        assertFalse(containsAttacker(result),
                "same-size rewrite with restored timestamp must not be parsed as approved content, got: "
                        + result.records());
        assertTrue(!result.failures().isEmpty(), "the rewrite must be reported, got: " + result);
    }

    @Test
    void memberRewrittenSameSizeAndTimestampMustBeRefused(@TempDir Path dataFolder) throws Exception {
        Path dir = importDir(dataFolder);
        Path sheets = dir.resolve("sheets");
        Files.createDirectories(sheets);
        Files.writeString(sheets.resolve("benign.csv"),
                "uuid,name,balance\n" + VICTIM + ",Victim,000010  \n", StandardCharsets.UTF_8);
        FileTime stamped = Files.getLastModifiedTime(sheets.resolve("benign.csv"));

        ImportPathGate.GatedImport gated = ImportPathGate.gate(dataFolder, "sheets", ImportSource.CMI);
        List<ImportPathGate.GatedImport> members = ImportPathGate.listMembersSecure(gated, "sheets");
        assertEquals(1, members.size());

        // Same member rewritten in place with identical size and timestamp.
        Files.writeString(sheets.resolve("benign.csv"),
                "uuid,name,balance\n" + ATTACKER + ",Attacker,999999\n", StandardCharsets.UTF_8);
        Files.setLastModifiedTime(sheets.resolve("benign.csv"), stamped);

        assertThrows(ImportPathRejectedException.class,
                () -> ImportPathGate.readMemberSecure(gated, members.get(0), "sheets/benign.csv"));
    }

    /**
     * The approved listing must bind member bytes, not just the listing shape:
     * a directory deleted and recreated after the gate can carry a member with
     * the same name, kind, size, timestamp and filesystem identity while its
     * content changed. Keeping the member's inode alive across the rebuild
     * makes that deterministic on any filesystem, and reusing the recreated
     * directory's {@code fileKey} simulates an inode-reusing filesystem for the
     * directory itself.
     */
    @Test
    void rebuiltDirectoryWithSameMemberMetadataButNewContentMustNotLeakAttackerRecords(
            @TempDir Path dataFolder) throws Exception {
        Path dir = importDir(dataFolder);
        Path sheets = dir.resolve("sheets");
        Files.createDirectories(sheets);
        Path member = sheets.resolve("benign.csv");
        // Both rows are the same length, so swapping the content keeps the size.
        Files.writeString(member,
                "uuid,name,balance\n" + VICTIM + ",Victim,000010  \n", StandardCharsets.UTF_8);
        FileTime stamped = Files.getLastModifiedTime(member);

        ImportPathGate.GatedImport gated = ImportPathGate.gate(dataFolder, "sheets", ImportSource.CMI);

        // Rebuild the directory while preserving the member's filesystem
        // identity (the same inode survives the move) and then swap its bytes
        // in place, so every listing field the gate approved stays identical.
        rebuildWithSwappedContent(dataFolder, sheets, member, stamped);

        Object reusedDirKey = Files
                .readAttributes(sheets, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS)
                .fileKey();
        ImportPathGate.GatedImport forged = new ImportPathGate.GatedImport(
                gated.path(), reusedDirKey, true, -1, null, null, gated.directoryFingerprint());

        ImportParseResult result = CmiParser.parse(forged, "coin", 2);

        assertFalse(containsAttacker(result),
                "a rebuilt directory whose members keep every metadata field but change content must "
                        + "not leak attacker records, got: " + result.records());
        assertTrue(!result.failures().isEmpty(), "the swap must be reported, got: " + result);
    }

    /**
     * Whole-directory swap after enumeration: the directory is rebuilt with the
     * same identity (forged, as an inode-reusing filesystem would hand out) and
     * the member keeps its name, size, timestamp and inode, but its bytes are
     * the attacker's. The read stage must still refuse it, matching the reader's
     * whole-directory-swap contract.
     */
    @Test
    void rebuiltDirectoryAfterListingMustBeRefusedAtMemberRead(@TempDir Path dataFolder) throws Exception {
        Path dir = importDir(dataFolder);
        Path sheets = dir.resolve("sheets");
        Files.createDirectories(sheets);
        Path member = sheets.resolve("benign.csv");
        Files.writeString(member,
                "uuid,name,balance\n" + VICTIM + ",Victim,000010  \n", StandardCharsets.UTF_8);
        FileTime stamped = Files.getLastModifiedTime(member);

        ImportPathGate.GatedImport gated = ImportPathGate.gate(dataFolder, "sheets", ImportSource.CMI);
        List<ImportPathGate.GatedImport> members = ImportPathGate.listMembersSecure(gated, "sheets");
        assertEquals(1, members.size());

        rebuildWithSwappedContent(dataFolder, sheets, member, stamped);

        Object reusedDirKey = Files
                .readAttributes(sheets, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS)
                .fileKey();
        ImportPathGate.GatedImport forged = new ImportPathGate.GatedImport(
                gated.path(), reusedDirKey, true, -1, null, null, gated.directoryFingerprint());

        assertThrows(ImportPathRejectedException.class,
                () -> ImportPathGate.readMemberSecure(forged, members.get(0), "sheets/benign.csv"));
    }

    /**
     * The content swap happens before enumeration, so a listing-time snapshot
     * taken from the swapped directory would otherwise become the baseline the
     * reader trusts. The approved listing binds the original bytes, so the
     * attacker content must never leave the gate.
     */
    @Test
    void contentSwapBeforeListingMustNotReturnAttackerContent(@TempDir Path dataFolder) throws Exception {
        Path dir = importDir(dataFolder);
        Path sheets = dir.resolve("sheets");
        Files.createDirectories(sheets);
        Path member = sheets.resolve("benign.csv");
        Files.writeString(member,
                "uuid,name,balance\n" + VICTIM + ",Victim,000010  \n", StandardCharsets.UTF_8);
        FileTime stamped = Files.getLastModifiedTime(member);

        ImportPathGate.GatedImport gated = ImportPathGate.gate(dataFolder, "sheets", ImportSource.CMI);

        // Same inode, same size, same timestamp — only the bytes change.
        Files.writeString(member,
                "uuid,name,balance\n" + ATTACKER + ",Attacker,999999\n", StandardCharsets.UTF_8);
        Files.setLastModifiedTime(member, stamped);

        String read = null;
        try {
            List<ImportPathGate.GatedImport> members = ImportPathGate.listMembersSecure(gated, "sheets");
            read = ImportPathGate.readMemberSecure(gated, members.get(0), "sheets/benign.csv");
        } catch (ImportPathRejectedException expected) {
            // Rejected is the correct outcome: nothing was returned.
        }

        assertNull(read, "attacker content must never be returned, got: " + read);
    }

    /**
     * A directory past the entry cap must be refused, not listed. The gate binds
     * a fingerprint over the whole listing, so a hostile or broken import tree
     * with too many members must fail closed instead of being enumerated. The
     * production cap is {@link ImportPathGate#MAX_DIRECTORY_ENTRIES}; here the
     * same bounded collection runs against a small real directory with a small
     * injected cap, so one entry past the cap trips it without creating 100,001
     * files.
     */
    @Test
    void directoryOverEntryLimitMustBeRejected(@TempDir Path dataFolder) throws Exception {
        Path dir = importDir(dataFolder);
        Path sheets = dir.resolve("sheets");
        Files.createDirectories(sheets);
        int cap = 8;
        for (int i = 0; i <= cap; i++) {
            Files.createFile(sheets.resolve("entry-" + i + ".csv"));
        }

        ImportPathRejectedException rejected;
        try (Stream<Path> stream = Files.list(sheets)) {
            rejected = assertThrows(ImportPathRejectedException.class,
                    () -> ImportPathGate.boundedEntries(stream, "sheets", cap));
        }

        assertTrue(rejected.getMessage().contains("too many entries"),
                "expected the directory-entry cap to reject the listing, got: " + rejected.getMessage());
        assertTrue(rejected.getMessage().contains("max " + cap),
                "expected the injected cap in the message, got: " + rejected.getMessage());
    }

    /**
     * The cap must bound the listing itself, not only the decision that follows
     * it. A lazily generated stream that fails the moment it is pulled past
     * {@code cap + 1} entries stands in for a directory of unbounded size: an
     * implementation that materializes and sorts the whole source before
     * checking the cap pulls the stream to the failing point and cannot pass,
     * while a listing that stops at the cap does. The cap is injected small and
     * the source is a plain stream, so the test needs neither static mocking of
     * {@code Files.list} nor real files.
     */
    @Test
    void entryCapMustBoundTheListingBeforeItIsMaterialized() {
        int cap = 8;
        Stream<Path> entries = Stream.iterate(0, i -> i + 1).map(i -> {
            if (i > cap) {
                throw new AssertionError("the listing pulled more than "
                        + (cap + 1L) + " entries before enforcing the cap");
            }
            return Paths.get("entry-" + i + ".csv");
        });

        ImportPathRejectedException rejected = assertThrows(ImportPathRejectedException.class,
                () -> ImportPathGate.boundedEntries(entries, "sheets", cap));

        assertTrue(rejected.getMessage().contains("too many entries"),
                "expected the entry-cap message, got: " + rejected.getMessage());
        assertTrue(rejected.getMessage().contains("max " + cap),
                "expected the injected cap in the message, got: " + rejected.getMessage());
    }

    /**
     * Rebuilds {@code sheets} while keeping the member's inode alive (the move
     * is a rename) and then overwrites its bytes with the attacker's, restoring
     * the original size and timestamp. The listing metadata and member identity
     * are therefore byte-for-byte what the gate approved; only the content
     * differs.
     */
    private static void rebuildWithSwappedContent(Path dataFolder, Path sheets, Path member, FileTime stamped)
            throws Exception {
        Path stash = dataFolder.resolve("stash");
        Files.createDirectories(stash);
        Files.move(member, stash.resolve("benign.csv"));
        deleteTree(sheets);
        Files.createDirectories(sheets);
        Files.move(stash.resolve("benign.csv"), sheets.resolve("benign.csv"));
        deleteTree(stash);
        Files.writeString(sheets.resolve("benign.csv"),
                "uuid,name,balance\n" + ATTACKER + ",Attacker,999999\n", StandardCharsets.UTF_8);
        Files.setLastModifiedTime(sheets.resolve("benign.csv"), stamped);
    }
}
