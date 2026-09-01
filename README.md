# AceEconomy

English · [简体中文](README.zh-CN.md) · [繁體中文](README.zh-TW.md)

AceEconomy adds an in-game economy to Paper and Folia servers. Players can check balances, pay each other, withdraw banknotes, open the bank dashboard, and compare standings on a leaderboard. Server owners can pick a storage backend, define currencies, connect Vault or PlaceholderAPI, and send transaction alerts to Discord.

## Documentation

### Getting started

| Guide | Use it when you want to |
| --- | --- |
| [Player guide](docs/player-guide.md) | Check balances, pay players, use banknotes, or open the bank dashboard |
| [Admin installation runbook](docs/admin-install-runbook.md) | Install AceEconomy v2 and complete the first server checks |

### Everyday use

| Guide | Use it when you want to |
| --- | --- |
| [Commands and permissions](docs/commands.md) | Look up command syntax, permissions, senders, and aliases |
| [Configuration guide](docs/config.md) | Configure storage, currencies, locale, economy rules, and Discord |

### Operations and upgrades

| Guide | Use it when you want to |
| --- | --- |
| [Server operations](docs/operations.md) | Run daily checks, change settings safely, back up data, or recover a server |
| [Persistence, backup, and restore](docs/persistence.md) | Choose a backend or work through backup and restore behaviour |
| [Upgrade from AceEconomy v1](docs/upgrade-from-v1.md) | Replace a v1 installation with v2 or plan a rollback |
| [Troubleshooting](docs/troubleshooting.md) | Investigate startup, storage, integration, or command problems |

### Integrations and development

| Guide | Use it when you want to |
| --- | --- |
| [Integrations](docs/integrations.md) | Connect AceLib, Vault, PlaceholderAPI, or Discord |
| [Integration API](docs/integration-api.md) | Build a plugin integration with Vault or PlaceholderAPI |
| [Localization](docs/localization.md) | Change or maintain the server language files |

### Releases and technical reference

| Guide | Use it when you want to |
| --- | --- |
| [AceEconomy v2.1.0 release](docs/release-v2.1.0.md) | Review the v2.1.0 release contents and validation boundaries |
| [AceEconomy v2.0.0 release](docs/release-v2.0.0.md) | Review the v2.0.0 release contents and upgrade notes |
| [Database concepts and upgrades](docs/database.md) | Understand the v2 data model and upgrade path |
| [v2 capability matrix](docs/v2-capability-matrix.md) | Check the v1 feature baseline retained by v2 |
| [v2.0.0 cutover notes](docs/cutover.md) | Read the v2 runtime, dependency, installation, and rollback reference |

## Contents

- [Requirements](#requirements)
- [Quick start](#quick-start)
- [Core features](#core-features)
- [Player commands](#player-commands)
- [Support](#support)

## Requirements

| Requirement | Version or detail |
| --- | --- |
| Java | `25` |
| Server | Paper/Folia API `26.1.2 build 74` |
| Required plugin | `AceLib v1.2.0` |
| Optional plugins | Vault, PlaceholderAPI |

Paper/Folia 26.1.2 is the officially supported server line. Folia 26.2 has been validated only on specific builds (VERIFIED-BETA); other 26.2 builds are unverified.

## Quick start

1. Stop the server before installing the plugins.
2. Place `AceLib-1.2.0.jar` and `AceEconomy-2.1.0.jar` in the server's `plugins` folder. Download `AceLib-1.2.0.jar` from <https://github.com/smile-minecraft/AceLib/releases/tag/v1.2.0> and verify its SHA-256 (`da9f196b47c2b28c6db443d102236b27c1a1bbdf7dd3e7c22470170420935278`) before copying it in; the [admin installation runbook](docs/admin-install-runbook.md) shows the exact command.
3. Add Vault or PlaceholderAPI if you want those integrations.
4. Start the server. AceEconomy creates its default configuration and storage on first start.
5. Adjust `config.yml` for your storage, locale, currencies, and integrations. The [configuration guide](docs/config.md) explains each setting.
6. Give the server a quick tour with `/money balance` and `/bank open`.

Upgrading an existing installation? Start with the [upgrade guide](docs/upgrade-from-v1.md), then follow the [admin installation runbook](docs/admin-install-runbook.md).

## Core features

- **Player economy:** Check balances, pay another player, and withdraw a banknote.
- **Bank dashboard:** Open a player-facing menu for account and withdrawal actions.
- **Leaderboards:** Show the richest players for a selected currency.
- **Multiple currencies:** Use the configured default currency or name another currency in a command.
- **Flexible storage:** JSON is the default; SQLite and MySQL are also available.
- **Integrations:** Optional Vault, PlaceholderAPI, and Discord support.
- **Localization:** Built-in `en_US`, `zh_TW`, and `zh_CN` locales.

## Player commands

| Command | Use |
| --- | --- |
| `/money balance [player] [currency]` | View a balance |
| `/pay send <player> <amount> [currency]` | Pay another player |
| `/withdraw cash <amount> [currency]` | Withdraw a physical banknote |
| `/baltop top [currency]` | View the balance leaderboard |
| `/bank open` | Open the bank dashboard |

For argument rules, permissions, administrator commands, and the full reference, see [Commands and permissions](docs/commands.md). For guided player-focused examples, start with the [player guide](docs/player-guide.md).

## Support

Read the guide that matches your task first. If something still looks wrong, open an issue in the [AceEconomy repository](https://github.com/SmileX-AI/AceEconomy/issues) and include the plugin version, server software, command or setting involved, and the message you saw. Remove passwords, tokens, and Webhook URLs before posting.

**AceEconomy** © 2024–2026 Developed by Smile
