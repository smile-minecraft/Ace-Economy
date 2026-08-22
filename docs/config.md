# Configuration Guide

This guide explains how to configure AceEconomy for your server.

> **v2.0.0 notice:** Java 25 and `AceLib v1.0.0` are required. Vault and PlaceholderAPI are optional integrations. v1 config and data are not migrated automatically. This version is compiled against the Paper/Folia API `26.1.2 build 74`; Folia `26.1.2` has not been verified on a real server running the same version. For complete installation and rollback instructions, see [`docs/release-v2.0.0.md`](release-v2.0.0.md).

---

## Database Setup

AceEconomy supports JSON, SQLite (file-based), and MySQL/MariaDB. JSON is the default storage backend for v2.0.0.

### JSON (Default)

Fresh installs use `data-v2.json` in the plugin data folder.

```yaml
storage:
  type: json
```

### SQLite

SQLite stores its database file in the plugin data folder. The path can be set under `storage.sqlite`.

```yaml
storage:
  type: sqlite
  sqlite:
    path: data-v2.sqlite
```

### MySQL / MariaDB

Use the following shape for a MySQL or MariaDB connection. `pool-size` and `max-lifetime` belong under `storage.mysql`; live MySQL connections have not been tested for this release.

```yaml
storage:
  type: mysql
  mysql:
    host: "localhost"
    port: 3306
    database: "aceeconomy"
    username: "your_user"
    password: ""
    pool-size: 10
    max-lifetime: 1800000
```

Set the password locally. Leave it empty in shared documentation, or use the placeholder `<set-locally>` in a local copy.

---

### v1 Is Not Automatically Migrated

v2.0.0 does not automatically migrate v1 config or data. v2 uses `version: '2.0'`, `data-v2.json` for JSON storage, `data-v2.sqlite` for SQLite when selected, and the v2 schema for MySQL.

Back up the old installation before upgrading, then check the new configuration and data in a copy first. Do not assume that balances or currency settings will be converted.

---

## Multi-Currency System

AceEconomy allows you to define unlimited custom currencies. The system will use the currency marked `default: true` for Vault integration and general commands like `/pay`.

```yaml
currencies:
  dollar:
    name: "Gold Coin"
    symbol: "$"
    scale: 2
    default: true
  token:
    name: "Event Token"
    symbol: "T"
    scale: 0
    default: false
```

| Property | Description |
|----------|-------------|
| `name` | Display name shown to players |
| `symbol` | Currency symbol (shown in balance, etc.) |
| `scale` | Maximum number of fractional digits |
| `default` | If `true`, this currency is used for Vault and `/pay` |

---

## Discord Integration

Send transaction logs directly to a Discord channel.

```yaml
discord:
  enabled: false
  webhook-url: ""
```

| Property | Description |
|----------|-------------|
| `enabled` | Enable/disable Discord webhook |
| `webhook-url` | Your Discord Webhook URL |

---

## Leaderboard Settings

```yaml
leaderboard:
  enabled: true
  cache-time-seconds: 300
  page-size: 10
```

| Property | Description |
|----------|-------------|
| `enabled` | Enable/disable `/baltop` |
| `cache-time-seconds` | How often to refresh the cache |
| `page-size` | Number of entries per page |

> **Note:** Leaderboards require SQL (MySQL or SQLite). JSON storage does not support leaderboards.

---

## Economy Settings

```yaml
economy:
  allow-negative-balance: true
  default-debt-limit: 0.0

start-balance: 1000.0
```

| Property | Description |
|----------|-------------|
| `allow-negative-balance` | Allow players to go into debt |
| `default-debt-limit` | Maximum debt allowed (0 = unlimited) |
| `start-balance` | Initial balance for new players |

---

## General Settings

```yaml
settings:
  locale: "zh_TW"
  main-command-alias: "aceeco"
```

| Property | Description |
|----------|-------------|
| `locale` | Language: `en_US`, `zh_TW`, `zh_CN` |
| `main-command-alias` | Custom alias for `/aceeco` (e.g., `bank`) |

---

---

# 設定指南

本指南說明如何為您的伺服器設定 AceEconomy。

> **v2.0.0 提醒：** 需要 Java 25 與 `AceLib v1.0.0`。Vault 與 PlaceholderAPI 都是可選整合。v1 的設定與資料不會自動 migration。此版本以 Paper/Folia API `26.1.2 build 74` 編譯；Folia `26.1.2` 尚未做同版本實機驗證。完整安裝與回退說明請參考 [`docs/release-v2.0.0.md`](release-v2.0.0.md)。

---

## 資料庫設定

AceEconomy 支援 JSON、SQLite（檔案型）與 MySQL/MariaDB。v2.0.0 預設使用 JSON 儲存。

### JSON（預設）

全新安裝會在插件資料夾建立 `data-v2.json`。

```yaml
storage:
  type: json
```

### SQLite

SQLite 會將資料庫檔案放在插件資料夾內，也可以在 `storage.sqlite` 下設定路徑。

```yaml
storage:
  type: sqlite
  sqlite:
    path: data-v2.sqlite
```

### MySQL / MariaDB

MySQL 或 MariaDB 可使用以下連線設定格式。`pool-size` 與 `max-lifetime` 必須放在 `storage.mysql` 底下；本版本尚未測試 live MySQL 連線。

```yaml
storage:
  type: mysql
  mysql:
    host: "localhost"
    port: 3306
    database: "aceeconomy"
    username: "your_user"
    password: ""
    pool-size: 10
    max-lifetime: 1800000
```

密碼請在本機設定。共用文件保留空值，或在本機副本使用 `<set-locally>` 這個 placeholder。

---

### v1 不自動 migration

v2.0.0 不會自動 migration v1 的設定或資料。v2 使用 `version: '2.0'`；JSON 使用 `data-v2.json`，選用 SQLite 時使用 `data-v2.sqlite`，MySQL 則使用 v2 schema。

升級前先備份舊安裝，再於副本確認新的設定與資料。不要假設餘額或貨幣設定會自動轉換。

---

## 多貨幣系統

AceEconomy 允許您定義無限的自訂貨幣。系統將使用標記為 `default: true` 的貨幣進行 Vault 整合與 `/pay` 等一般指令。

```yaml
currencies:
  dollar:
    name: "金幣"
    symbol: "$"
    scale: 2
    default: true
  token:
    name: "活動代幣"
    symbol: "T"
    scale: 0
    default: false
```

| 屬性 | 說明 |
|------|------|
| `name` | 顯示給玩家的名稱 |
| `symbol` | 貨幣符號（顯示於餘額等處）|
| `scale` | 小數位數上限 |
| `default` | 若為 `true`，此貨幣用於 Vault 與 `/pay` |

---

## Discord 整合

將交易記錄直接發送至 Discord 頻道。

```yaml
discord:
  enabled: false
  webhook-url: ""
```

| 屬性 | 說明 |
|------|------|
| `enabled` | 啟用/停用 Discord Webhook |
| `webhook-url` | 您的 Discord Webhook URL |

---

## 排行榜設定

```yaml
leaderboard:
  enabled: true
  cache-time-seconds: 300
  page-size: 10
```

| 屬性 | 說明 |
|------|------|
| `enabled` | 啟用/停用 `/baltop` |
| `cache-time-seconds` | 快取刷新頻率（秒）|
| `page-size` | 每頁顯示的條目數 |

> **注意：** 排行榜需要 SQL（MySQL 或 SQLite）。JSON 儲存不支援排行榜。

---

## 經濟系統設定

```yaml
economy:
  allow-negative-balance: true
  default-debt-limit: 0.0

start-balance: 1000.0
```

| 屬性 | 說明 |
|------|------|
| `allow-negative-balance` | 允許玩家負債 |
| `default-debt-limit` | 最大負債額度（0 = 無限制）|
| `start-balance` | 新玩家的起始餘額 |

---

## 一般設定

```yaml
settings:
  locale: "zh_TW"
  main-command-alias: "aceeco"
```

| 屬性 | 說明 |
|------|------|
| `locale` | 語言：`en_US`、`zh_TW`、`zh_CN` |
| `main-command-alias` | `/aceeco` 的自訂別名（如 `bank`）|
