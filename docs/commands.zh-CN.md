# 指令与权限

[English](commands.md) · 简体中文 · [繁體中文](commands.zh-TW.md)

你可能只是想查余额、转一笔钱，或打开银行界面。这一页把玩家指令和管理员指令分开，方便你直接找到要用的指令。以下是当前 v2 语法：每个主指令后面都要接一个指定的子指令。

## 目录

- [快速查表](#快速查表)
- [玩家用法](#玩家用法)
- [管理员权限](#管理员权限)
- [常见指令错误](#常见指令错误)

## 快速查表

| 主指令 | 子指令 | 用法 | 执行者 | 权限 | 别名 |
|---|---|---|---|---|---|
| `/money` | `balance` | `[player] [currency]` | 玩家或控制台；控制台必须提供 `player` | `aceeconomy.command.money` | `/balance` |
| `/pay` | `send` | `<player> <amount> [currency]` | 仅限玩家 | `aceeconomy.command.pay` | 无 |
| `/withdraw` | `cash` | `<amount> [currency]` | 仅限玩家 | `aceeconomy.command.withdraw` | 无 |
| `/baltop` | `top` | `[currency]` | 玩家或控制台 | `aceeconomy.command.baltop` | 无 |
| `/bank` | `open` | 不接受参数 | 仅限玩家 | `aceeconomy.command.bank` | 无 |
| `/aceeco` | `give`、`take`、`set`、`history`、`reload`、`rollback`、`backup`、`restore`、`import` | 见管理员参考 | `reload`、`rollback`、`restore`、`import` 仅限控制台；`backup` 与其他子指令可由玩家或控制台执行 | `aceeconomy.admin` 加上子指令权限 | 无 |

`<必填>` 代表必须提供的值；`[可选]` 代表可省略的值。省略 `currency` 时，使用设置的默认货币。货币 ID 不区分大小写。

金额必须是有效数字、大于零、符合该货币的小数位数，而且不得超过 `1,000,000,000,000,000`。

v2 指令规格列出的主指令别名只有 `/money` 的 `/balance`。它仍然要接 `balance` 子指令：`/balance balance [player] [currency]`。没有独立的 `/backup` 或 `/restore` 主指令，请使用 `/aceeco` 的管理子指令。

## 玩家用法

### 查询余额：`/money balance`

想看自己的余额时直接省略玩家名称。查询其他账户时加上玩家名称；控制台没有自己的玩家账户，所以必须使用带有 `player` 的完整形式。

| 项目 | 说明 |
|---|---|
| 用法 | `/money balance [player] [currency]` |
| 执行者 | 玩家或控制台；玩家可省略 `player`，控制台必须提供 `player` |
| 权限 | `aceeconomy.command.money` |
| 别名 | 主指令可用 `/balance`，但仍要保留 `balance` 子指令 |

示例：

```text
/money balance
/money balance Alex <currency>
/balance balance Alex <currency>
```

### 转账：`/pay send`

把钱转给其他玩家。这个指令必须在游戏内由玩家执行。

| 项目 | 说明 |
|---|---|
| 用法 | `/pay send <player> <amount> [currency]` |
| 执行者 | 仅限玩家 |
| 权限 | `aceeconomy.command.pay` |
| 别名 | v2 指令规格未提供别名 |

示例：`/pay send Alex 25 <currency>`

### 提取银行支票：`/withdraw cash`

把指定金额提取成银行支票。这个指令必须由玩家执行。

| 项目 | 说明 |
|---|---|
| 用法 | `/withdraw cash <amount> [currency]` |
| 执行者 | 仅限玩家 |
| 权限 | `aceeconomy.command.withdraw` |
| 别名 | v2 指令规格未提供别名 |

示例：`/withdraw cash 100 <currency>`

### 查看排行榜：`/baltop top`

查看余额最高的玩家。玩家和控制台都能执行；不想使用默认货币时，再指定货币即可。

| 项目 | 说明 |
|---|---|
| 用法 | `/baltop top [currency]` |
| 执行者 | 玩家或控制台 |
| 权限 | `aceeconomy.command.baltop` |
| 别名 | v2 指令规格未提供别名 |

示例：

```text
/baltop top
/baltop top <currency>
```

### 打开银行：`/bank open`

打开 AceEconomy 银行界面。这个指令不接受参数，必须由玩家执行。

| 项目 | 说明 |
|---|---|
| 用法 | `/bank open` |
| 执行者 | 仅限玩家 |
| 权限 | `aceeconomy.command.bank` |
| 别名 | v2 指令规格未提供别名 |

Java 玩家打开的是箱子菜单，按钮与格子对应如下：

- 存款（DEPOSIT）：第 4 格（上方中间）。
- 提款（WITHDRAW）：第 11 格与第 13 格（分别是 `100` 与 `500` 提取按钮）。
- 关闭（CLOSE）：第 15 格。

基岩版玩家（Geyser + Floodgate）打开的是原生表单：首页显示余额与存款、提取、关闭三个按钮；提取要输入金额与货币，再经过确认步骤才会执行。关闭表单、输入无效的值、离线，或在回应前遇到 reload／断线，都不会执行任何交易。两种界面走同一条存取款路径，nonce、防重播与 region 保证两边都成立。Floodgate 缺席时所有玩家都维持箱子菜单。

存入一张有效的 v2 银行支票时，必须先完成持久防重播机制与入帐，之后才会移除支票或减少堆叠数量。无效、被重播或入帐失败的支票，会留在玩家物品栏里。手持支票按右键可直接兑换，走与银行面板存款按钮相同的原子入账路径。如果入账已经完成但支票拿不掉，这笔钱照样算数，服务器审计日志会记下支票编号、玩家和实际入账金额，全程可追查。多张支票是先在副本上减一张再一次性写回，写入失败时整叠会留在手上：收好并联系管理员。单张支票的清除是一次写入，失败后格子状态无法从外部确认，因此只保证审计日志可追查、不保证票还在：把票根留好，不要重试（钱已经到账，重送会被拦下），直接联系管理员。管理员会拿支票编号去核对审计日志，先收回或作废重复的支票，再用比如 `/aceeco give` 把钱补上。

## 管理员权限

管理员主指令是 `/aceeco`，v2 指令规格未提供别名。主权限为 `aceeconomy.admin`，每个操作也各有自己的权限节点。变更余额的子指令沿用「玩家名称、金额、可选货币」的格式。`history` 只读取已记录的交易，不会改变余额。`reload`、`rollback`、`restore` 与 `import` 只能由控制台执行；`rollback` 要带交易 ID，`restore` 则要带备份 ID 与精确的 `confirm`，`import` 要带来源与路径（写入时需精确的 `apply confirm`）。

| 子指令 | 用法 | 执行者 | 子指令权限 | 别名 |
|---|---|---|---|---|
| `give` | `/aceeco give <player> <amount> [currency]` | 玩家或控制台 | `aceeconomy.admin.give` | 无 |
| `take` | `/aceeco take <player> <amount> [currency]` | 玩家或控制台 | `aceeconomy.admin.take` | 无 |
| `set` | `/aceeco set <player> <amount> [currency]` | 玩家或控制台 | `aceeconomy.admin.set` | 无 |
| `history` | `/aceeco history [player] [currency] [page]` | 玩家或控制台 | `aceeconomy.admin.history` | 无 |
| `reload` | `/aceeco reload` | 仅限控制台 | `aceeconomy.admin.reload` | 无 |
| `rollback` | `/aceeco rollback <transaction-id>` | 仅限控制台 | `aceeconomy.admin.rollback` | 无 |
| `backup` | `/aceeco backup [label]` | 玩家或控制台 | `aceeconomy.admin.backup` | 无 |
| `restore` | `/aceeco restore <backup-id> confirm` | 仅限控制台 | `aceeconomy.admin.restore` | 无 |
| `import` | `/aceeco import <essentials\|cmi> <path> [currency] [apply confirm]` | 仅限控制台 | `aceeconomy.admin.import` | 无 |

插件声明的默认值是：玩家指令权限为 `true`，管理员权限为 `op`。另外，插件也声明 `aceeconomy.bypass.debt`，默认值为 `op`，用于跳过债务上限的权限。

### 增加余额：`/aceeco give`

需要为玩家增加余额时使用 `give`。

示例：`/aceeco give Alex 500 <currency>`

### 扣除余额：`/aceeco take`

需要从玩家余额中扣除金额时使用 `take`。

示例：`/aceeco take Alex 125 <currency>`

### 设置余额：`/aceeco set`

需要把玩家余额直接设为指定金额时使用 `set`。

示例：`/aceeco set Alex 1000 <currency>`

### 查询交易历史：`/aceeco history`

管理员需要查看已记录的余额变更时使用 `history`。这个指令是只读的：不会改变余额或审计记录。省略 `player` 时列出所有账户的交易；省略 `currency` 时使用设置的默认货币；`page` 从 0 开始，每页显示 10 条。空页、找不到玩家、页码小于零都会有明确回复。

| 项目 | 说明 |
|---|---|
| 用法 | `/aceeco history [player] [currency] [page]` |
| 执行者 | 玩家或控制台 |
| 权限 | `aceeconomy.admin.history` |
| 排序 | 由新到旧，并以稳定的次序处理同一时刻的记录，因此重复查询的结果顺序一致 |

示例：

```text
/aceeco history
/aceeco history Alex
/aceeco history Alex <currency>
/aceeco history Alex <currency> 2
```

### 重载经济配置：`/aceeco reload`

修改经济配置后，从控制台执行这个指令。它不接受参数，玩家不能执行。

示例：`/aceeco reload`

### 回滚交易：`/aceeco rollback`

管理员需要根据 ID 复原一笔已记录的交易时使用 `rollback`。这是具有破坏性的管理操作，因此仅限控制台执行，而且需要同时拥有 `aceeconomy.admin` 与 `aceeconomy.admin.rollback`。交易 ID 是该笔交易的 UUID；格式不正确时会在查询前就被拒绝。

各种结果都会有明确回复：

| 结果 | 回复 |
|---|---|
| 成功 | 指出被回滚的交易，并列出该笔回滚的审计记录 ID。 |
| 已回滚过 | 说明该交易已经回滚、未做任何变更；再次提交同一个 ID 是安全的空操作，不会重复余额效果或审计记录。 |
| 找不到交易 | 错误提示：不存在这个 ID 的交易。 |
| ID 格式错误 | 错误提示：参数不是有效 UUID，不会进行任何查询。 |
| 转账对应腿缺失 | 错误提示：找不到转账的另一腿，无法安全回滚。 |
| 执行失败 | 错误提示：回滚未生效，该交易仍可重试。 |
| 标记写入失败 | 错误提示：回滚效果可能已经发生，但回滚标记缺失；请先检查存储并人工核对，再考虑重试。 |

示例：`/aceeco rollback 0b5f8a2e-1c3d-4e5f-6a7b-8c9d0e1f2a3b`

`rollback` 指令已经接入实际上线的指令介面，也有自动化契约测试覆盖，但**尚未在实际运行的服务器上验证过**：Folia/Bukkit 桥接的实机执行、真实 MySQL 存储，以及用真实数据进行的故障注入演练，都还是未完成的发布门槛。在这些验证完成之前，上表内容应视为设计规格，而不是实测结果。

### 创建逻辑备份：`/aceeco backup`

这个指令会在服务器运行时创建 v2 逻辑 JSON 快照。`label` 可省略；如果提供，只能使用安全的文件名字符。

| 项目 | 说明 |
|---|---|
| 用法 | `/aceeco backup [label]` |
| 执行者 | 玩家或控制台 |
| 权限 | `aceeconomy.admin.backup`（还需要主权限 `aceeconomy.admin`） |
| 存储位置 | 插件控制的 `<plugin data folder>/backups` |
| 输出 | 原子写入且不覆盖已有文件，并回报备份 ID |

快照是 v2 逻辑 JSON 模型，包含账户、余额、交易、已回滚标记与已消耗的一次性序号，但不包含数据库密码或 webhook 网址。没有独立的 `/backup` 主指令。

### 还原逻辑备份：`/aceeco restore`

还原会替换正式经济数据，因此只能从控制台执行，并且需要主权限与子指令权限。确认字符串区分大小写，只接受小写 `confirm`。

| 项目 | 说明 |
|---|---|
| 用法 | `/aceeco restore <backup-id> confirm` |
| 执行者 | 仅限控制台；有任何玩家在线时会拒绝 |
| 权限 | `aceeconomy.admin.restore`（还需要主权限 `aceeconomy.admin`） |
| 执行前检查 | 在动到正式数据前，先检查 JSON 结构、schema 版本、记录与配置货币的兼容性 |
| 安全备份 | 先备份当前状态；安全备份失败时中止还原 |
| 成功后 | 清除排行榜缓存，但不会热刷新 session 或 GUI。让玩家回来前必须重启服务器。 |

示例：`/aceeco restore 20260824T093000-aaaa1111 confirm`

### 导入余额：`/aceeco import`

当 EssentialsX 或 CMI 服务器的余额需要带进 v2 时使用。指令只能从控制台执行，且需要 `aceeconomy.admin` 与 `aceeconomy.admin.import`。没有精确的 `apply confirm` 时一律是预演：不写入、不备份、不消耗防重复状态。

| 项目 | 说明 |
|---|---|
| 用法 | `/aceeco import <essentials\|cmi> <path> [currency] [apply confirm]` |
| 执行者 | 仅限控制台 |
| 权限 | `aceeconomy.admin.import`（还需要主权限 `aceeconomy.admin`） |
| 来源格式 | Essentials：`<uuid>.yml` 玩家文件或其目录（`money:` 余额、可选 `last-account-name:`）。支持 EssentialsX 2.x userdata。CMI：管理员整理好的 UTF-8 对账文件（每行 `uuid,name,balance`，可有表头，`.csv`/`.txt`）。CMI 的 `cmi.sqlite.db` 二进制文件不受支持，会直接拒绝。 |
| 路径 | 相对于插件控制的 `<plugin data folder>/import` 目录。绝对路径、`..`、symlink、不存在的条目、过大文件、敏感文件名与各来源不支持的扩展名，都会在读文件前拒绝。 |
| 货币 | 省略时使用配置的默认货币。未知的货币 ID 会在备份前中止整个流程。 |
| 应用 | 只有精确的 `apply confirm`（`confirm` 限小写）会写入。写入前先建立 `pre-import` 安全备份；备份失败则整批不应用。重跑同一来源是安全的：已应用的记录会报告为跳过。 |
| 报告 | `applied` / `skipped` / `failed` 计数与失败摘要。只要有任何一笔失败，就不会宣称完全成功。 |

示例：

```text
/aceeco import essentials userdata
/aceeco import essentials userdata coin apply confirm
/aceeco import cmi balances.csv
/aceeco import cmi balances.csv coin apply confirm
```

先把 Essentials 的 `plugins/Essentials/userdata/` 文件（或整理好的 CMI 对账文件）复制到 `plugins/AceEconomy/import/`；指令不会读取该目录之外的任何位置。迁移流程见[从 AceEconomy v1 升级](upgrade-from-v1.zh-CN.md)。

## 常见指令错误

| 情况 | 检查方式 |
|---|---|
| 没有权限 | 确认执行者拥有主指令或子指令列出的权限。 |
| 执行者类型不对 | 玩家限定指令请在游戏内执行；`/aceeco reload`、`/aceeco rollback`、`/aceeco restore` 与 `/aceeco import` 请从控制台执行；`restore` 还要求没有玩家在线。 |
| 参数少了或多了 | 对照正确的子指令和用法。例如 `/baltop` 必须接 `top`，不接受页码。 |
| 找不到玩家 | 检查玩家名称后再试一次。 |
| 找不到货币 | 使用已配置的货币 ID；省略货币时会使用设置的默认值。 |
| 金额格式错误 | 请输入大于零、符合货币小数位数且未超过指令上限的数字。 |
| 经济操作被拒绝 | 根据返回的错误修正账户或经济条件，例如余额不足或超过债务上限。 |
| 回滚被拒绝 | 根据错误提示判断：需要有效的交易 UUID、已回滚的交易是空操作、标记失败需先人工核对再重试。 |
| 还原确认被拒绝 | 必须使用精确的小写 `confirm`：`/aceeco restore <backup-id> confirm`。`CONFIRM`、`Confirm` 与其他拼法都会被拒绝。 |
| 还原时有玩家在线 | 先让所有玩家离线，再从控制台重试。 |
| 还原的安全备份或快照检查失败 | 保留正式数据，根据错误提示检查原因；不要删除当前存储内容来强行还原。 |
| 导入确认被拒绝 | 没有精确的 `apply confirm` 就只是预览。请重跑为 `/aceeco import <essentials\|cmi> <path> [currency] apply confirm`。 |
| 导入路径被拒绝 | 路径必须相对于 `<plugin data folder>/import`。绝对路径、`..`、symlink、敏感文件名与不支持的扩展名都会在读文件前拒绝。 |
| 导入报告失败 | 看失败摘要：未知格式、无效数字与负余额不会被静默当成零。修好来源后重跑，只会补上还没应用的部分。 |
