# AceEconomy

[![Folia](https://img.shields.io/badge/Folia-Supported-brightgreen?style=flat-square)](https://papermc.io/software/folia)
[![Paper](https://img.shields.io/badge/Paper-1.21+-blue?style=flat-square)](https://papermc.io/)
[![Java](https://img.shields.io/badge/Java-21+-orange?style=flat-square)](https://adoptium.net/)
[![License](https://img.shields.io/badge/License-MIT-yellow?style=flat-square)](LICENSE)

**AceEconomy** is a modern, high-performance economy plugin designed specifically for the Folia architecture. It leverages regionized multithreading to ensure zero main thread blocking, making it ideal for large-scale servers.

[中文](#中文說明) | [English](#english-documentation)

---

# 中文說明

AceEconomy 專為現代 Minecraft 伺服器設計，特別針對 Folia 的多執行緒架構進行了優化。它不依賴傳統的同步主執行緒操作，而是採用非同步與執行緒安全的設計模式，確保在高負載下仍能保持流暢的經濟交易體驗。

## 主要功能

### Folia 架構支援
本插件完全遵循 Folia 的 API 規範，利用 `RegionScheduler` 和 `GlobalRegionScheduler` 進行任務調度。所有的資料存取都經過嚴格的執行緒安全處理，使用 `ConcurrentHashMap` 與 `ReentrantReadWriteLock` 來保證資料的一致性。

### 彈性資料儲存
支援多種資料儲存方式。對於小型伺服器，預設使用 **SQLite** 本地資料庫，無需額外設定即可運作。對於需要跨伺服器同步或更高性能的大型網路，支援 **MySQL** 資料庫連線，並採用 connection pool 技術優化連線效率。

### 資料遷移工具
為了方便從其他經濟插件轉移，AceEconomy 內建了強大的遷移工具。目前支援從 **EssentialsX** 和 **CMI** 匯入玩家資料。遷移過程完全非同步執行，並具備錯誤容忍機制，會自動跳過損壞的檔案並產生詳細報告。

### Discord 整合
內建交易監控系統，可透過 Webhook 將大額交易即時發送至 Discord 頻道。管理員可以自訂觸發通知的金額門檻，所有管理員操作（如給予、扣除、設定餘額）都會以不同顏色標示，方便查核。

### 實體銀行支票
玩家可以透過指令將虛擬貨幣轉換為實體支票物品。這些支票使用 **PersistentDataContainer (PDC)** 技術儲存面額，防止 NBT 標籤被偽造。支票可以自由交易、丟棄或存入箱子，右鍵點擊即可兌換回帳戶餘額。

## 安裝指南

1. 下載最新版本的 `AceEconomy-x.x.x.jar`。
2. 將檔案放入伺服器的 `plugins/` 資料夾。
3. 確保已安裝 [Vault](https://www.spigotmc.org/resources/vault.34315/)，這是經濟插件運作的必要依賴。
4. 啟動伺服器，插件將自動產生 `config.yml` 與 `messages.yml`。

如有需要，您可以安裝 [PlaceholderAPI](https://www.spigotmc.org/resources/placeholderapi.6245/) 來使用相關變數。

## 指令與權限

### 一般玩家指令

| 指令 | 說明 | 權限 |
|------|------|------|
| `/money` 或 `/balance` | 查看自己的帳戶餘額。 | `aceeconomy.use` |
| `/balance <玩家>` | 查看其他玩家的餘額。 | `aceeconomy.use` |
| `/pay <玩家> <金額>` | 轉帳給其他線上玩家。 | `aceeconomy.pay` |
| `/withdraw <金額>` | 將餘額提領為實體支票。 | `aceeconomy.withdraw` |

### 管理員指令

所有管理員指令需要 `aceeconomy.admin` 權限。

| 指令 | 說明 |
|------|------|
| `/aceeco give <玩家> <金額>` | 給予玩家指定金額。 |
| `/aceeco take <玩家> <金額>` | 從玩家帳戶扣除指定金額。 |
| `/aceeco set <玩家> <金額>` | 直接設定玩家的餘額。 |
| `/aceeco import <essentials\|cmi>` | 從其他插件匯入資料。 |

## 資料遷移

若您想從 EssentialsX 或 CMI 遷移資料，請依照以下步驟：

1. 確保伺服器已關閉或無玩家在線上（建議）。
2. 在 `plugins/Essentials/userdata` 或 `plugins/CMI/playerdata` 中確認有 `.yml` 資料檔。
3. 執行指令 `/aceeco import essentials` 或 `/aceeco import cmi`。
4. 系統將會開始非同步處理，並在完成後顯示成功與失敗的筆數。

## 開發者 API

AceEconomy 提供了簡單易用的 API 供其他開發者串接。

### 獲取 EconomyProvider

```java
EconomyProvider economy = Bukkit.getServicesManager()
    .getRegistration(EconomyProvider.class)
    .getProvider();
```

### 非同步操作範例

由於 Folia 的特性，建議盡量使用非同步方法：

```java
// 查詢餘額
economy.getBalance(playerItems).thenAccept(balance -> {
    player.sendMessage("您的餘額: " + balance);
});

// 存款
economy.deposit(playerUuid, 100.0).thenAccept(success -> {
    if (success) {
        // 處理成功邏輯
    }
});
```

### 監聽交易事件

您可以監聽 `EconomyTransactionEvent` 來處理自訂邏輯：

```java
@EventHandler
public void onTransaction(EconomyTransactionEvent event) {
    if (event.getAmount() > 100000) {
        // 記錄超大額交易
        getLogger().info("大額交易: " + event.getSenderName() + " -> " + event.getReceiverName());
    }
}
```

---

# English Documentation

**AceEconomy** is a modern economy plugin tailored for the Folia architecture. Eschewing traditional synchronous main-thread operations, it adopts an asynchronous, thread-safe design pattern to ensure smooth economic transactions even under high server loads.

## Key Features

### Folia Native Support
Fully compliant with Folia's API specifications, utilizing `RegionScheduler` and `GlobalRegionScheduler`. All data access involves strict thread-safety measures, employing `ConcurrentHashMap` and `ReentrantReadWriteLock` to guarantee data consistency.

### Flexible Data Storage
Supports multiple storage backends. For smaller servers, it defaults to a local **SQLite** database requiring no extra configuration. For larger networks needing cross-server synchronization, it supports **MySQL** with connection pooling for optimized performance.

### Data Migration Tools
To facilitate switching from other economy plugins, AceEconomy includes robust migration tools. Currently supports importing player data from **EssentialsX** and **CMI**. The migration process runs purely asynchronously and features error tolerance, skipping corrupted files and generating detailed reports.

### Discord Integration
Features a built-in transaction monitoring system that sends real-time webhooks to a Discord channel for large transactions. Administrators can customize the monetary threshold for alerts. Admin actions (Give, Take, Set) are color-coded for easy auditing.

### Physical Banknotes
Players can convert virtual currency into physical banknote items via command. These banknotes use **PersistentDataContainer (PDC)** to store value, preventing NBT forgery. They can be traded, dropped, or stored, and redeemed back into account balance by right-clicking.

## Installation

1. Download the latest `AceEconomy-x.x.x.jar`.
2. Place the file into your server's `plugins/` directory.
3. Ensure [Vault](https://www.spigotmc.org/resources/vault.34315/) is installed (required dependency).
4. Start the server; context files `config.yml` and `messages.yml` will be generated.

Optionally, install [PlaceholderAPI](https://www.spigotmc.org/resources/placeholderapi.6245/) for variable support.

## Commands & Permissions

### Player Commands

| Command | Description | Permission |
|---------|-------------|------------|
| `/money` or `/balance` | Check your account balance. | `aceeconomy.use` |
| `/balance <player>` | Check another player's balance. | `aceeconomy.use` |
| `/pay <player> <amount>` | Transfer money to an online player. | `aceeconomy.pay` |
| `/withdraw <amount>` | Withdraw balance as a physical banknote. | `aceeconomy.withdraw` |

### Admin Commands

All admin commands require the `aceeconomy.admin` permission.

| Command | Description |
|---------|-------------|
| `/aceeco give <player> <amount>` | Give a specified amount to a player. |
| `/aceeco take <player> <amount>` | Take a specified amount from a player. |
| `/aceeco set <player> <amount>` | Set a player's balance directly. |
| `/aceeco import <essentials\|cmi>` | Import data from other plugins. |

## Data Migration

To migrate data from EssentialsX or CMI:

1. Ensure the server is closed or empty (recommended).
2. Verify existance of `.yml` files in `plugins/Essentials/userdata` or `plugins/CMI/playerdata`.
3. Run `/aceeco import essentials` or `/aceeco import cmi`.
4. The system will process asynchronously and report success/failure counts upon completion.

## Developer API

AceEconomy provides a straightforward API for developers.

### Getting the EconomyProvider

```java
EconomyProvider economy = Bukkit.getServicesManager()
    .getRegistration(EconomyProvider.class)
    .getProvider();
```

### Async Operations

Due to Folia's nature, using asynchronous methods is highly recommended:

```java
// Check Balance
economy.getBalance(playerItems).thenAccept(balance -> {
    player.sendMessage("Your Balance: " + balance);
});

// Deposit
economy.deposit(playerUuid, 100.0).thenAccept(success -> {
    if (success) {
        // Handle success logic
    }
});
```

### Listening to Transactions

You can listen to `EconomyTransactionEvent` for custom logic:

```java
@EventHandler
public void onTransaction(EconomyTransactionEvent event) {
    if (event.getAmount() > 100000) {
        // Log huge transactions
        getLogger().info("Huge Transaction: " + event.getSenderName() + " -> " + event.getReceiverName());
    }
}
```

---

<div align="center">
    <strong>Made with ❤️ by Smile</strong>
</div>