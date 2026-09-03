# AceEconomy 整合

[English](integrations.md) · 简体中文 · [繁體中文](integrations.zh-TW.md)

当服务器管理员需要让 AceEconomy 和其他插件或 Discord 协作时，请使用本指南。内容涵盖必需的 AceLib，以及可选的 Vault、PlaceholderAPI、Floodgate 和 Discord 整合。插件作者请看[整合 API](integration-api.zh-CN.md)；语言文件请看[本地化](localization.zh-CN.md)。

## 目录

- [开始前](#开始前)
- [AceLib](#acelib)
- [Vault](#vault)
- [PlaceholderAPI](#placeholderapi)
- [Discord 通知](#discord-通知)
- [基岩版玩家（Floodgate）](#基岩版玩家floodgate)
- [相关指南](#相关指南)

## 开始前

AceEconomy 运行时需要 AceLib `v1.2.0`。请先装好 AceLib，再启动 AceEconomy。`plugin.yml` 把 AceLib 声明为硬依赖；如果没有一个就绪的 AceLib 服务，AceEconomy 根本不会启动。运行环境还需要 Java 25，以及符合本版本基准的 Paper/Folia 服务器。

Vault、PlaceholderAPI 和 Floodgate 是可选的软依赖。插件没装或没启用时，AceEconomy 会跳过对应的整合。Discord 不需要另外装服务器插件，它用的是 `config.yml` 里配置的 webhook。

## AceLib

请安装与本版本匹配的 AceLib，再启动带 AceEconomy 的服务器。AceLib 必须先就绪，AceEconomy 才能注册指令、消息和整合。

正常时，AceEconomy 会完成启动，相关指令也能用。如果 AceLib 缺失或还没就绪，插件会直接停用，而不是带着残缺的服务勉强跑。

无法启动时：

1. 确认 AceLib 的 JAR 已经安装并启用。
2. 确认本版本使用的是 AceLib `v1.2.0`。
3. 看 AceEconomy 启动时最早出现的那条错误。先把 AceLib 修好，再重启 AceEconomy。

## Vault

Vault 让那些使用标准 Vault `Economy` 服务的插件，可以把 AceEconomy 当作经济提供者。

### 安装与配置

1. 安装并启用 Vault。
2. 安装并启用 AceLib，再启用 AceEconomy。
3. 在 `config.yml` 里，把要交给 Vault 使用的货币设为 `default: true`：

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

Vault 只有一个经济余额，所以永远使用配置里的默认货币。具名货币仍然可以通过 AceEconomy 自己的多货币功能和 PlaceholderAPI 来使用；Vault 不会在每次调用时挑选货币。

### 成功现象

查询 Vault `Economy` 服务的插件，可以加载到一个名为 `AceEconomy` 的提供者。存款或提款成功时会回传新的余额；查询一个已有账户的余额，会回传该账户的余额。

### 玩家名称查询

名称型 Vault 方法（`hasAccount(String)`、`getBalance(String)`、`has(String, ...)`、`depositPlayer(String, ...)`、`withdrawPlayer(String, ...)`、`createPlayerAccount(String)`，包含世界名称重载）只通过在线玩家和已缓存的离线记录解析名称——调用线程上不会执行存储或网络 I/O。匹配时不区分大小写，UUID 仍是账户主键，因此改名后的玩家还是对应同一个账户。未知或空白名称永远不会被报成余额为 0 的有效账户：余额与 `has` 查询返回 `0.0`／`false`，并在服务器日志留下 `FINE` 级别的诊断；存款与提款则返回 `FAILURE` 并说明原因。世界名称参数会被接受但忽略：没有分世界余额，所有查询回报的都是全局余额。

### 排错

- **看不到提供者：** 先启用 Vault，再检查 AceEconomy。Vault 没装或没启用时，AceEconomy 会保持 Vault 提供者停用。
- **用玩家名称存款或提款报告失败：** 该名称对应不到在线或已缓存的玩家（检查拼写，或该玩家是否曾经加入过）。失败的操作不会被报成成功，Vault 的转接器也不会重试。
- **交易报告失败：** 检查玩家账户和金额。失败的操作不会被报成成功，Vault 的转接器也不会重試。
- **不存在的账户显示零或 false：** 这是余额或 `has` 查询的安全结果。请先通过 AceEconomy 的正常流程建立账户。
- **银行功能不可用：** AceEconomy 的 Vault 提供者不提供 Vault 的银行功能。

## PlaceholderAPI

PlaceholderAPI 在 `aceeco` 这个命名空间下提供 AceEconomy 的数值。请在启动 AceEconomy 之前，先安装并启用 PlaceholderAPI。

### 安装与确认

1. 安装并启用 PlaceholderAPI。
2. 重启服务器或重新加载，让 AceEconomy 注册它的扩展。
3. 把[整合 API](integration-api.zh-CN.md)里的某个占位符，放进支持 PAPI 的插件。

```text
Balance: %aceeco_balance_formatted%
Tokens: %aceeco_balance_token_formatted%
```

只要占位符正确、账户可用，使用中的插件就会收到余额值。未知或不可用的数值，会保留原始的占位符文字，不会被一个假数字顶替。

具名货币使用的是 `currencies.<id>` 里的内部货币 ID，只能用小写 `a-z`、`0-9` 和 `_`。要显示带格式的值，必须使用准确的 `_formatted` 后缀。

### 排错

- **画面直接显示出占位符：** 确认 PlaceholderAPI 已经安装并启用，再检查拼写和货币 ID。
- **具名货币解析不出来：** 用内部货币 ID，不要用显示名称。
- **默认值能用，但格式化的值用不了：** 使用准确的 `_formatted` 后缀；四种形式都列在 API 指南里。

## Discord 通知

Discord 会在交易提交之后，以尽力而为的方式发一条通知。它适合用来做审计风格的频道，但它本身不是交易结果：通知失败不会回滚或否决那笔经济操作。

### 配置

在 `plugins/AceEconomy/config.yml` 里设置这两个 `discord` 键：

```yaml
discord:
  enabled: true
  webhook-url: "https://discord.com/api/webhooks/<WEBHOOK_ID>/<WEBHOOK_TOKEN>"
```

请只在本机替换掉占位符。不要把真实的 webhook 网址写进共享文档、commit、issue 或求助消息。

保存之后，从服务器控制台执行 `/aceeco reload`，或者重启服务器。之后完成的交易，应该在 Discord 收到一条包含交易类型、发送者、接收者和金额的嵌入消息。

### 投递行为

- 投递是异步的，执行交易的指令不会干等网络。
- 请求被拒绝、超时、映射失败或传输失败时，通知器会忽略这个错误；已经提交的经济结果保持不变。
- 负载内容的字段有长度限制，设置里的密钥会被遮蔽；webhook 网址本身不会放进负载内容里。

### 排错

- **没有消息：** 检查 `discord.enabled`、webhook 网址，以及服务器到 Discord 的网络连通性，然后做一笔新交易；通知是针对已提交的事件发送的。
- **交易成功但 Discord 没消息：** 这就是尽力而为投递失败的正常情况。去修 webhook 或网络路径，不要把 Discord 当成余额的真正来源。
- **负载内容里出现了密钥：** 从交易文字里移除那个值，并轮换密钥。webhook 凭证只能放在本机配置里。

## 基岩版玩家（Floodgate）

Floodgate 是可选的软依赖。当它已安装、且 AceLib 判定某位玩家是基岩版客户端时，AceEconomy 会把聊天消息里的点击动作（执行指令、建议指令、打开链接、复制文本）换成可读提示，例如 `[基岩版：此处按钮无法使用，请手动执行：/baltop 1]`。Java 玩家永远收到带可点击按钮的原始消息。

### 安装与确认

1. 按照 Floodgate 文档，在服务器或代理上装好 Geyser + Floodgate。
2. 启动服务器；AceEconomy 会自动接上基岩版检测，不需要额外配置。
3. 用基岩版客户端执行一个回复带按钮的指令（例如 `/baltop`），确认提示文字可读。

### 限制

- 只有点击动作会降级；hover 文字在基岩版不保证显示。
- Floodgate 没装、被停用或查询失败时，所有玩家都收到原始消息——启动流程与 Java 行为完全不变。
- 提示文字来自 `lang/<locale>.yml` 的 `message.bedrock.fallback.*` 键；payload 以纯文本插入，不会被当成格式解析。

## 相关指南

- [整合 API](integration-api.zh-CN.md) — 给插件开发者的 Vault 与 PlaceholderAPI 公开契约。
- [本地化](localization.zh-CN.md) — v2 语言文件、键、占位符与重新加载流程。
- [配置指南](config.zh-CN.md) — 存储、货币和服务器配置参考。
