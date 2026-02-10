# AceEconomy

AceEconomy is a high-performance, **Folia-compatible** economy plugin designed for modern Minecraft servers. It offers regionized threading support, multiple storage backends, and extensive transaction logging capabilities.

AceEconomy 是一個專為現代 Minecraft 伺服器設計的高效能、**相容 Folia** 的經濟插件。它提供區域化多執行緒支援、多種儲存後端以及完整的交易記錄功能。

---

## Features / 功能特色

- **Folia & Paper Supported**: optimized for regionized multithreading.
  **支援 Folia 與 Paper**：針對區域化多執行緒進行優化。
- **Vault Integration**: Fully implements the Vault Economy API.
  **Vault 整合**：完整實作 Vault 經濟 API。
- **Flexible Storage**: Supports MySQL, SQLite, and JSON (fallback).
  **彈性儲存**：支援 MySQL、SQLite 與 JSON（備用）。
- **Transaction Logging & Rollback**: Logs detailed transaction history and supports rolling back accidental transactions.
  **交易記錄與回溯**：記錄詳細的交易歷史，並支援回溯錯誤的交易。
- **Multi-Currency System**: Support for defining custom currencies.
  **多貨幣系統**：支援定義自訂貨幣。
- **Discord Integration**: Send real-time transaction logs to a Discord channel via Webhook.
  **Discord 整合**：透過 Webhook 將即時交易記錄發送至 Discord 頻道。
- **Banknotes**: Withdraw currency as physical items (`/withdraw`).
  **銀行支票**：將貨幣提領為實體物品（`/withdraw`）。
- **Leaderboards**: View the richest players with `/baltop` (Requires SQL).
  **排行榜**：透過 `/baltop` 查看富豪榜（需使用 SQL）。
- **Localization**: Built-in support for `en_US`, `zh_TW`, and `zh_CN`.
  **多語言支援**：內建 `en_US`、`zh_TW` 與 `zh_CN`。
- **PlaceholderAPI Support**: Custom placeholders for scoreboards and chat.
  **支援 PlaceholderAPI**：提供自訂變數供記分板與聊天使用。

---

## Requirements / 系統需求

- **Java**: 21 or higher / 21 或更高版本
- **Server Software / 伺服器軟體**: Paper 1.21+ or Folia 1.21+
- **Dependencies / 必要插件**:
  - [Vault](https://www.spigotmc.org/resources/vault.34315/)

---

## Installation / 安裝教學

1. Download the latest `AceEconomy.jar` from the releases page.
   從發布頁面下載最新的 `AceEconomy.jar`。
2. Place the jar file into your server's `plugins` folder.
   將 jar 檔案放入伺服器的 `plugins` 資料夾中。
3. Ensure **Vault** is installed on your server.
   確保伺服器已安裝 **Vault**。
4. (Optional) Install **PlaceholderAPI** for placeholder support.
   (選用) 安裝 **PlaceholderAPI** 以獲得變數支援。
5. Restart your server.
   重新啟動伺服器。
6. Configure `config.yml` to set up your database and preferences.
   設定 `config.yml` 以配置資料庫與偏好設定。

---

## Commands & Permissions / 指令與權限

### User Commands / 玩家指令

| Command / 指令 | Alias / 別名 | Description / 描述 | Permission / 權限 |
|---|---|---|---|
| `/money` | `/bal`, `/balance` | Check your account balance.<br>查看帳戶餘額。 | `aceeconomy.use` |
| `/pay <player> <amount>` | | Transfer money to another player.<br>轉帳給其他玩家。 | `aceeconomy.pay` |
| `/withdraw <amount>` | | Withdraw money as a banknote.<br>提領銀行支票。 | `aceeconomy.withdraw` |
| `/baltop [page]` | `/top`, `/balancetop` | View the top richest players.<br>查看富豪排行榜。 | `aceeconomy.command.baltop` |

### Admin Commands /管理指令

Base Command: `/aceeco` (Alias configurable in config.yml)
主指令：`/aceeco`（可在 config.yml 中設定別名）

Permission: `aceeconomy.admin`

| Subcommand / 子指令 | Description / 描述 |
|---|---|
| `give <player> <amount>` | Give money to a player.<br>給予玩家金錢。 |
| `take <player> <amount>` | Take money from a player.<br>扣除玩家金錢。 |
| `set <player> <amount>` | Set a player's balance.<br>設定玩家餘額。 |
| `history <player>` | View a player's transaction history.<br>查看玩家交易記錄。 |
| `rollback <player> <id>` | Rollback a specific transaction.<br>回溯特定交易。 |
| `reload` | Reload configuration files.<br>重新載入設定檔。 | (Perm: `aceeconomy.command.reload`) |

---

## Configuration / 設定說明

### Database / 資料庫
You can choose between `sqlite` (default, file-based) or `mysql` in `config.yml`. For production servers or networks, **MySQL** is recommended.
您可以在 `config.yml` 中選擇 `sqlite`（預設，檔案型）或 `mysql`。對於正式伺服器或群組服，建議使用 **MySQL**。

### Discord Webhook
Enable Discord features to log transactions directly to your staff channel.
啟用 Discord 功能可將交易記錄直接發送到管理頻道。

```yaml
discord:
  enabled: true
  webhook-url: "YOUR_WEBHOOK_URL"
  min-amount: 10000.0 # Log transactions above this amount / 記錄超過此金額的交易
```

---

## Contributing / 如何貢獻

We welcome contributions! Please follow these steps:
我們歡迎您的貢獻！請遵循以下步驟：

1. **Fork** the repository.
   **Fork** 此專案。
2. Create a new **Feature Branch** (`git checkout -b feature/AmazingFeature`).
   建立新的 **功能分支** (`git checkout -b feature/AmazingFeature`)。
3. Commit your changes (`git commit -m 'Add some AmazingFeature'`).
   提交您的變更 (`git commit -m 'Add some AmazingFeature'`)。
4. Push to the branch (`git push origin feature/AmazingFeature`).
   推送到該分支 (`git push origin feature/AmazingFeature`)。
5. Open a **Pull Request**.
   建立 **Pull Request (PR)**。

---

**AceEconomy** © 2024-2026 Developed by Smile.