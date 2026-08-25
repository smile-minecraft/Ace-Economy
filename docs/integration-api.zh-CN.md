# AceEconomy 整合 API

[English](integration-api.md) · 简体中文 · [繁體中文](integration-api.zh-TW.md)

这份参考是给插件开发者看的。它说明如何不依赖 AceEconomy 的内部类，而是通过标准的 Vault `Economy` 服务和 PlaceholderAPI 的 `aceeco` 命名空间，来读取或修改余额。服务器安装请看[整合](integrations.zh-CN.md)；语言文件语法请看[本地化](localization.zh-CN.md)。

## 目录

- [可用性与兼容性](#可用性与兼容性)
- [Vault 提供者](#vault-提供者)
- [PlaceholderAPI 命名空间](#placeholderapi-命名空间)
- [货币参数](#货币参数)
- [安全的整合行为](#安全的整合行为)
- [公开整合清单](#公开整合清单)
- [相关指南](#相关指南)

## 可用性与兼容性

AceEconomy 运行时需要 AceLib `v1.0.0`。Vault 和 PlaceholderAPI 是可选整合；你的插件应该能处理其中任一公开服务不可用的情况。如果标准整合已经够用，不要把 AceEconomy 的实现类设成硬依赖。

## Vault 提供者

AceEconomy 会注册一个名为 `AceEconomy` 的 Vault `Economy` 提供者。插件可以用 Vault 标准的服务查询方式来取得它：

```java
Economy economy = getServer().getServicesManager().load(Economy.class);
if (economy == null || !economy.isEnabled()) {
    // Vault or an economy provider is not available.
    return;
}

double balance = economy.getBalance(player);
EconomyResponse response = economy.withdrawPlayer(player, 25.0);
if (!response.transactionSuccess()) {
    // Treat the operation as failed; do not assume the balance changed.
}
```

这个提供者会把 Vault 的调用映射到配置里的默认货币。Vault 没有货币 ID，所以 Vault 的使用者不能在每次调用时挑选 `token` 或其他具名货币。需要显示或整合具名货币时，请使用下面的 PlaceholderAPI 形式。

### Vault 结果规则

| 操作 | 公开结果 |
| --- | --- |
| 存款或提款成功 | `EconomyResponse` 报告成功，并给出新余额。 |
| 存款或提款失败 | `EconomyResponse` 报告失败，金额为 `0`，余额为当前值或零；不会重試。 |
| 查询不存在账户的余额或 `has` | 回传 `0.0` 或 `false`；这个安全结果不要求抛异常。 |
| 只有名称的账户方法 | 回传 `false` 或失败。账户使用 UUID，请使用 `OfflinePlayer` 方法。 |
| Vault 的银行方法 | 不支持。 |

在把存款或提款视为完成之前，必须先检查 `transactionSuccess()`。不要用 Vault 提供者去操作非默认货币。

## PlaceholderAPI 命名空间

PAPI 的命名空间是 `aceeco`。解析器对参数不区分大小写，但配置和插件文字里，请使用文档规定的小写写法。

### 占位符完整速查表

| 可复制的占位符 | 解析为 | 结果示例 |
| --- | --- | --- |
| `%aceeco_balance%` | 默认货币的原始余额。 | `100.00` |
| `%aceeco_balance_formatted%` | 带符号的默认货币余额。 | `$100.00` |
| `%aceeco_balance_<currency>%` | 具名货币的原始余额。 | `%aceeco_balance_token%` → `7` |
| `%aceeco_balance_<currency>_formatted%` | 带符号的具名货币余额。 | `%aceeco_balance_token_formatted%` → `ⓒ7` |

把 `<currency>` 换成内部货币 ID，而不是显示名称。以默认的 `config.yml` 来说，下面这四种形式可以直接复制：

```text
%aceeco_balance%
%aceeco_balance_formatted%
%aceeco_balance_token%
%aceeco_balance_token_formatted%
```

`<currency>` 必须匹配 `[a-z0-9_]+`。未知的 ID、格式错误的 ID、未知的占位符名称、缺少玩家，以及不可用的账户，都会解析为 `null`；PlaceholderAPI 会保留原始文字，而不是显示一个假的余额。

## 货币参数

货币 ID 来自 `currencies` 下的键：

```yaml
currencies:
  dollar:
    name: "Gold Coin"
    symbol: "$"
    scale: 2
    default: true
  token:
    name: "Event Token"
    symbol: "ⓒ"
    scale: 0
    default: false
```

| 参数 | 整合含义 |
| --- | --- |
| `currencies.<id>` | 具名占位符使用的内部 ID。使用小写字母、数字和 `_`。 |
| `name` | 显示名称，不是占位符 ID。 |
| `symbol` | 格式化余额使用的前缀。 |
| `scale` | 货币定义的小数精度。 |
| `default` | 选择透过 Vault 和默认占位符形式公开的唯一货币。 |

只应该把一种货币标记为 `default: true`。新增具名货币时，原始和格式化占位符都要使用它的内部 ID。

## 安全的整合行为

整合使用方应该把服务缺失或结果失败，当作一个正常的分支来处理：

- 调用 Vault 之前，先确认取到了 `Economy` 服务。
- 每次更新自己的状态或发出成功消息之前，先检查 `EconomyResponse`。
- PAPI 仍然显示原始占位符时，把它视为数据不可用，不要当成数字。
- 不要自动重試失败的 Vault 交易，除非你自己的产品契约明确规定了安全的重試策略。

## 公开整合清单

插件整合应该依赖公开契约，而不是实现细节：

1. 插件没有外部整合也能运行时，把相关的外部插件声明为可选依赖。
2. 运行时查询 Vault 的 `Economy` 并处理 `null`。
3. 使用 `OfflinePlayer`／UUID 相关的调用，并检查 `EconomyResponse`。
4. 把四种 PAPI 形式，按文档原样放进面向用户的配置或消息里。
5. 密钥和 webhook 网址只放在本机配置里。

## 相关指南

- [整合](integrations.zh-CN.md) — 服务器安装、配置、成功确认与排错。
- [本地化](localization.zh-CN.md) — v2 语言键与 `{placeholder}` 消息变量。
