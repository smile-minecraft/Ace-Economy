# AceEconomy

AceEconomy is a high-performance, **Folia-compatible** economy plugin designed for modern Minecraft servers. It offers regionized threading support, multiple storage backends, and extensive transaction logging capabilities.

AceEconomy 是一個專為現代 Minecraft 伺服器設計的高效能、**相容 Folia** 的經濟插件。它提供區域化多執行緒支援、多種儲存後端以及完整的交易記錄功能。

---

## 📚 Documentation / 說明文件

We have detailed documentation available in the `docs` folder:
我們在 `docs` 資料夾中提供了詳細的文件：

- **[Installation & Configuration / 安裝與設定](docs/config.md)**
- **[Commands & Permissions / 指令與權限](docs/commands.md)**
- **[Database & Rollback System / 資料庫與回溯系統](docs/database.md)**
- **[Localization & Translation / 在地化與翻譯](docs/localization.md)**

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
  - **Vault** (For Paper servers / 適用於 Paper)
  - **[Vault Unlocked](https://github.com/Jikoo/Vault-Unlocked)** (Required for **Folia** servers / **Folia** 伺服器必須使用此版本)

---

## Installation / 安裝教學

1. Download the latest `AceEconomy.jar` from the releases page.
   從發布頁面下載最新的 `AceEconomy.jar`。
2. Place the jar file into your server's `plugins` folder.
   將 jar 檔案放入伺服器的 `plugins` 資料夾中。
3. Install **Vault** (or **Vault Unlocked** for Folia).
   安裝 **Vault** (如果是 Folia 則安裝 **Vault Unlocked**)。
4. (Optional) Install **PlaceholderAPI** for placeholder support.
   (選用) 安裝 **PlaceholderAPI** 以獲得變數支援。
5. Restart your server.
   重新啟動伺服器。
6. Configure `config.yml` to set up your database and preferences.
   設定 `config.yml` 以配置資料庫與偏好設定。詳情請見 **[設定指南](docs/config.md)**。

---

## Commands / 指令

> For a full list of permissions and admin commands, please check the **[Commands Wiki](docs/commands.md)**.
> 完整權限與管理指令列表請參閱 **[指令與權限](docs/commands.md)**。

| Command / 指令 | Description / 描述 |
|---|---|
| `/money` | Check your account balance.<br>查看帳戶餘額。 |
| `/pay <player> <amount>` | Transfer money to another player.<br>轉帳給其他玩家。 |
| `/withdraw <amount>` | Withdraw money as a banknote.<br>提領銀行支票。 |
| `/baltop` | View the top richest players.<br>查看富豪排行榜。 |
| `/aceeco` | Admin main command.<br>管理員主指令。 |

---

## Contributing / 如何貢獻

We welcome contributions! Please see **[Localization Guide](docs/localization.md)** for translation contributions.
我們歡迎您的貢獻！翻譯貢獻請參閱 **[在地化指南](docs/localization.md)**。

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