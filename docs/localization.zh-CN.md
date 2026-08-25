# AceEconomy 本地化

[English](localization.md) · 简体中文 · [繁體中文](localization.zh-TW.md)

当你需要切换服务器语言、修改翻译、保持 v2 的键命名空间，或者重新加载结果时，请使用本指南。AceEconomy v2 使用 `lang/<locale>.yml`；内置示例是 `en_US`、`zh_TW` 和 `zh_CN`。

## 目录

- [文件位置与语言](#文件位置与语言)
- [v2 消息的写法](#v2-消息的写法)
- [内置键对照](#内置键对照)
- [切换当前语言](#切换当前语言)
- [安全地修改翻译](#安全地修改翻译)
- [重新加载与恢复](#重新加载与恢复)
- [相关指南](#相关指南)

## 文件位置与语言

首次启动之后，请编辑插件数据目录里的文件：

```text
plugins/AceEconomy/lang/en_US.yml
plugins/AceEconomy/lang/zh_TW.yml
plugins/AceEconomy/lang/zh_CN.yml
```

在 `plugins/AceEconomy/config.yml` 里选择语言：

```yaml
settings:
  locale: zh_TW
```

v2 的文件使用 `lang/<locale>.yml`。旧版的 `messages_<locale>.yml` 属于上一代的文件配置；编辑 v2 消息时不要用旧文件名。

## v2 消息的写法

语言文件使用三种语法：

- **键的命名空间：** 像 `general.invalid-amount` 和 `economy.payment-sent` 这样带点的 YAML 路径。保留命名空间和键名，只翻译值。
- **变量占位符：** 变量写成 `{placeholder}`，例如 `{amount}`、`{player}`、`{balance}` 和 `{status}`。保留大括号和占位符名称。
- **MiniMessage：** 用 `<red>`、`<yellow>`、`<aqua>`、`<green>`、和 `</red>` 这类标签来处理显示。标签会在变量替换之后解析，不要改成旧式的颜色代码。

```yaml
economy:
  balance-check: "Your balance: <yellow>{balance}</yellow>"
  payment-sent: "<green>Sent <yellow>{amount}</yellow> to <aqua>{player}</aqua>!</green>"
```

### 内置键对照

| 命名空间 | 用途 | 键示例 |
| --- | --- | --- |
| `message` | 消息共用的前缀 | `message.prefix` |
| `general` | 一般错误与状态 | `general.no-permission`、`general.status` |
| `economy` | 余额与付款 | `economy.balance-check`、`economy.payment-received` |
| `admin` | 管理员操作反馈 | `admin.give` |

## 切换当前语言

1. 打开 `plugins/AceEconomy/config.yml`。
2. 把 `settings.locale` 设成对应文件名里的语言，例如 `en_US`、`zh_TW` 或 `zh_CN`。
3. 保存文件。
4. 从服务器控制台执行 `/aceeco reload`，或者重启服务器。

重新加载会再次读取配置和语言快照。修改生效之后，下一条用到该键的消息就会以新语言显示。

## 安全地修改翻译

请从要编辑的语言所对应的 v2 文件开始，只修改 YAML 的值：

```yaml
general:
  invalid-amount: "<red>Invalid amount: <white>{amount}</white></red>"
  player-not-found: "<red>Player not found: <white>{player}</white></red>"
```

翻译时请注意：

- 保持缩进，并确保 YAML 的引号有效；
- 保留原消息需要的每一个 `{placeholder}`；
- MiniMessage 的开始和结束标签必须成对；
- `invalid-amount` 这类键名保持英文，即使值已经翻译。

维护某种语言时，请保留内置资源的结构。内置资源集是所需命名空间和占位符名称的参考来源。

## 重新加载与恢复

从服务器控制台执行 `/aceeco reload`，会重新加载 v2 配置和选中的语言资源。如果修改后的 YAML 无效，重新加载会报告失败，并保留内存中最后一份有效的快照。错误的翻译不应该悄悄取代当前正在使用的语言。

请修正报告里指出的 YAML 问题、保存文件，再执行一次 `/aceeco reload`。如果还是失败，就还原到最后一份有效的副本，并检查键的缩进、引号和 MiniMessage 标签。

## 相关指南

- [配置指南](config.zh-CN.md) — 完整的 `config.yml` 参考。
- [整合](integrations.zh-CN.md) — Vault、PlaceholderAPI、Discord 和 AceLib 的设置。
- [整合 API](integration-api.zh-CN.md) — 给插件开发者的占位符与货币细节。
