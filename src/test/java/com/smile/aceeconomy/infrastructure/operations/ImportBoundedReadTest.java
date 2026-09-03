package com.smile.aceeconomy.infrastructure.operations;

import com.smile.aceeconomy.ports.operations.ImportSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Bounded-read regression: a file that passes the size pre-check but grows
 * past the limit while it is being read must be refused without buffering the
 * oversized content. The read stops just past {@code MAX_FILE_BYTES} instead
 * of trusting a pre-read size probe.
 */
class ImportBoundedReadTest {

    private static final int CHUNK = 64 * 1024;

    /**
     * A stream that never runs out, standing in for a file that keeps growing
     * while it is read. Counts every byte pulled so the test can prove the
     * read stopped at the bound instead of draining the stream.
     */
    private static class GrowingStream extends InputStream {
        private final AtomicLong pulled = new AtomicLong();

        @Override
        public int read(byte[] buffer, int offset, int length) {
            pulled.addAndGet(length);
            Arrays.fill(buffer, offset, offset + length, (byte) 'a');
            return length;
        }

        @Override
        public int read() {
            pulled.incrementAndGet();
            return 'a';
        }

        long pulled() {
            return pulled.get();
        }
    }

    @Test
    void growingStreamIsRejectedWithoutUnboundedBuffering() {
        GrowingStream growing = new GrowingStream();

        ImportPathRejectedException rejected = assertThrows(ImportPathRejectedException.class,
                () -> ImportPathGate.readBoundedBytes(growing, "balances.csv"));

        assertTrue(rejected.getMessage().contains("too large"),
                "rejection must name the size bound, got: " + rejected.getMessage());
        assertTrue(growing.pulled() <= ImportPathGate.MAX_FILE_BYTES + 256 * 1024,
                "bounded read must stop just past the limit, pulled: " + growing.pulled());
    }

    @Test
    void exactLimitInputRemainsReadable() throws Exception {
        GrowingStream exact = new GrowingStream() {
            private long remaining = ImportPathGate.MAX_FILE_BYTES;

            @Override
            public int read(byte[] buffer, int offset, int length) {
                if (remaining <= 0) {
                    return -1;
                }
                int take = (int) Math.min(length, remaining);
                remaining -= take;
                Arrays.fill(buffer, offset, offset + take, (byte) 'a');
                return take;
            }
        };

        byte[] bytes = ImportPathGate.readBoundedBytes(exact, "balances.csv");

        assertEquals(ImportPathGate.MAX_FILE_BYTES, bytes.length);
    }

    @Test
    void exactLimitFileRemainsReadable(@TempDir Path dataFolder) throws Exception {
        Path dir = dataFolder.resolve("import");
        Files.createDirectories(dir);
        Path target = dir.resolve("balances.csv");
        byte[] chunk = new byte[CHUNK];
        Arrays.fill(chunk, (byte) 'a');
        try (OutputStream out = Files.newOutputStream(target)) {
            long remaining = ImportPathGate.MAX_FILE_BYTES;
            while (remaining > 0) {
                int take = (int) Math.min(CHUNK, remaining);
                out.write(chunk, 0, take);
                remaining -= take;
            }
        }

        ImportPathGate.GatedImport gated = ImportPathGate.gate(dataFolder, "balances.csv", ImportSource.CMI);
        String content = ImportPathGate.readRootFileSecure(gated, "balances.csv");

        assertEquals(ImportPathGate.MAX_FILE_BYTES, content.length());
    }

    @Test
    void fileGrownPastLimitAfterApprovalIsRejected(@TempDir Path dataFolder) throws Exception {
        Path dir = dataFolder.resolve("import");
        Files.createDirectories(dir);
        Path target = dir.resolve("balances.csv");
        Files.writeString(target, "uuid,name,balance\n", StandardCharsets.UTF_8);

        ImportPathGate.GatedImport gated = ImportPathGate.gate(dataFolder, "balances.csv", ImportSource.CMI);

        // The approval covered a small file; it grows past the limit before
        // the read. The read must fail closed, never buffer the oversized
        // content into memory.
        byte[] chunk = new byte[CHUNK];
        Arrays.fill(chunk, (byte) 'a');
        try (OutputStream out = Files.newOutputStream(target)) {
            long remaining = ImportPathGate.MAX_FILE_BYTES + 1;
            while (remaining > 0) {
                int take = (int) Math.min(CHUNK, remaining);
                out.write(chunk, 0, take);
                remaining -= take;
            }
        }

        assertThrows(ImportPathRejectedException.class,
                () -> ImportPathGate.readRootFileSecure(gated, "balances.csv"));
    }
}
