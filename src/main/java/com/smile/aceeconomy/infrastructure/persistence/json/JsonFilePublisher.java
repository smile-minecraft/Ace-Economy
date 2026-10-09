package com.smile.aceeconomy.infrastructure.persistence.json;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Minimal injectable seam for the JSON backend publish path (temporary file,
 * durability sync, atomic replacement, directory sync).
 *
 * <p>The production implementation delegates to the real filesystem. Tests
 * substitute a scripted fake to simulate pre-publish failures, post-publish
 * sync failures, and filesystems without atomic-move support. The seam covers
 * only the publish path; ordinary reads and directory creation stay on
 * {@code java.nio.file.Files} directly.
 */
public interface JsonFilePublisher {

    /** Creates a temporary file inside {@code dir}, like {@code Files.createTempFile}. */
    Path createTempFile(Path dir, String prefix, String suffix) throws IOException;

    /** Writes the full snapshot text to the temporary file. */
    void writeString(Path tmp, String content) throws IOException;

    /**
     * Forces buffered content of {@code file} to the storage device
     * (equivalent of fsync on the file).
     */
    void syncFile(Path file) throws IOException;

    /**
     * Replaces {@code target} with {@code tmp} atomically. Must either perform
     * an atomic replacement or throw {@link java.nio.file.AtomicMoveNotSupportedException};
     * silent non-atomic replacement is not allowed.
     */
    void moveAtomic(Path tmp, Path target) throws IOException;

    /**
     * Forces the directory entry change to the storage device (equivalent of
     * fsync on the directory), so the rename itself survives a crash.
     */
    void syncDirectory(Path dir) throws IOException;
}
