# AceEconomy troubleshooting

English · [简体中文](troubleshooting.zh-CN.md) · [繁體中文](troubleshooting.zh-TW.md)

Start with the symptom visible in the server console or in-game. Copy the relevant configuration and data before changing storage files. Replace placeholders locally; never paste a password or Discord webhook into a ticket or public message.

## Contents

- [The plugin does not enable](#the-plugin-does-not-enable)
- [Java, Paper, or Folia mismatch](#java-paper-or-folia-mismatch)
- [The storage file is missing or the wrong backend opens](#the-storage-file-is-missing-or-the-wrong-backend-opens)
- [SQLite path rejected](#sqlite-path-rejected)
- [MySQL or Hikari connection failure](#mysql-or-hikari-connection-failure)
- [Discord notifications do not arrive](#discord-notifications-do-not-arrive)
- [Vault or PlaceholderAPI integration is unavailable](#vault-or-placeholderapi-integration-is-unavailable)
- [Configuration reload fails](#configuration-reload-fails)
- [Reload, restart, or stop behaves unexpectedly](#reload-restart-or-stop-behaves-unexpectedly)
- [A balance or transaction looks wrong](#a-balance-or-transaction-looks-wrong)
- [What to send when it still fails](#what-to-send-when-it-still-fails)

## The plugin does not enable

**Possible causes:** `AceLib` is missing, the wrong AceLib version is present, or the server is not running the required Java/Paper/Folia combination.

**Check first:**

- Confirm that `plugins/AceLib-1.2.0.jar` and `plugins/AceEconomy-2.1.0.jar` exist.
- Remove `AceLib-0.5.0-SNAPSHOT.jar` and any duplicate AceLib JAR from the live plugin folder.
- Check the first AceLib or Java error in the console, not only the final disable message.

**Fix:** use Java 25 with Paper/Folia 26.1.2, install one `AceLib-1.2.0.jar`, and perform a full server restart. Do not try to fix a missing hard dependency with `/aceeco reload`.

## Java, Paper, or Folia mismatch

**Possible causes:** a different Java major version, an unsupported server build, or a Paper/Folia installation that does not expose the required API.

**Check first:** confirm the process uses Java 25 and the server is Paper/Folia 26.1.2. Keep startup lines identifying Java, the server, AceLib, and AceEconomy.

**Fix:** correct the service's Java selection or server installation, then restart. Do not replace the plugin JAR with an older copy just to bypass the error.

## The storage file is missing or the wrong backend opens

**Possible causes:** `storage.type` does not match the file, the server has not completed a successful start, or the file is outside the plugin data folder.

**Check first:** open `plugins/AceEconomy/config.yml` and verify:

```yaml
storage:
  type: json       # json, sqlite, or mysql
```

JSON uses `plugins/AceEconomy/data-v2.json`. SQLite uses the file named by `storage.sqlite.path`, which must remain under `plugins/AceEconomy/`. MySQL does not create a local database file.

**Fix:** correct the nested YAML shape, start the server again, and inspect the first storage message. Do not rename a v1 file to `data-v2.json`.

## SQLite path rejected

**Possible causes:** `storage.sqlite` is a scalar instead of a map, or the path escapes the plugin data folder with `../` or an absolute path.

The shape must be:

```yaml
storage:
  type: sqlite
  sqlite:
    path: data-v2.sqlite
```

**Fix:** use a relative filename or subdirectory below `plugins/AceEconomy/`, then perform a full restart. Keep any existing SQLite file before changing its path.

## MySQL or Hikari connection failure

**Possible causes:** the host, port, database, user, password, or database permissions are wrong; the database is unreachable; or pool values are under the wrong YAML block.

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

Check the database server and credentials through the database administrator's normal procedure. `pool-size` and `max-lifetime` belong under `storage.mysql`; the JDBC driver is already in the plugin JAR.

**Fix:** correct the values, keep the password local, and restart. If the error continues, do not delete v2 data; provide the sanitized connection shape and the first database error.

## Discord notifications do not arrive

**Possible causes:** `discord.enabled` is false, the webhook URL is empty or invalid, or Discord rejected the request.

```yaml
discord:
  enabled: true
  webhook-url: "<discord-webhook-url>"
```

Verify the URL locally and check surrounding Discord messages. Never include the real URL in a support request.

**Fix:** correct the two keys and use `/aceeco reload`; restart if the plugin or integration set changed. Discord delivery is asynchronous and best-effort. A failed notification does not undo an already completed economy transaction, so check the player balance and transaction result separately.

## Vault or PlaceholderAPI integration is unavailable

**Possible causes:** the optional plugin is missing, disabled, or not ready when AceEconomy starts.

**Check first:** confirm the optional plugin itself is enabled, then restart the server so AceEconomy restarts. Vault uses the configured default currency. PlaceholderAPI uses the `aceeco` namespace, including `%aceeco_balance%`, `%aceeco_balance_formatted%`, `%aceeco_balance_<currency>%`, and `%aceeco_balance_<currency>_formatted%`.

**Fix:** install or enable the matching optional plugin and restart. If core economy commands work while the integration does not, keep the core service open and troubleshoot the optional plugin separately.

## Configuration reload fails

**Possible causes:** invalid YAML, a wrong v2 key shape, an invalid value, or a language file that cannot be loaded.

**Check first:** review the last edit in `plugins/AceEconomy/config.yml` and the selected file in `plugins/AceEconomy/lang/`. Ensure the config still contains `version: "2.0"` and that `storage.sqlite` and `storage.mysql` are maps when used.

**Fix:** restore the last known-good edit and run `/aceeco reload` again. A failed reload keeps the last valid in-memory configuration; do not assume a partially edited file is active. Use a full restart only after the file is valid or when the changed setting belongs to startup storage or dependency setup.

## Reload, restart, or stop behaves unexpectedly

Distinguish the three operations:

- `/aceeco reload` reloads configuration and language files.
- A full server restart reopens the storage backend and reloads plugin dependencies.
- `stop` performs a normal server shutdown; allow saving to finish.

**Fix:** use a full restart after changing JARs, AceLib, `storage.type`, database connection values, or optional plugin availability. Do not use Bukkit `/reload` for a production upgrade or recovery.

## A balance or transaction looks wrong

**Possible causes:** a different currency was used, the wrong player was targeted, or the server opened a different v2 backend than expected.

**Check first:** record the exact command without passwords, the currency ID, player UUID or name, active `storage.type`, and operation time. Query again with `/money balance <player> <currency>` and inspect the server log around the transaction.

**Fix:** stop further balance changes until the backend and currency are confirmed. Restore only from a known-good v2 backup and only while the server is stopped. Do not load a v1 file into v2 or retry an unknown repair on the live store.

## What to send when it still fails

Send a short, sanitized report containing:

1. AceEconomy, AceLib, Java, and Paper/Folia versions.
2. The symptom and the exact time it started.
3. The relevant command, with player names and secrets replaced where needed.
4. The active `storage.type` and relevant key names, but not passwords, tokens, or webhook URLs.
5. The first AceEconomy/AceLib/storage error and the lines immediately before it.

Keep the original data and configuration backups available. Do not delete the data file to “clean up” the server before someone has reviewed the report.
