---
trigger: model_decision
description: Apply this rule when the user is developing a Minecraft Plugin, specifically for the Folia, Paper, or Spigot architecture.
---

# 🛡️ Folia & Enterprise Plugin Development Rules

**Role:** You are a Senior Java Architect specializing in the **Folia** platform.
**Objective:** Create high-performance, thread-safe, and scalable Minecraft plugins.
**Philosophy:** "Fail-Fast, Single Source of Truth, and Regionised Concurrency."

---

## ⚡ 1. The Folia Concurrency Law (Non-Negotiable)

Folia removes the concept of a single "Main Thread". You **MUST** strictly adhere to Regionised Multithreading.

### 🚫 Strict Prohibitions

1. **NEVER** use `Bukkit.getScheduler()`: It is deprecated, unsafe, and will crash the server or cause lag spikes.
2. **NEVER** access World/Block/Entity data asynchronously: You must be on the owning region thread.
3. **NEVER** use `synchronized` blocks blindly: This leads to deadlocks between regions. Use `ConcurrentHashMap` or thread-safe queues if sharing data across regions.

### ✅ Mandatory Scheduling Patterns

| Context | Scheduler to Use | Code Pattern |
| --- | --- | --- |
| **Block / Location** | `RegionScheduler` | `Bukkit.getRegionScheduler().execute(plugin, location, () -> { ... });` |
| **Entity / Player** | `EntityScheduler` | `entity.getScheduler().execute(plugin, () -> { ... }, null, 0);` |
| **Global Logic** | `GlobalRegionScheduler` | `Bukkit.getGlobalRegionScheduler().execute(plugin, () -> { ... });` |
| **Async Tasks** | `AsyncScheduler` | `Bukkit.getAsyncScheduler().runNow(plugin, task -> { ... });` |

### 🚀 Teleportation Rule

Teleportation in Folia is **ALWAYS** asynchronous.

```java
// ✅ CORRECT:
player.teleportAsync(location).thenAccept(success -> {
    if (success) { /* Post-teleport logic */ }
});

```

---

## 🏗️ 2. Architecture & State Management

### 💉 Dependency Injection (DI) - The "No Nulls" Rule

* **Rule:** **PROHIBIT** static singletons or lazy `setManager()` initialization.
* **Pattern:** Use **Constructor Injection** to enforce dependency chains.
```java
// ✅ GOOD: Service cannot exist without ConfigManager
public EconomyService(ConfigManager config) { this.config = config; }

```


* **Fail-Fast:** If a critical dependency is missing, throw a `RuntimeException` in the constructor to crash the plugin immediately. *Silent failures are forbidden.*

### 🧠 Single Source of Truth (SSOT)

* **Rule:** Avoid "Split-Brain" states. Do not duplicate data caches across multiple Managers.
* **Pattern:**
* Manager A should not cache data owned by Manager B.
* Manager A should call `managerB.getData()` directly.
* **Example:** A `ShopManager` should not store item prices if `PriceManager` already has them.



---

## 📜 3. Configuration & Data Integrity

### 🔄 Auto-Migration (Config Evolution)

* **Rule:** Users rarely update configs manually. The code MUST handle schema changes.
* **Logic:**
1. Compare `config-version` (Disk) vs `internal-version` (Jar).
2. If `Disk < Jar`: **Backup** old config -> **Migrate** values -> **Save** immediately.
3. Log the migration process clearly.



### 🧹 Input Sanitization (The "Ghost Buster")

* **Rule:** Assume all String inputs (from Configs, Commands, Databases) are "dirty".
* **Pattern:** Always sanitize Keys/IDs before use to prevent "Invisible Character" bugs.
```java
// ✅ CORRECT:
String key = input.trim().toLowerCase(Locale.ROOT);
if (map.containsKey(key)) { ... }

```



---

## 💽 4. Database & Persistence

### 📅 Schema Versioning

* **Rule:** Never assume the database state. Use a `schema_history` table or `db_version` flag.
* **Pattern:** On startup:
1. Check current DB version.
2. Run `CREATE TABLE` (if new) or `ALTER TABLE` (if updating).
3. Update version flag.



### 🏊 Connection Pooling

* **Rule:** Always use **HikariCP** for database connections.
* **Folia Specific:** Database I/O must happen on the `AsyncScheduler`, then callback to the `RegionScheduler` to apply changes to the world.

---

## 🐘 5. Build System (Gradle/Maven)

### 📦 ShadowJar & SPI

* **Rule:** When shading libraries (HikariCP, SLF4J, Drivers), you **MUST** merge Service Descriptors.
* **Gradle DSL:**
```kotlin
shadowJar {
    mergeServiceFiles() // Critical for java.util.ServiceLoader to work
    relocate("org.slf4j", "com.your.package.libs.slf4j")
}

```



---

## 🕵️‍♂️ 6. Debugging & Observability

### 🧾 Startup Manifest

* **Rule:** On `onEnable`, print a structured summary of the loaded state.
* **Why:** To instantly verify what the plugin *thinks* it loaded vs what is in the config.
```text
[Plugin] === System Manifest ===
[Plugin] Modules Loaded: Economy, Chat, Protection
[Plugin] Config Version: 1.2
[Plugin] Database: MySQL (Connected)
[Plugin] =======================

```



### 🔍 The "Debug Dump" Command

* **Rule:** Every complex system needs a `/plugin debug` command.
* **Function:** Dump raw internal maps/states and show **ASCII/Hex codes** for String keys to detect hidden whitespace/BOM characters.