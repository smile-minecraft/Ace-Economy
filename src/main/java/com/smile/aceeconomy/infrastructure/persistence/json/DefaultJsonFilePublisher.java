package com.smile.aceeconomy.infrastructure.persistence.json;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/**
 * Production {@link JsonFilePublisher} on the real filesystem.
 */
public class DefaultJsonFilePublisher implements JsonFilePublisher {

    @Override
    public Path createTempFile(Path dir, String prefix, String suffix) throws IOException {
        return Files.createTempFile(dir, prefix, suffix);
    }

    @Override
    public void writeString(Path tmp, String content) throws IOException {
        Files.writeString(tmp, content, StandardCharsets.UTF_8);
    }

    @Override
    public void syncFile(Path file) throws IOException {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            channel.force(true);
        }
    }

    @Override
    public void moveAtomic(Path tmp, Path target) throws IOException {
        Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    @Override
    public void syncDirectory(Path dir) throws IOException {
        try (FileChannel channel = FileChannel.open(dir, StandardOpenOption.READ)) {
            channel.force(true);
        }
    }
}
