# AceEconomy

<p align="center">
  <img src="https://img.shields.io/github/v/release/SmileX-AI/AceEconomy?include_prereleases&label=Release" alt="GitHub release">
  <img src="https://img.shields.io/github/downloads/SmileX-AI/AceEconomy/total?label=Downloads" alt="Downloads">
  <img src="https://img.shields.io/badge/Java-21%2B-b07219?logo=openjdk" alt="Java">
  <img src="https://img.shields.io/badge/Paper-1.21%2B-ffffff?logo=paper" alt="Paper">
  <img src="https://img.shields.io/badge/Folia-Supported-4CAF50" alt="Folia">
</p>

AceEconomy is a high-performance, **Folia-compatible** economy plugin designed for modern Minecraft servers. It provides regionized threading support, multiple storage backends, and comprehensive transaction logging with rollback capabilities.

---

## Features

- **Folia & Paper Compatible** — Optimized for regionized multithreading
- **Vault Integration** — Full Vault Economy API implementation
- **Flexible Storage** — MySQL, SQLite, and JSON (fallback) support
- **Transaction Logging & Rollback** — Detailed audit trail with smart rollback
- **Multi-Currency System** — Define unlimited custom currencies
- **Discord Integration** — Real-time transaction logs via Webhook
- **Banknotes** — Physical item currency (`/withdraw`)
- **Leaderboards** — Richest players with `/baltop` (requires SQL)
- **Localization** — Built-in `en_US`, `zh_TW`, `zh_CN` support
- **PlaceholderAPI** — Custom placeholders for scoreboards and chat

<details>
<summary><h3>功能特色（中文）</h3></summary>

- **Folia 與 Paper 相容** — 針對區域化多執行緒優化
- **Vault 整合** — 完整實作 Vault 經濟 API
- **彈性儲存** — 支援 MySQL、SQLite 與 JSON（備用）
- **交易記錄與回溯** — 詳細稽核軌跡與智慧回溯功能
- **多貨幣系統** — 支援無限自訂貨幣
- **Discord 整合** — 透過 Webhook 即時傳送交易記錄
- **銀行支票** — 實體物品貨幣（`/withdraw`）
- **排行榜** — 透過 `/baltop` 查看富豪榜（需 SQL）
- **多語系支援** — 內建 `en_US`、`zh_TW` 與 `zh_CN`
- **PlaceholderAPI** — 提供記分板與聊天的自訂變數

</details>

---

## Requirements

| Requirement | Version |
|-------------|---------|
| Java | 21+ |
| Server Software | Paper 1.21+ or Folia 1.21+ |
| Dependencies | **Vault** (Paper) or **Vault Unlocked** (Folia) |

---

## Quick Start

1. Download the latest `AceEconomy.jar` from [Releases](https://github.com/SmileX-AI/AceEconomy/releases)
2. Place the jar into your server's `plugins` folder
3. Install **Vault** (Paper) or **Vault Unlocked** (Folia)
4. (Optional) Install **PlaceholderAPI** for advanced placeholders
5. Restart your server
6. Configure `config.yml` — see [Configuration Guide](docs/config.md)

---

## Commands

| Command | Description |
|---------|-------------|
| `/money` | Check your account balance |
| `/pay <player> <amount>` | Transfer money to another player |
| `/withdraw <amount>` | Withdraw as a physical banknote |
| `/baltop` | View the richest players |
| `/aceeco` | Admin main command |

> For full command details and permissions, see [Commands & Permissions](docs/commands.md)

---

## Documentation

| Document | Description |
|----------|-------------|
| [Installation & Configuration](docs/config.md) | Setup guide, database, currencies |
| [Commands & Permissions](docs/commands.md) | Complete command reference |
| [Database & Rollback](docs/database.md) | Schema, rollback system |
| [Localization](docs/localization.md) | Translation guide |

---

## Contributing

We welcome contributions! To contribute translations:

1. Fork the repository
2. Add your language file to `src/main/resources/lang/`
3. Create a Pull Request

For full translation guide, see [Localization](docs/localization.md).

---

**AceEconomy** © 2024-2026 Developed by Smile
