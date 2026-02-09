plugins {
    java
    id("io.papermc.paperweight.userdev") version "2.0.0-beta.14"
    id("com.gradleup.shadow") version "8.3.5"
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

    // HikariCP (資料庫連線池)
    implementation("com.zaxxer:HikariCP:5.1.0")

    // SLF4J (HikariCP 日誌 - 使用 NOP 禁止輸出)
    implementation("org.slf4j:slf4j-nop:2.0.9")

    // Testing
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.0")
    testImplementation("org.mockito:mockito-core:5.11.0")
    testImplementation("org.mockito:mockito-junit-jupiter:5.11.0")
}

tasks.test {
    useJUnitPlatform()
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

    // jar 任務加上 classifier 避免與 shadowJar 衝突
    jar {
        archiveClassifier.set("slim")
    }

    shadowJar {
        archiveClassifier.set("")

        // Relocate HikariCP 和 SLF4J 避免衝突
        relocate("com.zaxxer.hikari", "com.smile.aceeconomy.libs.hikari")
        relocate("org.slf4j", "com.smile.aceeconomy.libs.slf4j")

        // 排除不需要的 metadata
        exclude("META-INF/*.SF")
        exclude("META-INF/*.DSA")
        exclude("META-INF/*.RSA")
    }

    // 先執行 shadowJar，再進行 reobf
    reobfJar {
        inputJar.set(shadowJar.flatMap { it.archiveFile })
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
