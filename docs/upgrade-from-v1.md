# Upgrade from AceEconomy v1

English · [简体中文](upgrade-from-v1.zh-CN.md) · [繁體中文](upgrade-from-v1.zh-TW.md)

Use this guide when replacing an existing v1 installation with AceEconomy v2. v2 is a clean installation, not an in-place schema upgrade: `version: "2.0"`, v2 storage files or tables, and v2 plugin APIs are separate from v1. No v1 configuration, data, or API is converted automatically.

## Contents

- [What changes](#what-changes)
- [Before touching the live server](#before-touching-the-live-server)
- [The cutover](#the-cutover)
- [Rollback](#rollback)
- [After the upgrade](#after-the-upgrade)
- [If v1 data must be carried forward](#if-v1-data-must-be-carried-forward)

## What changes

The v2 runtime requires Java 25, Paper/Folia 26.1.2, and `AceLib-1.2.0.jar`. The v2 plugin is `AceEconomy-2.1.0.jar`. Vault and PlaceholderAPI remain optional integrations. Paper/Folia 26.1.2 is the officially supported server line; Folia 26.2 has been validated only on specific builds (VERIFIED-BETA), and other 26.2 builds are unverified.

The v2 command surface uses explicit subcommands: `/money balance`, `/pay send`, `/withdraw cash`, `/baltop top`, `/bank open`, and `/aceeco` administration commands. Do not use v1-only history, rollback, import, or old banknote data instructions as if they were v2 commands.

If the server still needs v1 balances, keep the complete v1 installation intact as the rollback source until the server owner accepts v2.

## Before touching the live server

1. Schedule a maintenance window and stop the server from its normal console or service control. The Minecraft console command is `stop`.
2. Make a dated, restorable copy of the whole server. At minimum include the v1 `plugins/AceEconomy/` directory, active v1 configuration, v1 AceEconomy JAR, current AceLib JAR, and the server data needed to restore the old installation.
3. Keep the copy outside the live server directory. Do not use it as the working directory for v2 files.

Before proceeding, write down which v1 storage files or database are authoritative. Do not assume that `data-v2.json`, a v1 JSON file, and a SQL database can be exchanged merely because they all contain balances.

## The cutover

### 1. Remove v1 from the live plugin set

With the server stopped, move the old AceEconomy JAR and old AceLib JAR out of the live `plugins/` directory. Keep them in the dated backup rather than deleting them. Do not leave two AceLib versions in `plugins/`.

### 2. Install the v2 pair

Place these files in the live `plugins/` directory:

```text
AceLib-1.2.0.jar
AceEconomy-2.1.0.jar
```

Add Vault and PlaceholderAPI only if the server uses those integrations. Do not add a separate SQLite or MySQL JDBC driver; both are included in the AceEconomy JAR.

### 3. Create a v2 configuration

Let v2 create `plugins/AceEconomy/config.yml`, or replace the generated file with a deliberately written v2 configuration. Confirm that it contains:

```yaml
version: "2.0"
```

Re-enter currencies, starting balance, debt settings, locale, storage selection, leaderboard settings, and optional Discord settings. Do not copy a v1 `config-version` block or assume that v1 currency names and limits were imported.

### 4. Choose the v2 storage

For a file-based server, v2 JSON is the default:

```yaml
storage:
  type: json
```

SQLite uses a new v2 file under the plugin data folder:

```yaml
storage:
  type: sqlite
  sqlite:
    path: data-v2.sqlite
```

MySQL or MariaDB uses the v2 `storage.mysql.*` settings. Keep the password local and make a database backup through the database administrator's normal process before cutover.

The v2 JSON snapshot format has its own schema version. A v1 data file is not a v2 snapshot and must not be renamed to `data-v2.json` or loaded into a v2 backend.

### 5. Start and configure v2

Start the server and wait for `AceEconomy v2.1.0` to enable. Confirm that the chosen v2 storage has opened, then edit generated settings if needed. Use `/aceeco reload` from the console for configuration and language changes; use a full restart after changing plugin files, AceLib, or storage connection settings.

### 6. Check before opening to players

Use a test account and check:

- `/money balance` returns the expected v2 account value.
- `/pay send <player> <amount> [currency]` completes a small transfer.
- `/withdraw cash <amount> [currency]` creates a banknote when that workflow is enabled.
- `/baltop top [currency]` and `/bank open` respond normally.
- Optional Vault, PlaceholderAPI, and Discord behaviour matches the settings enabled.

A clean plugin enable does not prove that v1 balances were migrated. v2 is ready only after the owner has decided how old data will be retained or recreated.

## Rollback

Rollback means restoring the pre-cutover v1 installation. It does not mean asking v1 to read v2 files.

1. Stop the v2 server with `stop` and wait for saving to finish.
2. Make a separate copy of the current v2 `plugins/AceEconomy/` directory and any v2 database backup. Keep it for investigation; do not overwrite the v1 backup.
3. Move `AceEconomy-2.1.0.jar` and `AceLib-1.2.0.jar` out of the live `plugins/` directory.
4. Restore the pre-cutover v1 JARs, v1 configuration, and v1 data from the dated backup.
5. Start the server and confirm that v1 data is readable before allowing players back in.

Never copy `data-v2.json`, `data-v2.sqlite`, or a v2 snapshot into the v1 data location. Keep the v2 copy until the rollback decision is closed.

## After the upgrade

Keep the v1 backup, first v2 backup, and current v2 backup under separate names. Back up `plugins/AceEconomy/` before changing storage or repairing data. For SQL storage, also keep the database backup produced by the database administrator's normal process.

Use `/aceeco reload` for ordinary configuration and language changes. Use a full server restart for a new JAR, new AceLib, `storage.type`, SQLite path, MySQL connection values, or integration plugin changes. Do not use Bukkit `/reload` as the upgrade procedure.

For the operator checklist and regular maintenance, see [Server operations](operations.md).

## If v1 data must be carried forward

The product does not perform automatic v1-to-v2 data migration. Do not improvise by editing JSON, renaming files, or pointing v2 at the v1 storage location. Preserve the original backup and raise a separately scoped data-conversion request with a reversible plan.
