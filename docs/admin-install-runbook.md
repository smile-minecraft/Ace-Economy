# AceEconomy v2 installation runbook

English · [简体中文](admin-install-runbook.zh-CN.md) · [繁體中文](admin-install-runbook.zh-TW.md)

This runbook is for the person who looks after a Paper or Folia server and wants to put AceEconomy v2 into service without guessing which file belongs where. Follow it for a fresh installation, or use [`upgrade-from-v1.md`](upgrade-from-v1.md) when replacing a v1 server.

## Contents

- [What you need](#what-you-need)
- [Install in a maintenance window](#install-in-a-maintenance-window)
- [If the first start does not look right](#if-the-first-start-does-not-look-right)
- [Next reading](#next-reading)

## What you need

Use a Java 25 server running Paper or Folia 26.1.2. AceEconomy requires `AceLib-1.2.0.jar`; Vault and PlaceholderAPI are optional. The SQLite and MySQL JDBC drivers are already included in `AceEconomy-2.1.0.jar`, so do not download separate driver JARs.

Paper/Folia 26.1.2 is the officially supported server line. Folia 26.2 has been validated only on specific builds (VERIFIED-BETA); other 26.2 builds are unverified.

Prepare these two plugin files:

```text
plugins/AceLib-1.2.0.jar
plugins/AceEconomy-2.1.0.jar
```

Do not leave `AceLib-0.5.0-SNAPSHOT.jar` or another AceLib version in `plugins/`. Two AceLib versions can make the server report an ambiguous dependency and prevent a clean start.

### AceLib v1.2.0 download and checksum

Download `AceLib-1.2.0.jar` from the AceLib v1.2.0 GitHub Release: <https://github.com/smile-minecraft/AceLib/releases/tag/v1.2.0>.

Before placing the JAR in `plugins/`, verify it against the published SHA-256:

```text
da9f196b47c2b28c6db443d102236b27c1a1bbdf7dd3e7c22470170420935278  AceLib-1.2.0.jar
```

Calculate the local digest and compare it exactly:

```text
shasum -a 256 AceLib-1.2.0.jar   # macOS
sha256sum AceLib-1.2.0.jar       # Linux
```

Do not install the JAR if the digest does not match.

## Install in a maintenance window

### 1. Stop the server and make a backup

Stop the Minecraft server from its normal console or service control. The console command is:

```text
stop
```

Wait until the process has exited and world saving has finished. Before copying plugin files, make a backup of the server data and at least the complete `plugins/AceEconomy/` directory. Keep that backup outside the live server directory and label it with the date.

On a fresh installation the directory may not exist yet. That is fine; the important part is to have a restorable copy of the server before the first production start.

### 2. Check the dependency set

Remove old or duplicate AceLib files from the live `plugins/` directory, but keep them in the backup if they belong to the previous installation. Place exactly `AceLib-1.2.0.jar` and `AceEconomy-2.1.0.jar` in `plugins/`.

If you use integrations, place Vault and/or PlaceholderAPI in the same `plugins/` directory. AceEconomy starts without either optional plugin, so do not treat their absence as an installation failure.

### 3. Start once and let v2 create its files

Start the server normally. On the first successful start, AceEconomy creates its v2 configuration and language files under `plugins/AceEconomy/`. With the default JSON storage it also creates:

```text
plugins/AceEconomy/config.yml
plugins/AceEconomy/lang/en_US.yml
plugins/AceEconomy/lang/zh_TW.yml
plugins/AceEconomy/lang/zh_CN.yml
plugins/AceEconomy/data-v2.json
```

For SQLite, set `storage.type: sqlite` before the start that should create the database. The default file is `plugins/AceEconomy/data-v2.sqlite`.

### 4. Configure storage and server behaviour

Open `plugins/AceEconomy/config.yml` while the server is stopped. The file must be a v2 file with `version: "2.0"`; do not paste a v1 `config-version` block into it. The following are the storage shapes supported by v2.

JSON is the default and needs no connection details:

```yaml
storage:
  type: json
```

SQLite keeps its file inside the plugin data folder:

```yaml
storage:
  type: sqlite
  sqlite:
    path: data-v2.sqlite
```

For MySQL or MariaDB, keep the password local and replace the placeholder before starting:

```yaml
storage:
  type: mysql
  mysql:
    host: "<database-host>"
    port: 3306
    database: "<database-name>"
    username: "<database-user>"
    password: "<set-locally>"
    pool-size: 10
    max-lifetime: 1800000
```

`pool-size` and `max-lifetime` belong under `storage.mysql`. The plugin supplies the JDBC driver; do not add a separate MySQL or SQLite driver to `plugins/`.

You can also set `settings.locale`, `start-balance`, the `currencies.*` entries, `economy.allow-negative-balance`, `economy.default-debt-limit`, and the `leaderboard.*` settings. The snippets above show the storage keys that matter during installation; keep all secrets and webhook URLs out of shared documents.

### 5. Start again and read the console

Start the server after saving the configuration. Look for an enable message containing `AceEconomy v2.1.0`, and confirm that the server continues to its normal ready state. Also check that there is only one `AceLib` version enabled.

If AceEconomy disables itself, stop opening the server to players. Keep the first error and the nearby AceEconomy/AceLib lines; the [troubleshooting guide](troubleshooting.md) explains what to check next.

### 6. Run the basic operator checks

Run these from the server console where the command is console-safe, and use a test player for player-only commands. The explicit subcommand forms below are the v2 command surface.

```text
/money balance
/baltop top
/aceeco give <player> <amount> [currency]
/aceeco take <player> <amount> [currency]
/aceeco set <player> <amount> [currency]
/aceeco history [player] [currency] [page]
/aceeco reload
```

`/aceeco rollback` is deliberately not part of the routine checklist above. It is a destructive, console-only administrative action: run it only with both `aceeconomy.admin` and `aceeconomy.admin.rollback`, a valid transaction UUID, and explicit human approval or a dedicated drill — never as an automated or casual smoke check.

`/aceeco rollback <transaction-id>` is also available from the console. It is a destructive administrative action that reverses a recorded transaction, so do not run it as part of routine installation checks; keep it for incident handling. It requires both `aceeconomy.admin` and `aceeconomy.admin.rollback`, rejects players and invalid UUIDs up front, reports the reversal audit record ids on success, treats an already reverted transaction as an explicit no-op, and reports a marker persist failure as needing manual reconciliation.

With a test player, also check:

```text
/pay send <player> <amount> [currency]
/withdraw cash <amount> [currency]
/bank open
```

`/aceeco reload` is for the console and reloads the configuration and language files. A successful reload reports `AceEconomy reloaded`. A full restart is still required after changing the plugin JAR, AceLib, the storage backend, or database connection details.

### 7. Open the server to players

Only open the server after the enable message, the expected storage file or database connection, and the basic commands all look correct. Test one balance lookup and one small transfer before announcing the server is ready.

After opening the server, keep the dated pre-install backup and the v2 configuration backup. Do not overwrite either one with a copy containing secrets in a shared location.

## If the first start does not look right

Use [`troubleshooting.md`](troubleshooting.md) by symptom. In particular, check the following before changing data:

- `AceLib-1.2.0.jar` is present and no older AceLib JAR is active.
- `config.yml` contains `version: "2.0"` and a valid `storage.type`.
- A SQLite path stays under `plugins/AceEconomy/`.
- A MySQL password and webhook URL were set locally, not copied into a ticket or public post.

Do not delete `data-v2.json`, a SQLite file, or a database simply because the first start failed. Take a copy first; deletion is a recovery decision, not a routine installation step.

## Next reading

- [`upgrade-from-v1.md`](upgrade-from-v1.md): replace a v1 installation and keep a safe rollback path.
- [`operations.md`](operations.md): routine backups, reloads, restarts, and integrations.
- [`release-v2.1.0.md`](release-v2.1.0.md): version requirements and the v2 feature overview.
