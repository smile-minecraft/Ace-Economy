# ⚙️ Configuration Guide / 設定指南

This guide explains how to configure AceEconomy for your server.
本指南說明如何為您的伺服器設定 AceEconomy。

## 📁 `config.yml` Overview / 設定檔概覽

The main configuration file is located at `plugins/AceEconomy/config.yml`.
主要設定檔位於 `plugins/AceEconomy/config.yml`。

### 1. Database Setup / 資料庫設定

AceEconomy supports both SQLite (file-based) and MySQL/MariaDB.
AceEconomy 支援 SQLite (檔案型) 與 MySQL/MariaDB。

#### SQLite (Default / 預設)
Best for small servers or testing. No external setup required.
適合小型伺服器或測試使用。無需額外設定。

```yaml
storage:
  type: sqlite
```

#### MySQL / MariaDB (Recommended / 推薦)
Recommended for production servers, networks, or if you plan to use the leaderboard feature heavily.
推薦用於正式伺服器、群組服，或頻繁使用排行榜功能時。

```yaml
storage:
  type: mysql
  mysql:
    host: "localhost"
    port: 3306
    database: "aceeconomy"
    username: "your_user"
    password: "your_password"
```

---

### 2. Multi-Currency System / 多貨幣系統

AceEconomy allows you to define multiple currencies. The system will auto-migrate old configs to this new format.
AceEconomy 允許您定義多種貨幣。系統會自動將舊設定遷移至此新格式。

```yaml
currencies:
  dollar:
    name: "金幣"          # Display name / 顯示名稱
    symbol: "$"           # Currency symbol / 貨幣符號
    format: "#,##0.00"    # Number format / 數字格式
    default: true         # Is this the default currency? / 是否為預設貨幣？
  point:
    name: "點數"
    symbol: "P"
    format: "#,##0"
    default: false
```

- **`default: true`**: This currency will be used for Vault integration and general commands like `/pay`.
  **`default: true`**：此貨幣將用於 Vault 整合及 `/pay` 等一般指令。

---

### 3. Discord Integration / Discord 整合

Send transaction logs directly to a Discord channel.
將交易記錄直接發送至 Discord 頻道。

```yaml
discord:
  enabled: true
  webhook-url: "https://discord.com/api/webhooks/..."
  min-amount: 10000.0  # Only log transactions above this amount
                       # 僅記錄超過此金額的交易
```

- **`min-amount`**: Helps reduce spam by only logging large transfers.
  **`min-amount`**：僅記錄大額轉帳以減少洗頻。

---

### 4. General Settings / 一般設定

```yaml
# Starting balance for new players
# 新玩家的起始餘額
start-balance: 1000.0

settings:
  # Creating a custom alias for the main admin command
  # 為管理員主指令建立自訂別名
  main-command-alias: "aceeco"
```
