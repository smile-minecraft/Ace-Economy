# AceEconomy v2.0.0 release

English · [简体中文](release-v2.0.0.zh-CN.md) · [繁體中文](release-v2.0.0.zh-TW.md)

AceEconomy v2.0.0 is the v2 server release for Java 25 and Paper/Folia 26.1.2. It uses `AceLib-1.0.0.jar` as a required dependency and ships as `AceEconomy-2.0.0.jar`.

> **Historical release notes.** The versions on this page describe v2.0.0 as it shipped. For a new installation, use the current release [AceEconomy v2.1.0](release-v2.1.0.md), which requires `AceLib-1.2.0.jar`.

This page is for someone installing or replacing the server plugin. It lists what the release contains, which files must be present, where the v2 data lives, and how to check the file before installing.

## Contents

- [What is included](#what-is-included)
- [Files and dependencies](#files-and-dependencies)
- [Configuration and data](#configuration-and-data)
- [Commands](#commands)
- [Upgrade and rollback](#upgrade-and-rollback)
- [Verify the release file](#verify-the-release-file)

For a first installation, start with [`admin-install-runbook.md`](admin-install-runbook.md). For a v1 replacement, use [`upgrade-from-v1.md`](upgrade-from-v1.md). Daily maintenance is covered in [`operations.md`](operations.md).

## What is included

- JSON, SQLite, and MySQL/MariaDB storage for v2 data.
- Multiple currencies, starting balances, debt limits, transfers, administrative balance changes, transaction records, banknotes, the bank menu, and balance leaderboards.
- Optional Vault and PlaceholderAPI integration.
- Optional Discord transaction notifications using a local webhook setting.
- English, Traditional Chinese, and Simplified Chinese language files.

## Files and dependencies

Put these files in `plugins/`:

```text
AceLib-1.0.0.jar
AceEconomy-2.0.0.jar
```

AceLib is required. Vault and PlaceholderAPI are optional and are detected when enabled. SQLite and MySQL JDBC drivers are included in the AceEconomy JAR; no additional driver file is required.

Do not keep `AceLib-0.5.0-SNAPSHOT.jar` or another AceLib version beside v2.

## Configuration and data

The active configuration is `plugins/AceEconomy/config.yml` with `version: "2.0"`. JSON is the default backend and uses `plugins/AceEconomy/data-v2.json`. SQLite uses the file named by `storage.sqlite.path` under the plugin data folder. MySQL/MariaDB uses the `storage.mysql.*` block.

v1 configuration and data are not migrated automatically. A v1 file must not be renamed to a v2 file. Keep the complete pre-upgrade backup if a rollback may be needed.

The installation and operations guides above contain the settings and backup steps needed by a server administrator. Keep passwords and webhook URLs as local values; public examples must use placeholders.

## Commands

The v2 commands use these explicit forms:

| Command | Use |
| --- | --- |
| `/money balance [player] [currency]` | Check a balance |
| `/pay send <player> <amount> [currency]` | Transfer funds |
| `/withdraw cash <amount> [currency]` | Create a banknote |
| `/baltop top [currency]` | Show the leaderboard |
| `/bank open` | Open the bank menu |
| `/aceeco give <player> <amount> [currency]` | Add balance |
| `/aceeco take <player> <amount> [currency]` | Remove balance |
| `/aceeco set <player> <amount> [currency]` | Set balance |
| `/aceeco reload` | Reload config and language files from the console |

`/aceeco reload` is not a replacement for a restart after changing a plugin JAR, AceLib, storage backend, database connection, or optional plugin set.

## Upgrade and rollback

Stop the server, back up the complete v1 installation, install the v2 JAR pair, and create a v2 configuration. Do not point v2 at v1 storage. If rollback is required, stop v2, preserve a copy of the v2 data, and restore the pre-upgrade v1 JARs, configuration, and data from the dated backup.

The full procedure is [`upgrade-from-v1.md`](upgrade-from-v1.md). Do not copy `data-v2.json`, `data-v2.sqlite`, or a v2 snapshot into a v1 data location.

## Verify the release file

When a `SHA256SUMS` asset is supplied with the release, place it beside `AceEconomy-2.0.0.jar` and verify the bare filename entry:

```text
sha256sum -c SHA256SUMS
```

On macOS, calculate the value with:

```text
shasum -a 256 AceEconomy-2.0.0.jar
```

Compare the first column with the `AceEconomy-2.0.0.jar` line in `SHA256SUMS` before placing the file on the live server.
