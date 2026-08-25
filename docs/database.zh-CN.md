# 数据库概念与升级

[English](database.md) · 简体中文 · [繁體中文](database.zh-TW.md)

这一页说明目前 v2 的数据模型，帮管理员在升级时认出哪些数据必须保留。它不是手动建表的 SQL 脚本。选择 `storage.type: mysql` 或 `storage.type: sqlite` 后，AceEconomy 会按所选的数据库类型建立 schema；JSON 则是把同一套逻辑模型存在文件里。

## 目录

- [两个含义不同的版本值](#两个含义不同的版本值)
- [v2 模型](#v2-模型)
  - [`ace_v2_schema`](#ace_v2_schema)
  - [`ace_v2_accounts`](#ace_v2_accounts)
  - [`ace_v2_balances`](#ace_v2_balances)
  - [`ace_v2_transactions`](#ace_v2_transactions)
- [JSON 与 SQL 表示相同的数据](#json-与-sql-表示相同的数据)
- [如何初始化新的 SQL 存储](#如何初始化新的-sql-存储)
- [升级路径](#升级路径)
- [备份与恢复](#备份与恢复)
- [与旧文档相比的变化](#与旧文档相比的变化)

## 两个含义不同的版本值

这里有两个独立的版本概念：

| 版本 | 出现位置 | 含义 |
| --- | --- | --- |
| 配置 `2.0` | `config.yml` → `version` | v2 配置文件的格式。 |
| 持久化 `1` | SQL 表 `ace_v2_schema`，或 JSON `schemaVersion` | backend 当前理解的 v2 持久化模型。 |

不要把持久化值当成配置值，也不要把旧版 v1 表结构当成 v2 schema。下面列出的是 v2 名称。

## v2 模型

这套模型包含一个 schema 标记、一个账户关系、一个余额关系和一个交易关系。四张 SQL 表如下：

### `ace_v2_schema`

此表记录持久化模型版本。它包含 `version` 和 `updated_at`；初始化的 v2 存储会写入版本 `1`。这一行用来告诉 backend 正在打开哪个模型，里面不含玩家余额。

### `ace_v2_accounts`

此表把账户 owner ID 对应到最后保存的 owner 名称：

| 列 | 含义 |
| --- | --- |
| `owner` | 账户拥有者的 UUID。 |
| `owner_name` | 为该拥有者保存的显示名称。 |

UUID 才是持久化身份。名称只是供显示用的保存值，不是账户键。

### `ace_v2_balances`

此表为每一组 `(owner, currency_id)` 保存一个金额：

| 列 | 含义 |
| --- | --- |
| `owner` | 账户拥有者 UUID。 |
| `currency_id` | 配置中的货币 ID，例如 `dollar` 或 `token`。 |
| `amount` | 精确的十进制金额，以文本保存。 |

`(owner, currency_id)` 这一对值就是键。所以修改货币 ID 是数据变更，而不只是改显示文字。

### `ace_v2_transactions`

此表保存理解一笔财务事件及其后续状态所需的信息：

| 列 | 含义 |
| --- | --- |
| `id` | 交易 UUID。 |
| `account_id` | 该记录影响的账户。 |
| `counterparty` | 操作存在对手账户时记录对方；也可以为空。 |
| `currency_id` | 操作使用的货币。 |
| `amount` | 精确的十进制金额，以文本保存。 |
| `type` | 领域交易类型。 |
| `balance_before` | 事件前余额（如果有）。 |
| `balance_after` | 事件后余额（如果有）。 |
| `timestamp` | 事件时间。 |
| `reason` | 可附加在事件上的原因。 |
| `reverted` | 此记录是否已标记为 reverted。 |

两种 SQL dialect 都以十进制文本保存金额和余额快照。MySQL 使用 v2 的 `VARCHAR` 表示和 boolean reverted 标记；SQLite 使用文本和整数形式的布尔值。逻辑字段相同。

## JSON 与 SQL 表示相同的数据

JSON backend 会在一个文档中保存相同的概念：

```json
{
  "schemaVersion": 1,
  "accounts": {
    "<owner-uuid>": {
      "owner": "<owner-uuid>",
      "ownerName": "<display-name>",
      "balances": {
        "dollar": "1000.00"
      }
    }
  },
  "transactions": []
}
```

这个示例刻意保持精简。真实 snapshot 可以包含许多账户和交易。`accounts` 保存余额，`transactions` 保存事件历史和 `reverted` 状态。

## 如何初始化新的 SQL 存储

SQL backend 在没有 v2 表的新位置启动时，会创建：

1. 带有 v2 持久化版本行的 `ace_v2_schema`。
2. 保存账户身份和名称的 `ace_v2_accounts`。
3. 按 owner 和货币保存金额的 `ace_v2_balances`。
4. 保存事件记录和回滚状态的 `ace_v2_transactions`。

对已有的兼容存储，建表可以重复执行而不会重复建资料。应由插件初始化这些表；管理员不需要把旧版 v1 DDL 脚本复制到新的 v2 数据库。

## 升级路径

v2 配置和持久化模型有清楚的边界。旧版 v1 配置或旧版 v1 表不会被当作 v2 数据读取。请把升级规划成建立一个新的 v2 存储：

1. 修改文件或数据库设置前，先保留旧安装的备份。
2. 在 v2 `config.yml` 中选择 JSON、SQLite 或 MySQL。
3. 使用所选位置启动，让插件创建 v2 模型。
4. 如果已有 v2 JSON snapshot，把该 snapshot 还原到新的 backend。
5. 正式运行前，检查账户余额和配置中的货币 ID。

这套模型没有描述旧版 v1 行与 v2 账户、余额、交易之间的自动转换。v2 snapshot 带有共用的逻辑模型，因此可以在 JSON 与 SQL backend 之间转移；这不代表旧版 v1 备份也兼容。

## 备份与恢复

操作顺序请参阅[持久化、备份与恢复](persistence.zh-CN.md)。必须保留的完整数据边界是 v2 模型：账户、余额、交易以及 schema 版本。

替换正在使用的存储前，先停止服务器并保留当前备份。恢复时会先解析输入的 v2 JSON snapshot，再检查其 schema 版本。如果解析或版本验证失败，现有数据会保留。恢复成功后，backend 会在恢复操作中用 snapshot 替换当前账户、余额和交易。

不要把删除 schema 标记或删除 v2 表当作第一步故障排除。如果存储不兼容，请保留备份，并把重建视为会清空数据、从空白状态开始的数据破坏性操作。

## 与旧文档相比的变化

旧文档中的 `ace_balances`、`ace_users` 和 `ace_transaction_logs` 不是 v2 建库指南。在 v2 中，账户身份和余额分别位于 `ace_v2_accounts` 与 `ace_v2_balances`，交易历史由 `ace_v2_transactions` 表示。v2 schema 还会以文本保存精确的十进制值，并包含 schema 标记。

如果现有数据库只有这些旧表名，不要直接让 v2 backend 指向它，并假设旧行会被接收。保留旧备份，创建或选择 v2 位置，并把数据转换作为单独的 migration 决策处理。
