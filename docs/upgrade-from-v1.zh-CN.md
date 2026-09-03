# 从 AceEconomy v1 升级

[English](upgrade-from-v1.md) · 简体中文 · [繁體中文](upgrade-from-v1.zh-TW.md)

要把现有的 v1 安装换成 AceEconomy v2，请使用本指南。v2 是一次全新的安装，不是在 v1 数据结构上原地升级：`version: "2.0"`、v2 的存储文件或数据表，以及 v2 的插件 API，都和 v1 是分开的。v1 的配置、数据和 API 都不会自动转换。

## 目录

- [这次升级会改变什么](#这次升级会改变什么)
- [操作正式服务器前](#操作正式服务器前)
- [执行切换](#执行切换)
- [回退](#回退)
- [升级后的维护](#升级后的维护)
- [如果必须延续 v1 数据](#如果必须延续-v1-数据)

## 这次升级会改变什么

v2 的运行环境需要 Java 25、Paper/Folia 26.1.2，以及 `AceLib-1.2.0.jar`。v2 的插件文件是 `AceEconomy-2.1.0.jar`。Vault 和 PlaceholderAPI 仍然是可选整合。Paper/Folia 26.1.2 是正式支持的服务器线；Folia 26.2 仅在特定 build 上通过验证（VERIFIED-BETA），其余 26.2 build 未验证。

v2 的指令采用明确的子指令形式：`/money balance`、`/pay send`、`/withdraw cash`、`/baltop top`、`/bank open`，以及 `/aceeco` 的管理指令。不要把 v1 专用的 history、rollback、import，或者旧的银行券数据说明，当成 v2 指令来用。

如果服务器还需要 v1 的余额，在服务器管理员接受 v2 之前，请把完整的 v1 安装保留下来作为回退来源。

## 操作正式服务器前

1. 安排一个维护时段，用平常的控制台或服务管理方式停服。Minecraft 的控制台指令是 `stop`。
2. 做一份带日期、可以还原的完整服务器副本。至少包含 v1 的 `plugins/AceEconomy/` 目录、正在使用的 v1 配置、v1 的 AceEconomy JAR、当前的 AceLib JAR，以及还原旧安装所需的服务器数据。
3. 把副本放在正式服务器目录之外，不要把它当成 v2 文件的工作目录。

开始之前，先记清楚 v1 实际使用的权威存储文件或数据库是哪一个。不要因为 `data-v2.json`、v1 的 JSON 文件和 SQL 数据库里都存有余额，就以为它们可以互相替换。

## 执行切换

### 1. 从正式插件清单里移除 v1

停服后，把旧的 AceEconomy JAR 和旧的 AceLib JAR 移出正式的 `plugins/` 目录。把它们留在那份带日期的备份里，不要直接删掉。`plugins/` 里不要同时留着两个 AceLib 版本。

### 2. 放入 v2 插件组合

请把以下文件放进正式的 `plugins/` 目录：

```text
AceLib-1.2.0.jar
AceEconomy-2.1.0.jar
```

只有服务器确实用到这些整合时，才加入 Vault 和 PlaceholderAPI。不要另外加 SQLite 或 MySQL 的 JDBC 驱动；两者都已经包含在 AceEconomy 的 JAR 里了。

### 3. 创建 v2 配置

让 v2 自己生成 `plugins/AceEconomy/config.yml`，或者用你明确写好的 v2 配置去替换生成出来的文件。确认里面含有：

```yaml
version: "2.0"
```

重新填写货币、起始余额、债务设置、语言、存储选择、排行榜设置，以及可选的 Discord 设置。不要复制 v1 的 `config-version` 区块，也不要假设 v1 的货币名称和限制已经被导入。

### 4. 选择 v2 存储

文件型服务器可以使用 v2 默认的 JSON：

```yaml
storage:
  type: json
```

SQLite 会在插件数据目录内使用一个新的 v2 文件：

```yaml
storage:
  type: sqlite
  sqlite:
    path: data-v2.sqlite
```

MySQL 或 MariaDB 使用 v2 的 `storage.mysql.*` 设置。密码只留在本机，并且在切换之前，按数据库管理员平时的流程先完成一次数据库备份。

v2 的 JSON 快照有自己的 schema 版本。v1 的数据文件不是 v2 快照，既不能只改个名变成 `data-v2.json`，也不能直接塞进 v2 的存储后端。

### 5. 启动并配置 v2

启动服务器，等待 `AceEconomy v2.1.0` 启用。确认选定的 v2 存储已经打开，再按需修改生成出来的设置。配置和语言文件的变更，请从控制台执行 `/aceeco reload`；改动了插件文件、AceLib 或存储连接设置之后，则必须完整重启。

### 6. 开放玩家前检查

请用测试账号确认：

- `/money balance` 能回传预期的 v2 账户余额。
- `/pay send <player> <amount> [currency]` 能完成一笔小额转账。
- 启用该流程时，`/withdraw cash <amount> [currency]` 能开出一个银行券。
- `/baltop top [currency]` 和 `/bank open` 回应正常。
- 已经启用的 Vault、PlaceholderAPI 和 Discord，行为符合设置。

插件成功启用，并不代表 v1 的余额已经迁移过来。只有在管理员决定好旧数据要如何保留或重建之后，v2 才算真正准备完成。

## 回退

回退是指还原切换前的完整 v1 安装，而不是让 v1 去读取 v2 的文件。

1. 用 `stop` 停止 v2 服务器，等待保存完成。
2. 另外复制一份当前的 v2 `plugins/AceEconomy/` 目录和任何 v2 数据库备份，留作调查；不要覆盖 v1 的备份。
3. 把 `AceEconomy-2.1.0.jar` 和 `AceLib-1.2.0.jar` 移出正式的 `plugins/` 目录。
4. 从那份带日期的备份里，还原切换前的 v1 JAR、v1 配置和 v1 数据。
5. 启动服务器，确认 v1 数据可读之后，再让玩家回来。

绝对不要把 `data-v2.json`、`data-v2.sqlite` 或任何 v2 快照，复制到 v1 的数据位置。在回退决策结束之前，都要保留那份 v2 副本。

## 升级后的维护

请用不同的名称，分别保留 v1 备份、第一份 v2 备份，以及当前的 v2 备份。改动存储或修复数据之前，先备份 `plugins/AceEconomy/`。使用 SQL 存储时，也要保留数据库管理员按平时流程产生的数据库备份。

普通的配置和语言文件变更，使用 `/aceeco reload`。更换新 JAR、新 AceLib、`storage.type`、SQLite 路径、MySQL 连接值或整合插件之后，必须完整重启。不要把 Bukkit 的 `/reload` 当成升级流程。

日常管理清单请看[服务器运维](operations.zh-CN.md)。

## 如果必须延续 v1 数据

v2 不会直接读 v1 的文件，v1 的文件也不是 v2 快照。不要自己改 JSON、改文件名，或把 v2 指向 v1 的存储位置。[操作正式服务器前](#操作正式服务器前)留下的原始备份不要动。

旧余额真要带进 v2，请走导入流程。只吃两种来源：EssentialsX 2.x 的玩家文件，以及整理好的 CMI 对账文件，都要从服务器控制台执行：

1. 先把来源文件复制到 `plugins/AceEconomy/import/`。EssentialsX 就是带 `money:` 字段的 `<uuid>.yml` 玩家文件；CMI 就是整理好的 UTF-8 对账文件，每行 `uuid,name,balance`。`import/` 之外的路径一律不读。
2. 先预览，不写入，例如 `/aceeco import essentials userdata` 或 `/aceeco import cmi balances.csv`。预览报的路径或格式问题先修好，再往下走。
3. 预览结果没问题，才用精确的 `apply confirm` 写入，例如 `/aceeco import essentials userdata apply confirm`。写入前会先做一份 `pre-import` 安全备份；备份失败就整批不写。
4. 看 `applied` / `skipped` / `failed` 报告。只要有失败，就不算完全成功，请看失败摘要，不要以为没进去的都变成零。
5. 同一份来源重跑是安全的：已经进去的会报成 `skipped`，重跑只会补上还没进去的。

完整用法、权限和错误对照，见[指令](commands.zh-CN.md#导入余额aceeco-import)的“导入余额”一节。
