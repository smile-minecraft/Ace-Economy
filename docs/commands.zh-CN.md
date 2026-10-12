# 指令与权限

[English](commands.md) · 简体中文 · [繁體中文](commands.zh-TW.md)

AceEconomy v2 的指令一定要接子指令，例如 `/money balance`，不能只打 `/money`。这一页把玩家指令和管理员指令分开，列出语法、执行者、权限和别名。

## 目录

- [快速查表](#快速查表)
- [玩家用法](#玩家用法)
- [管理员权限](#管理员权限)
- [常见指令错误](#常见指令错误)

## 快速查表

| 主指令 | 子指令 | 用法 | 执行者 | 权限 | 别名 |
|---|---|---|---|---|---|
| `/money` | `balance` | `[player] [currency]` | 玩家或控制台；控制台必须提供 `player` | `aceeconomy.command.money` | `/balance` |
| `/money` | `history` | `[currency] [page]` | 仅限玩家 | `aceeconomy.command.history` | `/balance` |
| `/pay` | `send` | `<player> <amount> [currency]` | 仅限玩家 | `aceeconomy.command.pay` | 无 |
| `/withdraw` | `cash` | `<amount> [currency]` | 仅限玩家 | `aceeconomy.command.withdraw` | 无 |
| `/baltop` | `top` | `[currency]` | 玩家或控制台 | `aceeconomy.command.baltop` | 无 |
| `/bank` | `open` | 不接受参数 | 仅限玩家 | `aceeconomy.command.bank` | 无 |
| `/aceeco` | `give`、`take`、`set`、`history`、`reload`、`rollback`、`backup`、`restore`、`import` | 见管理员参考 | `reload`、`rollback`、`restore`、`import` 仅限控制台；`backup` 与其他子指令可由玩家或控制台执行 | `aceeconomy.admin` 加上子指令权限 | 无 |

`<必填>` 代表必须提供的值；`[可选]` 代表可省略的值。省略 `currency` 时，使用设置的默认货币。货币 ID 不区分大小写。

金额必须为有效正数、符合该货币定义之小数位数上限，且不得超过 `1,000,000,000,000,000`。格式不符时将拒绝执行，详见[常见指令错误](#常见指令错误)。

v2 指令规格列出的主指令别名只有 `/money` 的 `/balance`。它仍然要接 `balance` 子指令：`/balance balance [player] [currency]`。查明细也一样：`/balance history [currency] [page]`。没有独立的 `/backup` 或 `/restore` 主指令，请使用 `/aceeco` 的管理子指令。

## 玩家用法

### 查询余额：`/money balance`

查询自身或指定玩家的账户余额。玩家执行时若省略玩家名称则查询本人；控制台无自身账户，必须明确指定目标玩家。

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

### 查询交易明细：`/money history`

分页检视执行者本人的历史交易记录。仅限玩家执行且仅显示自身账户；不接受玩家参数，传入额外参数将直接拒绝并报错。

| 项目 | 说明 |
|---|---|
| 用法 | `/money history [currency] [page]` |
| 执行者 | 仅限玩家；控制台没有自己的玩家账户 |
| 权限 | `aceeconomy.command.history`（还需要主权限 `aceeconomy.command.money`） |
| 别名 | 主指令可用 `/balance`，但仍要保留 `history` 子指令 |

省略 `currency` 时，使用设置的默认货币。`page` 从 0 开始，每页显示 10 条，由新到旧——分页、排序与字段都和 `/aceeco history` 一致。空页会有明确回复。未知的货币与无效的页码会被拒绝并返回错误。

示例：

```text
/money history
/money history <currency>
/money history <currency> 2
```

### 转账：`/pay send`

将指定金额转账至目标玩家账户。此指令仅限游戏内在线玩家执行。

| 项目 | 说明 |
|---|---|
| 用法 | `/pay send <player> <amount> [currency]` |
| 执行者 | 仅限玩家 |
| 权限 | `aceeconomy.command.pay` |
| 别名 | v2 指令规格未提供别名 |

示例：`/pay send Alex 25 <currency>`

### 提取银行支票：`/withdraw cash`

从账户提取指定金额并转化为实体支票物品。此指令仅限玩家执行；执行前请确认物品栏具备可用空格。

| 项目 | 说明 |
|---|---|
| 用法 | `/withdraw cash <amount> [currency]` |
| 执行者 | 仅限玩家 |
| 权限 | `aceeconomy.command.withdraw` |
| 别名 | v2 指令规格未提供别名 |

示例：`/withdraw cash 100 <currency>`

### 查看排行榜：`/baltop top`

查询服务器财富排行榜。支持玩家与控制台执行；若未指定货币则查询默认货币。

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

打开银行交互界面。此指令不接受额外参数，仅限玩家执行。

| 项目 | 说明 |
|---|---|
| 用法 | `/bank open` |
| 执行者 | 仅限玩家 |
| 权限 | `aceeconomy.command.bank` |
| 别名 | v2 指令规格未提供别名 |

Java 玩家打开的是箱子菜单，按钮与格子对应如下：

- 存入（DEPOSIT）：第 4 格（上方中间）。
- 提取（WITHDRAW）：第 11 格与第 13 格（分别是 `100` 与 `500` 提取按钮）。
- 关闭（CLOSE）：第 15 格。

基岩版玩家（Geyser + Floodgate）打开的是原生表单：首页显示余额与存入、提取、关闭三个按钮；提取要输入金额与货币，再经过确认步骤才会执行。关闭表单、输入无效的值、离线，或在回应前遇到重新加载／断线，都不会执行任何交易。两种界面走同一条存取款路径，一次性序号、防重播与区域执行线程保证两边都成立。Floodgate 缺席时所有玩家都维持箱子菜单。

想把支票存进账户，先认清这条安全线：存入一张有效的 v2 银行支票时，服务器一定先完成防重播记录与入账，之后才会移除支票或减少堆叠数量。无效、用过或入账失败的支票会留在你的物品栏里。手持支票按右键可直接兑换，走与银行面板存入按钮相同的入账路径。如果入账已经完成但支票拿不掉，这笔钱照样算数，服务器审计日志会记下支票编号、玩家和实际入账金额，全程可追查。多张支票是先在副本上减一张再一次性写回，写入失败时整叠会留在手上：收好并联系管理员。单张支票的清除是一次写入，失败后格子状态无法从外部确认，因此只保证审计日志可追查、不保证票还在：把票根留好，不要重试（钱已经到账，重送会被拦下），直接联系管理员。管理员会拿支票编号去核对审计日志，先收回或作废重复的支票，再用比如 `/aceeco give` 把钱补上。

## 管理员权限

管理员的主指令是 `/aceeco`，v2 指令规格未提供别名。想动余额就用 `give`、`take`、`set`；想查记录就用 `history`，它只读取已记录的交易，不会改变余额。`reload`、`rollback`、`restore` 与 `import` 只能由控制台执行；`rollback` 要带交易 ID，`restore` 要带备份 ID 与精确的 `confirm`，`import` 要带来源与路径（写入时需精确的 `apply confirm`）。会动到余额的六个操作（`give`、`take`、`set`、`rollback`、`restore`、`import`）都可在尾段加上可选的 `reason` 留作审计；权限维持不变。`freeze`、`unfreeze`、`readonly` 是安全控制：玩家或控制台都能执行，各自有独立权限，每次成功都会在独立的控制审计里记下操作者与原因，绝不会伪造一笔金钱交易。搞混执行者或参数就看[常见指令错误](#常见指令错误)。

| 子指令 | 用法 | 执行者 | 子指令权限 | 别名 |
|---|---|---|---|---|
| `give` | `/aceeco give <player> <amount> [currency] [reason]` | 玩家或控制台 | `aceeconomy.admin.give` | 无 |
| `take` | `/aceeco take <player> <amount> [currency] [reason]` | 玩家或控制台 | `aceeconomy.admin.take` | 无 |
| `set` | `/aceeco set <player> <amount> [currency] [reason]` | 玩家或控制台 | `aceeconomy.admin.set` | 无 |
| `history` | `/aceeco history [player] [currency] [page]` | 玩家或控制台 | `aceeconomy.admin.history` | 无 |
| `reload` | `/aceeco reload` | 仅限控制台 | `aceeconomy.admin.reload` | 无 |
| `rollback` | `/aceeco rollback <transaction-id> [reason]` | 仅限控制台 | `aceeconomy.admin.rollback` | 无 |
| `backup` | `/aceeco backup [label]` | 玩家或控制台 | `aceeconomy.admin.backup` | 无 |
| `restore` | `/aceeco restore <backup-id> confirm [reason]` | 仅限控制台 | `aceeconomy.admin.restore` | 无 |
| `import` | `/aceeco import <essentials\|cmi> <path> [currency] [apply confirm] [reason]` | 仅限控制台 | `aceeconomy.admin.import` | 无 |
| `freeze` | `/aceeco freeze <player> [reason]` | 玩家或控制台 | `aceeconomy.admin.freeze` | 无 |
| `unfreeze` | `/aceeco unfreeze <player> [reason]` | 玩家或控制台 | `aceeconomy.admin.unfreeze` | 无 |
| `readonly` | `/aceeco readonly <on\|off> [reason]` | 玩家或控制台 | `aceeconomy.admin.readonly` | 无 |

每个操作除了主权限 `aceeconomy.admin`，还要各自的子指令权限。插件声明的默认值是：玩家指令权限为 `true`，管理员权限为 `op`，上面三个安全控制节点也是 `op`。另外，插件也声明 `aceeconomy.bypass.debt`，默认值为 `op`，用于跳过债务上限的权限。三个安全控制权限互相独立：只有 `freeze` 不能解冻，也不能切换唯读。

### 管理操作审计：操作者与原因

上述六个操作（`give`、`take`、`set`、`rollback`、`restore`、`import`）的审计规则是：每次成功执行都要记下谁做的、为什么做。操作者一律从指令执行者自动获取，不能用参数指定：玩家执行时记下其 UUID 与当时的显示名称，控制台执行时记下固定的控制台标记，因此无法冒充别的操作者。可选的尾段 `reason` 是单一 token（不可含空格）；空白的原因会被拒绝，超过 256 个字符会被拒绝，看起来像敏感数据的值（例如 IPv4 或 IPv6 地址的压缩、完整、中括号、映射或区域形式、网址或 token）会被拒绝且不会把原文回显出来，也不会做任何网络查询。完整形式的 IPv6 地址即使嵌在较长的 token 里（例如带有 `ip=` 前缀）也会同样被拒，一般含单个冒号的 token 不受影响。还原快照若带有损坏的操作记录，会在动到线上数据之前被拒，诊断只会指出记录的位置与字段，不会回显存储的值，线上数据保持不动。这层检查只是基本的过滤，不声称能识别所有敏感字符串。省略 `reason` 时，记录会记下明确的默认值 `unspecified`，不会留空。同时给货币与原因时，货币在前、原因在最后。审计字段只在交易成功写入时跟交易放在同一次原子提交里存档；失败或回滚不会留下孤立的审计记录。`restore` 会另外留一条独立的操作记录，记下来源备份与安全备份两个 ID，并跟还原放在同一次提交里，不会另外产生一笔零元存入；这条记录只在内部保存，不新增公开查询指令。一般操作的原由跟交易存在一起，仍可通过 `/aceeco history` 的 `reasonContains` 过滤命中；独立的还原操作记录不会显示为明细条目。

### 增加余额：`/aceeco give`

需要为玩家增加余额时使用 `give`。尾段可加上可选的 `reason` 留作审计，规则见上方审计一节。

示例：`/aceeco give Alex 500 <currency>`。附原因：`/aceeco give Alex 500 <currency> monthly-reward`。

### 扣除余额：`/aceeco take`

需要从玩家余额中扣除金额时使用 `take`。尾段可加上可选的 `reason` 留作审计，规则见上方审计一节。

示例：`/aceeco take Alex 125 <currency>`。附原因：`/aceeco take Alex 125 <currency> penalty-case`。

### 设置余额：`/aceeco set`

需要把玩家余额直接设为指定金额时使用 `set`。尾段可加上可选的 `reason` 留作审计，规则见上方审计一节。

示例：`/aceeco set Alex 1000 <currency>`。附原因：`/aceeco set Alex 1000 <currency> balance-fix`。

### 冻结账户：`/aceeco freeze`

怀疑账户被盗或需要立刻止血时使用 `freeze`。用法：`/aceeco freeze <player> [reason]`。玩家或控制台持有 `aceeconomy.admin.freeze` 就能执行。玩家不存在会明确拒绝，不动任何状态；重复冻结已冻结的账户是幂等的，状态与版本号都不变。成功会记一条控制审计（可按原因查到），绝不伪造金钱交易。变更在玩家查找之后走共用 IO 线程，指令会立刻返回，回复只在完成后发一次，慢控制落盘不会在控制路径上卡住地域线程；已通过 permit 的普通金流在控制写锁落盘期间仍会等待（已接受的线性化代价，慢盘量测留给整合验证）。冻结期间该账户不能发起任何余额变更（转出、提领、兑票、`give`/`take`/`set` 都没有例外），也不能接收（转入、外部存入都会明确失败）；被拒绝的转账一分钱都不会扣，被拒绝的兑票也不会烧掉序号。查余额、查明细、排行榜不受影响。

示例：`/aceeco freeze Alex incident-case`。拒绝消息走 `general.account-frozen`。

### 解冻账户：`/aceeco unfreeze`

调查结束后使用 `unfreeze` 恢复。用法：`/aceeco unfreeze <player> [reason]`。它需要独立的 `aceeconomy.admin.unfreeze` 权限，只有冻结权限是不够的。未知玩家与重复解冻的处理与 `freeze` 相同（明确拒绝、幂等无操作），成功同样记一条控制审计。

示例：`/aceeco unfreeze Alex cleared-case`。

### 全服唯读：`/aceeco readonly`

需要一键停住全服金流时使用 `readonly`。用法：`/aceeco readonly <on|off> [reason]`。玩家或控制台持有 `aceeconomy.admin.readonly` 就能执行。打开后所有账户的余额变更都会被拒绝，消息走 `general.global-readonly`；查询、预览、备份不受影响，管理员随时可以关掉。重复执行当前状态是幂等的。切换跟 `freeze` 一样走共用 IO 线程，指令立刻返回，回复只在完成后发一次。已通过 permit 的普通金流在控制写锁落盘期间仍会等待（已接受的线性化代价，慢盘量测留给整合验证）。控制文件损坏时服务器本来就已经 fail-closed 到唯读，这时关闭会被忽略（先修文件再重启），绝不会绕过保护。每次切换都记一条控制审计。

示例：

```text
/aceeco readonly on emergency-hold
/aceeco readonly off recovered
```

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

### 重新加载经济配置：`/aceeco reload`

修改经济配置后，想套用就从控制台执行这个指令。它不接受参数，玩家不能执行。

示例：`/aceeco reload`

### 回溯交易：`/aceeco rollback`

管理员需要根据 ID 复原一笔已记录的交易时使用 `rollback`。这是具有破坏性的管理操作，因此仅限控制台执行，而且需要同时拥有 `aceeconomy.admin` 与 `aceeconomy.admin.rollback`。交易 ID 是该笔交易的 UUID；格式不正确时会在查询前就被拒绝。回溯遵循与普通交易相同的负债规则：`economy.allow-negative-balance` 为 `false` 时，会导致余额变负的回溯会被拒绝且不改变任何数据；为 `true` 时，回溯可以在设定的负债额度内产生负余额。尾段可加上可选的 `reason`，会跟回溯一起记录（规则见上方审计一节）。

各种结果都会有明确回复：

| 结果 | 回复 |
|---|---|
| 成功 | 指出被回溯的交易，并列出该笔回溯的审计记录 ID。 |
| 已回溯过 | 说明该交易已经回溯、未做任何变更；再次提交同一个 ID 是安全的空操作，不会重复余额效果或审计记录。 |
| 找不到交易 | 错误提示：不存在这个 ID 的交易。 |
| ID 格式错误 | 错误提示：参数不是有效 UUID，不会进行任何查询。 |
| 转账对应腿缺失 | 错误提示：找不到转账的另一腿，无法安全回溯。 |
| 负债拒绝 | 错误提示：该回溯会导致余额变负，而 `economy.allow-negative-balance` 为 `false`（或超出负债额度）；未改变任何数据，该交易仍可重试。 |
| 执行失败 | 错误提示：回溯未生效，该交易仍可重试。 |
| 标记写入失败 | 错误提示：回溯效果可能已经发生，但回溯标记缺失；请先检查存储并人工核对，再考虑重试。 |

示例：`/aceeco rollback 0b5f8a2e-1c3d-4e5f-6a7b-8c9d0e1f2a3b`。附原因：`/aceeco rollback 0b5f8a2e-1c3d-4e5f-6a7b-8c9d0e1f2a3b duplicate-payout`。

`rollback` 指令已经接入实际上线的指令接口，也有自动化合约测试覆盖，但**尚未在实际运行的服务器上验证过**：Folia/Bukkit 桥接的实机执行、真实 MySQL 存储，以及用真实数据进行的故障注入演练，都还是未完成的发布门槛。在这些验证完成之前，上表内容应视为设计规格，而不是实测结果。

### 创建逻辑备份：`/aceeco backup`

想在服务器运行时留一份可还原的记录就用这个指令。它会创建一份 v2 逻辑 JSON 快照。`label` 可省略；如果提供，只能使用安全的文件名字符。

| 项目 | 说明 |
|---|---|
| 用法 | `/aceeco backup [label]` |
| 执行者 | 玩家或控制台 |
| 权限 | `aceeconomy.admin.backup`（还需要主权限 `aceeconomy.admin`） |
| 存储位置 | 插件控制的 `<plugin data folder>/backups` |
| 输出 | 原子写入且不覆盖已有文件，并回报备份 ID |

快照是 v2 逻辑 JSON 模型，包含账户、余额、交易、已回溯标记与已消耗的一次性序号，但不包含数据库密码或 webhook 网址。没有独立的 `/backup` 主指令。

### 还原逻辑备份：`/aceeco restore`

还原会替换正式经济数据，因此只能从控制台执行，并且需要主权限与子指令权限。确认字符串区分大小写，只接受小写 `confirm`。尾段可加上可选的 `reason`；这次执行会另外留一条独立操作记录，规则见上方审计一节。执行前先请所有玩家离线，失败就看[常见指令错误](#常见指令错误)。

| 项目 | 说明 |
|---|---|
| 用法 | `/aceeco restore <backup-id> confirm [reason]` |
| 执行者 | 仅限控制台；有任何玩家在线时会拒绝 |
| 权限 | `aceeconomy.admin.restore`（还需要主权限 `aceeconomy.admin`） |
| 执行前检查 | 在动到正式数据前，先检查 JSON 结构、schema 版本、记录（含还原操作记录）与配置货币的兼容性；损坏的快照会被拒绝且正式数据保持不变 |
| 安全备份 | 先备份当前状态；安全备份失败时中止还原 |
| 成功后 | 清除排行榜缓存，但不会热刷新 session 或 GUI。让玩家回来前必须重启服务器。 |

示例：`/aceeco restore 20260824T093000-aaaa1111 confirm`。附原因：`/aceeco restore 20260824T093000-aaaa1111 confirm wrong-payout`。

### 导入余额：`/aceeco import`

当 EssentialsX 或 CMI 服务器的余额需要带进 v2 时使用。指令只能从控制台执行，且需要 `aceeconomy.admin` 与 `aceeconomy.admin.import`。没有精确的 `apply confirm` 时一律是预演：不写入、不备份、不消耗防重复状态。预演或套用都可在尾段加上可选的 `reason`，套用时会跟该次执行一起记录（规则见上方审计一节）。

| 项目 | 说明 |
|---|---|
| 用法 | `/aceeco import <essentials\|cmi> <path> [currency] [apply confirm] [reason]` |
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
/aceeco import cmi balances.csv coin apply confirm migration-topup
```

先把 Essentials 的 `plugins/Essentials/userdata/` 文件（或整理好的 CMI 对账文件）复制到 `plugins/AceEconomy/import/`；指令不会读取该目录之外的任何位置。详细用法见本页 `import` 章节。

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
| 回溯被拒绝 | 根据错误提示判断：需要有效的交易 UUID、已回溯的交易是空操作、标记失败需先人工核对再重试。 |
| 还原确认被拒绝 | 必须使用精确的小写 `confirm`：`/aceeco restore <backup-id> confirm [reason]`。`CONFIRM`、`Confirm` 与其他拼法都会被拒绝。 |
| 还原时有玩家在线 | 先让所有玩家离线，再从控制台重试。 |
| 还原的安全备份或快照检查失败 | 保留正式数据，根据错误提示检查原因；不要删除当前存储内容来强行还原。 |
| 导入确认被拒绝 | 没有精确的 `apply confirm` 就只是预览。请重跑为 `/aceeco import <essentials\|cmi> <path> [currency] apply confirm [reason]`。 |
| 导入路径被拒绝 | 路径必须相对于 `<plugin data folder>/import`。绝对路径、`..`、symlink、敏感文件名与不支持的扩展名都会在读文件前拒绝。 |
| 审计原因被拒绝 | `reason` 必须是单一 token（不含空格、至多 256 个字符），也可省略；看起来像敏感数据的值会被拒绝且不会回显原文。 |
| 导入报告失败 | 看失败摘要：未知格式、无效数字与负余额不会被静默当成零。修好来源后重跑，只会补上还没应用的部分。 |
