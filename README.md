# AceEconomy

<div align="center">

[![Folia](https://img.shields.io/badge/Folia-Supported-brightgreen?style=for-the-badge)](https://papermc.io/software/folia)
[![Paper](https://img.shields.io/badge/Paper-1.21+-blue?style=for-the-badge)](https://papermc.io/)
[![Java](https://img.shields.io/badge/Java-21+-orange?style=for-the-badge)](https://adoptium.net/)
[![License](https://img.shields.io/badge/License-MIT-yellow?style=for-the-badge)](LICENSE)

**一個輕量、高效、完全支援 Folia 的 Minecraft 經濟插件**

*A lightweight, high-performance, Folia-compatible economy plugin for Minecraft*

[中文](#中文文檔) | [English](#english-documentation)

</div>

---

# 中文文檔

## ✨ 特色功能

- 🚀 **Folia 完全相容** — 使用區域化多執行緒，零阻塞主執行緒
- 🔒 **執行緒安全** — 使用 `ConcurrentHashMap` 和 `ReentrantReadWriteLock`
- 💾 **JSON 持久化** — 輕量級資料儲存，易於備份
- 🔌 **Vault 整合** — 相容所有支援 Vault 的插件
- 📊 **PlaceholderAPI** — 提供餘額佔位符
- 💵 **銀行支票** — 可轉讓的實體貨幣物品

---

## 📦 安裝

1. 下載最新版 `AceEconomy-x.x.x-reobf.jar`
2. 放入伺服器 `plugins/` 資料夾
3. 確保已安裝 [Vault](https://www.spigotmc.org/resources/vault.34315/)
4. 重啟伺服器

### 相依插件

| 插件 | 必要性 | 說明 |
|------|--------|------|
| [Vault](https://www.spigotmc.org/resources/vault.34315/) | **必要** | 經濟 API 橋接 |
| [PlaceholderAPI](https://www.spigotmc.org/resources/placeholderapi.6245/) | 可選 | 佔位符支援 |

---

## 🎮 指令

| 指令 | 說明 | 權限 |
|------|------|------|
| `/money` | 查看自己餘額 | `aceeconomy.use` |
| `/balance [玩家]` | 查看餘額 | `aceeconomy.use` |
| `/pay <玩家> <金額>` | 轉帳給其他玩家 | `aceeconomy.pay` |
| `/withdraw <金額>` | 提領銀行支票 | `aceeconomy.withdraw` |
| `/aceeco give <玩家> <金額>` | 給予金錢 | `aceeconomy.admin` |
| `/aceeco take <玩家> <金額>` | 扣除金錢 | `aceeconomy.admin` |
| `/aceeco set <玩家> <金額>` | 設定餘額 | `aceeconomy.admin` |

---

## 🔑 權限

| 權限節點 | 預設值 | 說明 |
|----------|--------|------|
| `aceeconomy.use` | 所有人 | 使用基本經濟指令 |
| `aceeconomy.pay` | 所有人 | 使用轉帳功能 |
| `aceeconomy.withdraw` | 所有人 | 提領銀行支票 |
| `aceeconomy.admin` | OP | 管理員指令 |

---

## 📊 PlaceholderAPI 佔位符

| 佔位符 | 輸出範例 |
|--------|----------|
| `%aceeco_balance%` | `1234.56` |
| `%aceeco_balance_formatted%` | `$1,234.56` |
| `%aceeco_balance_commas%` | `1,234` |
| `%aceeco_balance_int%` | `1234` |

---

## 💵 銀行支票系統

使用 `/withdraw` 可將虛擬貨幣轉換為實體支票物品：

- 支票使用 **PDC (PersistentDataContainer)** 儲存數值，防止偽造
- **右鍵點擊**支票即可兌換回虛擬貨幣
- 支票可在玩家間自由交易

---

## ⚙️ 設定檔

```yaml
# config.yml
default-balance: 0.0  # 新玩家預設餘額
```

---

## 🔧 開發者 API

### 取得 EconomyProvider

```java
EconomyProvider economy = Bukkit.getServicesManager()
    .getRegistration(EconomyProvider.class)
    .getProvider();

// 非同步操作
economy.getBalance(uuid).thenAccept(balance -> {
    System.out.println("餘額: " + balance);
});

economy.deposit(uuid, 100.0).thenAccept(success -> {
    if (success) {
        System.out.println("存款成功");
    }
});
```

### 監聽交易事件

```java
@EventHandler
public void onTransaction(EconomyTransactionEvent event) {
    if (event.getAmount() > 10000) {
        event.setCancelled(true); // 取消大額交易
    }
}
```

---

## 🤝 貢獻指南

歡迎任何形式的貢獻

### 如何貢獻

1. **Fork** 此倉庫
2. 建立功能分支：`git checkout -b feature/amazing-feature`
3. 提交變更：`git commit -m 'Add amazing feature'`
4. 推送分支：`git push origin feature/amazing-feature`
5. 開啟 **Pull Request**

### 開發環境設置

```bash
# 克隆專案
git clone https://github.com/your-username/AceEconomy.git
cd AceEconomy

# 建置專案
./gradlew build

# 產出 JAR 位於 build/libs/
```

### 程式碼規範

- 使用 **Java 21** 語法
- 遵循 Folia 執行緒模型（禁止使用 `Bukkit.getScheduler()`）
- 所有註解使用**繁體中文**
- 提交訊息使用英文

---

# English Documentation

## ✨ Features

- 🚀 **Folia Compatible** — Regionized multithreading, zero main thread blocking
- 🔒 **Thread-Safe** — Uses `ConcurrentHashMap` and `ReentrantReadWriteLock`
- 💾 **JSON Storage** — Lightweight data persistence, easy backup
- 🔌 **Vault Integration** — Works with all Vault-compatible plugins
- 📊 **PlaceholderAPI** — Balance placeholders support
- 💵 **Banknotes** — Transferable physical currency items

---

## 📦 Installation

1. Download the latest `AceEconomy-x.x.x-reobf.jar`
2. Place it in your server's `plugins/` folder
3. Ensure [Vault](https://www.spigotmc.org/resources/vault.34315/) is installed
4. Restart the server

### Dependencies

| Plugin | Required | Description |
|--------|----------|-------------|
| [Vault](https://www.spigotmc.org/resources/vault.34315/) | **Yes** | Economy API bridge |
| [PlaceholderAPI](https://www.spigotmc.org/resources/placeholderapi.6245/) | Optional | Placeholder support |

---

## 🎮 Commands

| Command | Description | Permission |
|---------|-------------|------------|
| `/money` | Check your balance | `aceeconomy.use` |
| `/balance [player]` | Check balance | `aceeconomy.use` |
| `/pay <player> <amount>` | Transfer money | `aceeconomy.pay` |
| `/withdraw <amount>` | Withdraw banknote | `aceeconomy.withdraw` |
| `/aceeco give <player> <amount>` | Give money | `aceeconomy.admin` |
| `/aceeco take <player> <amount>` | Take money | `aceeconomy.admin` |
| `/aceeco set <player> <amount>` | Set balance | `aceeconomy.admin` |

---

## 🔑 Permissions

| Permission | Default | Description |
|------------|---------|-------------|
| `aceeconomy.use` | Everyone | Basic economy commands |
| `aceeconomy.pay` | Everyone | Transfer money |
| `aceeconomy.withdraw` | Everyone | Withdraw banknotes |
| `aceeconomy.admin` | OP | Admin commands |

---

## 📊 PlaceholderAPI Placeholders

| Placeholder | Example Output |
|-------------|----------------|
| `%aceeco_balance%` | `1234.56` |
| `%aceeco_balance_formatted%` | `$1,234.56` |
| `%aceeco_balance_commas%` | `1,234` |
| `%aceeco_balance_int%` | `1234` |

---

## 💵 Banknote System

Use `/withdraw` to convert virtual currency into physical banknote items:

- Banknotes use **PDC (PersistentDataContainer)** to store values, preventing forgery
- **Right-click** a banknote to redeem it
- Banknotes can be freely traded between players

---

## ⚙️ Configuration

```yaml
# config.yml
default-balance: 0.0  # Default balance for new players
```

---

## 🔧 Developer API

### Getting EconomyProvider

```java
EconomyProvider economy = Bukkit.getServicesManager()
    .getRegistration(EconomyProvider.class)
    .getProvider();

// Async operations
economy.getBalance(uuid).thenAccept(balance -> {
    System.out.println("Balance: " + balance);
});

economy.deposit(uuid, 100.0).thenAccept(success -> {
    if (success) {
        System.out.println("Deposit successful");
    }
});
```

### Listening to Transaction Events

```java
@EventHandler
public void onTransaction(EconomyTransactionEvent event) {
    if (event.getAmount() > 10000) {
        event.setCancelled(true); // Cancel large transactions
    }
}
```

---

## 🤝 Contributing

We welcome contributions of all kinds!

### How to Contribute

1. **Fork** this repository
2. Create a feature branch: `git checkout -b feature/amazing-feature`
3. Commit your changes: `git commit -m 'Add amazing feature'`
4. Push to the branch: `git push origin feature/amazing-feature`
5. Open a **Pull Request**

### Development Setup

```bash
# Clone the project
git clone https://github.com/your-username/AceEconomy.git
cd AceEconomy

# Build the project
./gradlew build

# Output JAR is in build/libs/
```

### Code Standards

- Use **Java 21** syntax
- Follow Folia threading model (no `Bukkit.getScheduler()`)
- Code comments in **Traditional Chinese**
- Commit messages in **English**

---

## 📄 License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

---

<div align="center">

**Made with ❤️ by Smile**

</div>