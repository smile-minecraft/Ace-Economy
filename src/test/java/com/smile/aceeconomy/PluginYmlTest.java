package com.smile.aceeconomy;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class PluginYmlTest {

    @Test
    public void testPluginYmlContents() throws Exception {
        Path path = Paths.get("src/main/resources/plugin.yml");
        assertTrue(Files.exists(path), "plugin.yml not found at src/main/resources/plugin.yml");

        List<String> lines = Files.readAllLines(path);

        boolean authorCorrect = false;
        boolean foliaSupported = false;
        boolean apiVersionExists = false;

        for (String line : lines) {
            line = line.trim();
            if (line.startsWith("author: Smile")) {
                authorCorrect = true;
            }
            if (line.startsWith("folia-supported: true")) {
                foliaSupported = true;
            }
            if (line.startsWith("api-version:")) {
                // Just check key exists because value is placeholder ${apiVersion}
                apiVersionExists = true;
            }
        }

        assertTrue(authorCorrect, "plugin.yml must contain 'author: Smile'");
        assertTrue(foliaSupported, "plugin.yml must contain 'folia-supported: true'");
        assertTrue(apiVersionExists, "plugin.yml must contain 'api-version'");
    }
}
