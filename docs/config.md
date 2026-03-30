# Configuration Guide

This guide explains how to configure AceEconomy for your server.

---

## Database Setup

AceEconomy supports SQLite (file-based) and MySQL/MariaDB.

### SQLite (Default)

Best for small servers or testing. No external setup required.

```yaml
storage:
  type: sqlite
```

### MySQL / MariaDB (Recommended)

Recommended for production servers, networks, or heavy leaderboard usage.

```yaml
storage:
  type: mysql
  mysql:
    host: "localhost"
    port: 3306
    database: "aceeconomy"
    username: "your_user"
    password: "your_password"
  pool-size: 10
  max-lifetime: 1800000
```

> **Note:** The `pool-size` and `max-lifetime` settings control HikariCP connection pool behavior. Adjust `pool-size` based on your server's concurrent database connections.

---

### Migration from Old Config

If you're upgrading from an older version that used a single-currency setup, AceEconomy will automatically migrate your configuration to the new multi-currency format:

- Your existing balance will be converted to the `dollar` currency
- The old `currency-symbol` setting will be preserved as the `dollar` symbol

---

## Multi-Currency System

AceEconomy allows you to define unlimited custom currencies. The system will use the currency marked `default: true` for Vault integration and general commands like `/pay`.

```yaml
currencies:
  dollar:
    name: "Gold Coin"
    symbol: "$"
    format: "#,##0.00"
    default: true
  token:
    name: "Event Token"
    symbol: "T"
    format: "#,##0"
    default: false
```

| Property | Description |
|----------|-------------|
| `name` | Display name shown to players |
| `symbol` | Currency symbol (shown in balance, etc.) |
| `format` | Number format pattern (Java DecimalFormat) |
| `default` | If `true`, this currency is used for Vault and `/pay` |

---

## Discord Integration

Send transaction logs directly to a Discord channel.

```yaml
discord:
  enabled: true
  webhook-url: "https://discord.com/api/webhooks/..."
  min-amount: 10000.0
  log-events:
    transaction: true   # /pay
    admin: true         # /aceeco set/give/take
    server: true        # Plugin enable/disable
```

| Property | Description |
|----------|-------------|
| `enabled` | Enable/disable Discord webhook |
| `webhook-url` | Your Discord Webhook URL |
| `min-amount` | Only log transactions above this amount |
| `log-events.transaction` | Log player-to-player payments |
| `log-events.admin` | Log admin balance changes |
| `log-events.server` | Log plugin enable/disable events |

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

---

## 資料庫設定

AceEconomy 支援 SQLite（檔案型）與 MySQL/MariaDB。

### SQLite（預設）

適合小型伺服器或測試使用，無需額外設定。

```yaml
storage:
  type: sqlite
```

### MySQL / MariaDB（推薦）

推薦用於正式伺服器、群組服或頻繁使用排行榜功能。

```yaml
storage:
  type: mysql
  mysql:
    host: "localhost"
    port: 3306
    database: "aceeconomy"
    username: "your_user"
    password: "your_password"
  pool-size: 10
  max-lifetime: 1800000
```

> **注意：** `pool-size` 與 `max-lifetime` 設定控制 HikariCP 連線池行為。請根據伺服器的並發資料庫連線數調整 `pool-size`。

---

### 舊版設定遷移

如果您從舊版的單一貨幣設定升級，AceEconomy 會自動將您的設定遷移至新的多貨幣格式：

- 您現有的餘額將轉換為 `dollar` 貨幣
- 舊的 `currency-symbol` 設定將保留為 `dollar` 符號

---

## 多貨幣系統

AceEconomy 允許您定義無限的自訂貨幣。系統將使用標記為 `default: true` 的貨幣進行 Vault 整合與 `/pay` 等一般指令。

```yaml
currencies:
  dollar:
    name: "金幣"
    symbol: "$"
    format: "#,##0.00"
    default: true
  token:
    name: "活動代幣"
    symbol: "T"
    format: "#,##0"
    default: false
```

| 屬性 | 說明 |
|------|------|
| `name` | 顯示給玩家的名稱 |
| `symbol` | 貨幣符號（顯示於餘額等處）|
| `format` | 數字格式（Java DecimalFormat）|
| `default` | 若為 `true`，此貨幣用於 Vault 與 `/pay` |

---

## Discord 整合

將交易記錄直接發送至 Discord 頻道。

```yaml
discord:
  enabled: true
  webhook-url: "https://discord.com/api/webhooks/..."
  min-amount: 10000.0
  log-events:
    transaction: true   # /pay
    admin: true        # /aceeco set/give/take
    server: true       # Plugin enable/disable
```

| 屬性 | 說明 |
|------|------|
| `enabled` | 啟用/停用 Discord Webhook |
| `webhook-url` | 您的 Discord Webhook URL |
| `min-amount` | 僅記錄超過此金額的交易 |
| `log-events.transaction` | 記錄玩家間轉帳 |
| `log-events.admin` | 記錄管理員餘額變動 |
| `log-events.server` | 記錄插件啟用/停用事件 |

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
