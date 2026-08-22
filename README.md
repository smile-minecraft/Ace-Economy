# AceEconomy

<p align="center">
  <img src="https://img.shields.io/github/v/release/SmileX-AI/AceEconomy?include_prereleases&label=Release" alt="GitHub release">
  <img src="https://img.shields.io/github/downloads/SmileX-AI/AceEconomy/total?label=Downloads" alt="Downloads">
  <img src="https://img.shields.io/badge/Java-25-b07219?logo=openjdk" alt="Java">
</p>

AceEconomy v2.0.0 is compiled against the Paper/Folia API `26.1.2 build 74`. Folia `26.1.2` has not been verified on a real server running the same version. It provides multiple storage backends and transaction records; rollback production wiring is not included in v2.0.0.

---

## Features

- **Paper/Folia API build** — Compiled against API `26.1.2 build 74`; Folia `26.1.2` has not been verified on a real server running the same version
- **Vault Integration (Optional)** — Vault Economy API integration
- **Flexible Storage** — MySQL, SQLite, and JSON (`data-v2.json`, the default) support
- **Transaction Logging** — Transaction records are available; rollback production wiring is not included in v2.0.0
- **Multi-Currency System** — Define unlimited custom currencies
- **Discord Integration** — Real-time transaction logs via Webhook
- **Banknotes** — Physical item currency (`/withdraw`)
- **Leaderboards** — Richest players with `/baltop` (requires SQL)
- **Localization** — Built-in `en_US`, `zh_TW`, `zh_CN` support
- **PlaceholderAPI (Optional)** — Custom placeholders for scoreboards and chat

<details>
<summary><h3>功能特色（中文）</h3></summary>

- **Paper/Folia API 編譯版本** — 以 API `26.1.2 build 74` 編譯；Folia `26.1.2` 尚未做同版本實機驗證
- **Vault 整合（可選）** — Vault 經濟 API 整合
- **彈性儲存** — 支援 MySQL、SQLite 與 JSON（預設為 `data-v2.json`）
- **交易記錄** — 提供交易記錄；rollback production wiring 不在 v2.0.0
- **多貨幣系統** — 支援無限自訂貨幣
- **Discord 整合** — 透過 Webhook 即時傳送交易記錄
- **銀行支票** — 實體物品貨幣（`/withdraw`）
- **排行榜** — 透過 `/baltop` 查看富豪榜（需 SQL）
- **多語系支援** — 內建 `en_US`、`zh_TW` 與 `zh_CN`
- **PlaceholderAPI（可選）** — 提供記分板與聊天的自訂變數

</details>

---

## Requirements

| Requirement     | Version                                                     |
| --------------- | ----------------------------------------------------------- |
| Java            | 25                                                          |
| Server Software | Compiled against Paper/Folia API `26.1.2 build 74`; Folia `26.1.2` has not been verified on a real server running the same version |
| Required runtime dependency | `AceLib v1.0.0`                                      |
| Optional integrations | Vault, PlaceholderAPI                                      |

---

## Quick Start

1. Stop the server and remove any old AceLib JAR from the `plugins` folder. Do not keep `AceLib-0.5.0-SNAPSHOT` alongside the new version.
2. Download `AceLib-1.0.0.jar` from [Releases](https://github.com/SmileX-AI/AceEconomy/releases) and place it in your server's `plugins` folder
3. Download `AceEconomy-2.0.0.jar` from [Releases](https://github.com/SmileX-AI/AceEconomy/releases) and place it in the same folder
4. (Optional) Install **Vault** or **PlaceholderAPI** for their respective integrations
5. v2 config and data are not automatically migrated from v1. Back up the old installation before continuing; see the [v2.0.0 installation and rollback notes](docs/release-v2.0.0.md)
6. Start or restart your server
7. Configure `config.yml` — see [Configuration Guide](docs/config.md)

---

## Commands

| Command                    | Description                      |
| -------------------------- | -------------------------------- |
| `/money`                 | Check your account balance       |
| `/pay <player> <amount>` | Transfer money to another player |
| `/withdraw <amount>`     | Withdraw as a physical banknote  |
| `/baltop`                | View the richest players         |
| `/bank`                  | Open the bank                    |
| `/aceeco`                | Admin main command               |

> For full command details and permissions, see [Commands &amp; Permissions](docs/commands.md)

---

## Documentation

| Document                                          | Description                       |
| ------------------------------------------------- | --------------------------------- |
| [Installation &amp; Configuration](docs/config.md) | Setup guide, database, currencies |
| [Commands &amp; Permissions](docs/commands.md)     | Complete command reference        |
| [Persistence, Backup &amp; Restore](docs/persistence.md) | Persistence, backup and restore  |
| [v2.0.0 安裝與回退](docs/release-v2.0.0.md)         | v2.0.0 installation and rollback  |
| [Localization](docs/localization.md)               | Translation guide                 |

---

## Contributing

We welcome contributions! To contribute translations:

1. Fork the repository
2. Add your language file to `src/main/resources/lang/`
3. Create a Pull Request

For full translation guide, see [Localization](docs/localization.md).

---

**AceEconomy** © 2024-2026 Developed by Smile
