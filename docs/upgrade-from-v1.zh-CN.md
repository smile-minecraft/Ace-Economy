# 从 AceEconomy v1 升级

[English](upgrade-from-v1.md) · 简体中文 · [繁體中文](upgrade-from-v1.zh-TW.md)

这份指南写给手上有 v1、想换成 v2 的管理员。先说清楚：v2 是全新的安装，不是直接在旧数据上改。`version: "2.0"`、v2 的存储文件或数据表、v2 的插件 API，都和 v1 分开。v1 的配置、数据和 API 不会自动转过去。

## 目录

- [这次升级会改变什么](#这次升级会改变什么)
- [操作正式服务器前](#操作正式服务器前)
- [执行切换](#执行切换)
- [回退](#回退)
- [升级后的维护](#升级后的维护)
- [如果必须延续 v1 数据](#如果必须延续-v1-数据)

## 这次升级会改变什么

v2 要 Java 25、Paper/Folia 26.1.2，还有 `AceLib-1.2.0.jar`。插件本体是 `AceEconomy-2.1.0.jar`。Vault 和 PlaceholderAPI 还是可选的，有装才联动。Paper/Folia 26.1.2 是正式支持的版本；Folia 26.2 只有特定 build 通过验证（VERIFIED-BETA），其余 26.2 build 还没验证。

指令也换了样子。v2 一定要加子指令：`/money balance`、`/pay send`、`/withdraw cash`、`/baltop top`、`/bank open`，管理用 `/aceeco`。不要把 v1 才有的 history、rollback、import 写法，或旧银行券的说法，直接当 v2 指令来打。

如果服上还有人靠 v1 余额在玩，在你点头接受 v2 之前，请把整套 v1 留下来当回退来源。

## 操作正式服务器前

1. 排一个维护时段，用平时停服的方式停服。Minecraft 控制台指令是 `stop`。
2. 做一份带日期、能还原的完整服务器副本。至少要有 v1 的 `plugins/AceEconomy/` 目录、正在用的 v1 配置、v1 的 AceEconomy JAR、当前的 AceLib JAR，还有把旧安装救回来需要的服务器数据。
3. 副本放在正式服务器目录外面，不要拿它当 v2 的工作目录。

动手前先写下来：v1 真正在用的到底是哪个文件或数据库。不要因为 `data-v2.json`、v1 的 JSON 文件和 SQL 数据库里都有余额，就以为它们能互换。

## 执行切换

### 1. 从正式插件清单里移除 v1

停服后，把旧的 AceEconomy JAR 和旧的 AceLib JAR 移出正式的 `plugins/` 目录。让它们留在带日期的备份里，不要直接删掉。`plugins/` 里不要同时留两个 AceLib 版本。

### 2. 放入 v2 插件组合

把下面两个文件放进正式的 `plugins/` 目录：

```text
AceLib-1.2.0.jar
AceEconomy-2.1.0.jar
```

服务器真有用联动，才加 Vault 和 PlaceholderAPI。SQLite 或 MySQL 的 JDBC 驱动不用加，AceEconomy 的 JAR 里已经有了。

### 3. 创建 v2 配置

让 v2 自己生成 `plugins/AceEconomy/config.yml`，或拿你写好的 v2 配置去换掉生成出来的文件。打开确认里面有：

```yaml
version: "2.0"
```

货币、起始余额、债务设置、语言、存储选择、排行榜、可选的 Discord，都要重新填一次。不要复制 v1 的 `config-version`，也不要以为 v1 的货币名称和限制会自动进来。

### 4. 选择 v2 存储

文件型小服直接用 v2 默认的 JSON：

```yaml
storage:
  type: json
```

SQLite 会在插件数据目录里用一个新的 v2 文件：

```yaml
storage:
  type: sqlite
  sqlite:
    path: data-v2.sqlite
```

MySQL 或 MariaDB 走 v2 的 `storage.mysql.*` 设置。密码只留在本机，切换前先请数据库管理员按平时流程备份一次数据库。

v2 的 JSON 快照有自己的 schema 版本。v1 的数据文件不是 v2 快照，不能改个名变成 `data-v2.json`，也不能直接塞进 v2 的存储后端。

### 5. 启动并配置 v2

启动服务器，等到 `AceEconomy v2.1.0` 出现。先确认选好的 v2 存储已经打开，再改生成出来的设置。配置和语言文件的小修改从控制台打 `/aceeco reload`；动过插件文件、AceLib 或存储连接设置，就要整台重启。

### 6. 开放玩家前检查

拿测试账号把下面每一条都走一次：

- `/money balance` 回的是预期的 v2 账户余额。
- `/pay send <player> <amount> [currency]` 能完成一笔小额转账。
- 有开提现流程的话，`/withdraw cash <amount> [currency]` 能开出一张银行券。
- `/baltop top [currency]` 和 `/bank open` 回应正常。
- 有开 Vault、PlaceholderAPI、Discord 的话，行为和设置一致。

插件成功启用，不代表 v1 的余额搬过来了。要等管理员决定旧数据保留或重建，v2 才算真正就绪。

## 回退

回退的意思是把切换前的 v1 整套还原回来，不是叫 v1 去读 v2 的文件。

1. 用 `stop` 停掉 v2 服务器，等存档写完。
2. 把当前的 v2 `plugins/AceEconomy/` 目录和 v2 数据库备份另外复制一份，留着查问题；不要盖掉 v1 的备份。
3. 把 `AceEconomy-2.1.0.jar` 和 `AceLib-1.2.0.jar` 移出正式的 `plugins/` 目录。
4. 从带日期的备份里，把切换前的 v1 JAR、v1 配置和 v1 数据还原回来。
5. 启动服务器，确认 v1 数据读得到，才重新开门。

绝对不要把 `data-v2.json`、`data-v2.sqlite` 或任何 v2 快照复制到 v1 的数据位置。在回退决定结束前，把那份 v2 副本留好。

## 升级后的维护

v1 备份、第一份 v2 备份、当前的 v2 备份，请用不同名字分开留。改存储或修数据之前，先备份 `plugins/AceEconomy/`。用 SQL 存储的话，数据库管理员按平时流程产生的数据库备份也要留。

平时的配置和语言文件修改用 `/aceeco reload`。换新 JAR、新 AceLib、`storage.type`、SQLite 路径、MySQL 连接值或集成插件之后，一定要整台重启。不要拿 Bukkit 的 `/reload` 当升级流程。

日常管理清单请看[服务器运维](operations.zh-CN.md)。

## 如果必须延续 v1 数据

v2 不会直接读 v1 的文件，v1 的文件也不是 v2 快照。不要自己改 JSON、改文件名，或把 v2 指向 v1 的存储位置。[操作正式服务器前](#操作正式服务器前)留下的原始备份不要动。

旧余额真要带进 v2，请走导入流程。只认两种来源：EssentialsX 2.x 的玩家文件，以及整理好的 CMI 对账文件，全部从服务器控制台打：

1. 先把来源文件复制到 `plugins/AceEconomy/import/`。EssentialsX 就是带 `money:` 字段的 `<uuid>.yml` 玩家文件；CMI 就是整理好的 UTF-8 对账文件，每行 `uuid,name,balance`。`import/` 之外的路径一律不读。
2. 先预览，不写入，例如 `/aceeco import essentials userdata` 或 `/aceeco import cmi balances.csv`。预览报的路径或格式问题先修好，再往下走。
3. 预览结果没问题，才用精确的 `apply confirm` 写入，例如 `/aceeco import essentials userdata apply confirm`。写入前会先做一份 `pre-import` 安全备份；备份失败就整批不写。
4. 看 `applied` / `skipped` / `failed` 报告。只要有失败就不算完全成功，请看失败摘要，不要以为没进去的都变成零。
5. 同一份来源重跑是安全的：已经进去的会报成 `skipped`，重跑只会补上还没进去的。

完整用法、权限和错误对照，见[指令](commands.zh-CN.md#导入余额aceeco-import)的“导入余额”一节。
