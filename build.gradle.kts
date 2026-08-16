plugins {
    java
    id("io.papermc.paperweight.userdev") version "2.0.0-beta.21"
    id("com.gradleup.shadow") version "9.6.1"
    id("maven-publish")
}

group = "com.smile.aceeconomy"
version = "1.4.0"
description = "A Folia-compatible economy plugin"

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
    withSourcesJar()
    withJavadocJar()
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://jitpack.io") // Vault API
    maven("https://repo.extendedclip.com/releases/") // PlaceholderAPI
}

dependencies {
    // Paper / Folia API (Folia-compatible) — 26.1.2 dev bundle
    paperweight.paperDevBundle("26.1.2.build.74-stable")

    // AceLib v1.0.0 (Folia-first base library) — provided at runtime by the server, never shaded
    compileOnly("com.github.smile-minecraft:AceLib:v1.0.0")

    // Vault API (經濟整合介面)
    compileOnly("com.github.MilkBowl:VaultAPI:1.7.1")

    // PlaceholderAPI (佔位符支援)
    compileOnly("me.clip:placeholderapi:2.11.6")

    // HikariCP (資料庫連線池)
    implementation("com.zaxxer:HikariCP:5.1.0")

    // SLF4J (HikariCP 日誌 - 使用 NOP 禁止輸出)
    implementation("org.slf4j:slf4j-api:2.0.9")
    implementation("org.slf4j:slf4j-nop:2.0.9")

    // Testing
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.0")
    // Gradle 9.x no longer auto-provides the JUnit Platform launcher on the test runtime classpath.
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.10.0")
    testImplementation("org.yaml:snakeyaml:2.2") // For testing YAML files
    testImplementation("org.mockito:mockito-core:5.23.0")
    testImplementation("org.mockito:mockito-junit-jupiter:5.23.0")
    testImplementation("com.github.MilkBowl:VaultAPI:1.7.1") // Vault on test classpath for Mockito inline mock maker
    // AceLib on the test classpath so the consumer-foundation smoke test can exercise the public API surface.
    // This is test-only and is NOT part of the production/runtime classpath, so it is never shaded into the plugin JAR.
    testImplementation("com.github.smile-minecraft:AceLib:v1.0.0")

    // SQLite JDBC driver for real, offline persistence contract tests (test-only; never shaded into the plugin).
    testImplementation("org.xerial:sqlite-jdbc:3.47.0.0")
}

tasks.test {
    useJUnitPlatform()
}

tasks {
    compileJava {
        options.encoding = "UTF-8"
        options.release.set(25)
    }

    javadoc {
        options.encoding = "UTF-8"
        (options as StandardJavadocDocletOptions).addStringOption("Xdoclint:none", "-quiet")
    }

    processResources {
        val props = mapOf(
            "name" to project.name,
            "version" to project.version,
            "description" to project.description,
            "apiVersion" to "1.26"
        )
        inputs.properties(props)
        filesMatching("plugin.yml") {
            expand(props)
        }
    }

    // jar 任務加上 classifier 避免與 shadowJar 衝突
    jar {
        archiveClassifier.set("slim")
    }

    // Customize shadowJar
    shadowJar {
        // 1. Relocate packages to avoid conflicts
        relocate("com.zaxxer.hikari", "com.smile.aceeconomy.libs.hikari")
        relocate("org.slf4j", "com.smile.aceeconomy.libs.slf4j")

        // 🔥 關鍵修正：必須合併 Service Files，否則 Relocate 後 SLF4J 2.x 會找不到 Provider
        mergeServiceFiles()

        // 2. IMPORTANT: Remove the "-all" classifier so this JAR replaces the default one
        archiveClassifier.set("")

        // 3. Minimize jar but exclude NOP from being removed
        minimize {
            exclude(dependency("org.slf4j:slf4j-nop"))
            exclude(dependency("org.slf4j:slf4j-api"))
        }

        // 排除不需要的 metadata
        exclude("META-INF/*.SF")
        exclude("META-INF/*.DSA")
        exclude("META-INF/*.RSA")
    }

    // Disable Default Jar or make it depend on shadowJar
    assemble {
        dependsOn(shadowJar)
    }
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            groupId = "com.smile.aceeconomy"
            artifactId = "AceEconomy"
            version = project.version.toString()

            from(components["java"])
        }
    }
}