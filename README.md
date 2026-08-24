# AceEconomy

AceEconomy gives Paper and Folia servers a complete in-game economy: balances, player payments, physical banknotes, a bank dashboard, leaderboards, multiple currencies, and the integrations server communities already use.

AceEconomy 為 Paper 與 Folia 伺服器提供完整的遊戲內經濟系統，包含餘額、玩家轉帳、實體銀行支票、銀行面板、排行榜、多貨幣，以及伺服器社群常用的整合功能。

## What AceEconomy does

Players can check balances, pay one another, withdraw funds as banknotes, open the bank dashboard, and compare balances on a leaderboard. Server owners can choose a storage backend, define currencies, connect Vault or PlaceholderAPI, and send transaction notifications to Discord.

AceEconomy 能讓玩家查詢餘額、互相付款、把資金提領成銀行支票、開啟銀行面板，或在排行榜上查看資產排名。伺服器管理員則可以選擇資料儲存方式、定義貨幣、接入 Vault 或 PlaceholderAPI，並將交易通知送到 Discord。

## Requirements

| Requirement | Version or detail |
| --- | --- |
| Java | `25` |
| Server | Paper/Folia API `26.1.2 build 74` |
| Required plugin | `AceLib v1.0.0` |
| Optional plugins | Vault, PlaceholderAPI |

| 需求 | 版本或說明 |
| --- | --- |
| Java | `25` |
| 伺服器 | Paper/Folia API `26.1.2 build 74` |
| 必要插件 | `AceLib v1.0.0` |
| 可選插件 | Vault、PlaceholderAPI |

## Quick start

1. Stop the server before installing the plugins.
2. Place `AceLib-1.0.0.jar` and `AceEconomy-2.1.0.jar` in the server's `plugins` folder.
3. Add Vault or PlaceholderAPI if you want those integrations.
4. Start the server. AceEconomy creates its default configuration and storage on first start.
5. Adjust `config.yml` for your storage, locale, currencies, and integrations. The [configuration guide](docs/config.md) explains each setting.
6. Give the server a quick tour with `/money balance` and `/bank open`.

1. 安裝插件前先關閉伺服器。
2. 將 `AceLib-1.0.0.jar` 與 `AceEconomy-2.1.0.jar` 放進伺服器的 `plugins` 資料夾。
3. 需要 Vault 或 PlaceholderAPI 時，再一併安裝對應插件。
4. 啟動伺服器。第一次啟動時，AceEconomy 會建立預設設定與資料儲存。
5. 依伺服器需求調整 `config.yml` 的儲存方式、語系、貨幣與整合功能；可參考[設定指南](docs/config.md)。
6. 用 `/money balance` 查詢餘額，再用 `/bank open` 打開銀行面板。

Upgrading an existing installation? Start with the [upgrade guide](docs/upgrade-from-v1.md), then follow the [admin installation runbook](docs/admin-install-runbook.md).

正在從舊安裝升級？請先看[升級指南](docs/upgrade-from-v1.md)，再依[管理員安裝手冊](docs/admin-install-runbook.md)完成部署。

## Core features

- **Player economy** — Check balances, pay another player, and withdraw a banknote.
- **Bank dashboard** — Open a player-facing menu for account and withdrawal actions.
- **Leaderboards** — Show the richest players for a selected currency.
- **Multiple currencies** — Use the configured default currency or name another currency in a command.
- **Flexible storage** — JSON is the default; SQLite and MySQL are also available.
- **Integrations** — Optional Vault, PlaceholderAPI, and Discord support.
- **Localization** — Built-in `en_US`, `zh_TW`, and `zh_CN` locales.

- **玩家經濟** — 查詢餘額、付款給其他玩家，或提領成銀行支票。
- **銀行面板** — 開啟玩家可操作的帳戶與提領介面。
- **排行榜** — 依指定貨幣查看富豪排名。
- **多貨幣** — 可使用設定的預設貨幣，也能在指令中指定其他貨幣。
- **彈性儲存** — 預設使用 JSON，也支援 SQLite 與 MySQL。
- **整合功能** — 可選用 Vault、PlaceholderAPI 與 Discord。
- **多語系** — 內建 `en_US`、`zh_TW` 與 `zh_CN`。

## Player commands

| Command | Use |
| --- | --- |
| `/money balance [player] [currency]` | View a balance |
| `/pay send <player> <amount> [currency]` | Pay another player |
| `/withdraw cash <amount> [currency]` | Withdraw a physical banknote |
| `/baltop top [currency]` | View the balance leaderboard |
| `/bank open` | Open the bank dashboard |

| 指令 | 用途 |
| --- | --- |
| `/money balance [player] [currency]` | 查看餘額 |
| `/pay send <player> <amount> [currency]` | 付款給其他玩家 |
| `/withdraw cash <amount> [currency]` | 提領實體銀行支票 |
| `/baltop top [currency]` | 查看餘額排行榜 |
| `/bank open` | 開啟銀行面板 |

指令參數、權限、管理指令與完整說明請看[指令與權限](docs/commands.md)。想直接照情境操作，請從[玩家指南](docs/player-guide.md)開始。

For argument rules, permissions, admin commands, and the full reference, see [Commands & Permissions](docs/commands.md). For guided, player-focused examples, start with the [player guide](docs/player-guide.md).

## Documentation

| Guide | What it covers |
| --- | --- |
| [Player guide](docs/player-guide.md) | Everyday player tasks and common input problems |
| [Commands & Permissions](docs/commands.md) | Complete command syntax, permissions, and aliases |
| [Admin installation runbook](docs/admin-install-runbook.md) | Installation and first server setup |
| [Configuration guide](docs/config.md) | Storage, currencies, locale, economy, and Discord settings |
| [Persistence, backup & restore](docs/persistence.md) | Data backends and recovery procedures |
| [Integrations](docs/integrations.md) | Vault, PlaceholderAPI, Discord, and AceLib integrations |
| [Integration API](docs/integration-api.md) | Public integration-facing usage |
| [Troubleshooting](docs/troubleshooting.md) | Common server and player-facing problems |
| [Localization](docs/localization.md) | Locale files and translation work |

| 文件 | 內容 |
| --- | --- |
| [玩家指南](docs/player-guide.md) | 玩家日常操作與常見輸入問題 |
| [指令與權限](docs/commands.md) | 完整指令語法、權限與別名 |
| [管理員安裝手冊](docs/admin-install-runbook.md) | 安裝與伺服器首次設定 |
| [設定指南](docs/config.md) | 儲存、貨幣、語系、經濟與 Discord 設定 |
| [資料儲存、備份與回復](docs/persistence.md) | 資料後端與復原流程 |
| [整合功能](docs/integrations.md) | Vault、PlaceholderAPI、Discord 與 AceLib 整合 |
| [整合 API](docs/integration-api.md) | 提供給整合開發者的使用方式 |
| [故障排除](docs/troubleshooting.md) | 常見伺服器與玩家問題 |
| [多語系](docs/localization.md) | 語系檔與翻譯維護 |

## Support

Read the guide that matches your task first. If something still looks wrong, open an issue in the [AceEconomy repository](https://github.com/SmileX-AI/AceEconomy/issues) and include the plugin version, server software, command or setting involved, and the message you saw. Please remove passwords, tokens, and Webhook URLs before posting.

先從符合你目前需求的文件開始。如果問題仍未解決，請到 [AceEconomy repository](https://github.com/SmileX-AI/AceEconomy/issues) 開 Issue，附上插件版本、伺服器軟體、使用的指令或設定，以及畫面上的訊息。貼出內容前，請先移除密碼、Token 與 Webhook URL。

**AceEconomy** © 2024–2026 Developed by Smile
