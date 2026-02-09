plugins {
    java
    id("io.papermc.paperweight.userdev") version "2.0.0-beta.14"
    id("maven-publish")
}

group = "com.smile.aceeconomy"
version = "1.0.0"
description = "A Folia-compatible economy plugin"

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
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
    // Paper API (Folia-compatible)
    paperweight.paperDevBundle("1.21.4-R0.1-SNAPSHOT")

    // Vault API (經濟整合介面)
    compileOnly("com.github.MilkBowl:VaultAPI:1.7.1")

    // PlaceholderAPI (佔位符支援)
    compileOnly("me.clip:placeholderapi:2.11.6")
}

tasks {
    compileJava {
        options.encoding = "UTF-8"
        options.release.set(21)
    }

    processResources {
        val props = mapOf(
            "name" to project.name,
            "version" to project.version,
            "description" to project.description,
            "apiVersion" to "1.21"
        )
        inputs.properties(props)
        filesMatching("plugin.yml") {
            expand(props)
        }
    }

    assemble {
        dependsOn(reobfJar)
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
