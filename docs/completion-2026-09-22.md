# Regions 候选包验证记录 — 2026-09-22

## 交付状态

本轮在工作区已有的八种玩法改动上补齐故障保护，产出测试候选包；**未宣称完成全部发布验收**。
没有提交、推送、创建发布标签或改动生产服。运行中的历史报名记录在比赛关闭前仍占用玩家参赛资格。

## 本轮实现

| 问题 | 实现与回归 |
| --- | --- |
| 竞速／捉迷藏在装备任务结束前进入比赛，回执缺失可能挂起 | 准备阶段、10 秒超时、实例和 generation 校验；`ModeSafetyTest` |
| 战斗／竞速不等待异步传送结果 | 收到成功回执才继续，失败或未回执中止；`CombatMatchCoordinatorTest` |
| 死亡时收尾可能提前删除装备托管 | 死亡时保留 escrow，重生后恢复；先写背包、`saveData()`、确认、删除；写入失败保留记录 |
| 战斗确认写盘失败后内存仍显示已恢复 | 撤销内存确认标记，保留恢复证据；孤立 escrow 同样写确认 |
| 可跨玩法同时报名／观战 | `MatchAdmission` 以比赛 UUID 原子占位，三类服务共用；过期释放不会移除其他比赛占位 |
| 临时装备可以进入容器或展示实体 | `GearTransferListener` 拦截容器打开、点击、拖拽、方块放置、盔甲架／物品展示框交互；自身背包和 Regions 菜单放行 |
| 已淘汰的竞速玩家仍能触发终点 | 进度更新只接受 ALIVE，锁内复查；运行定义不随重新发布变化 |
| 单命战斗出局后重生到赛场 | 单命淘汰者重生到返回点，保持淘汰身份 |
| 中文校验显示原始字段 `respawn` | `issueLine` 共用 locale presenter，翻译 `args.field`；命令使用发送者语言 |

语言 v10→v11 使用既有缺键迁移机制，装备／比赛 schema 与插件版本号均未改变。

## 自动化

PowerShell，在仓库根目录执行：

```powershell
.\gradlew.bat :Regions:test :Regions:build :Regions:jarGate
.\gradlew.bat :Regions:shadowJar --rerun-tasks
.\gradlew.bat :Regions:jarGate
```

- 376 tests，0 failures，0 errors，0 skipped。
- build、强制 shadowJar、最终 jarGate 均成功。
- jarGate：EMBEDDED，`unrelocatedKotlin=0`，`reflectImpl=0`，445 个自身 class，字节码 major 61。
- 测试报告：`Regions/build/reports/tests/test/index.html`；XML：`Regions/build/test-results/test/`。
- 保留既有弃用提示（玩家 locale、旧聊天事件、Gradle 9 兼容提示）；本轮不升级编译或运行基线。
- `git diff --check -- Regions` 仍报告原有 `RegionCreationMenu.kt:806` 文件末尾空行；本轮未修改该文件。

## 实服证据

- 目录：`Regions/build/completion-smoke-20260922`，仅监听 `127.0.0.1:25579`。
- Windows 11，Microsoft Java 21.0.5+11，Paper 1.21.11 build 132（c5eb079）。
- 只安装 Regions；无 Lands、Contract、Vault、RuleGems；没有客户端连接。
- 数据来自 `Regions/run/plugins/Regions` 的隔离副本，含两个旧场地、语言 v8 与旧模板。
- 第一次启动执行语言 v8→v11。备份目录 `plugins/Regions/backups/migrations/20260922-182219/lang/` 的两份文件 SHA-256 分别与原目录对应文件相同；原文件未改。
- 最终 JAR 再次启动成功，未重复执行语言迁移，`Regions enabled with 2 configured regions.`。
- `regions doctor`：8 modes、9 templates，能力目录一致；Lands 缺席如实显示 missing。
- `regions validate ffa-probe`：通过；`regions game ffa-probe status`：空闲；控制台 `join`：只允许玩家执行。
- `regions validate`：旧 `integration-probe` 如实报告 Contract 不可用、返回点缺失、出生点不足；字段已显示“返回点”。这三项是测试数据的预期问题，reload 后仍保留。
- `regions reload`：成功；两次正常 stop 均成功，Regions 关闭无异常。
- 日志：`upgrade-v8.log` 保存第一次启动，`logs/latest.log` 保存最终 JAR 验证。首轮新平坦世界生成曾报告 `No key layers in MapLike[{}]`，属于隔离服生成配置；最终启动未再次出现。Paper 版本提示和旧场地校验警告未伪装成全服无告警。

## 产物

- `Regions/build/libs/regions-0.1.0.jar`，3,943,567 字节，非 plain JAR。
- SHA-256：`B886FE31C4024769D0E8BCE3063423AB850375D4CAF69D37C95A7DC70155AF04`。

## 尚未完成的发布门槛

真实 Lands Nation 与保护交互、目标 Folia/Purpur、多人 BO1/BO3／工会战／大乱斗／竞速／捉迷藏、
装备元数据与离线／崩溃恢复、真实 Contract/Vault 资金，以及生产数据副本的完整升级矩阵仍需执行。
本次旧数据验证只覆盖本机开发服隔离副本，不等同于生产数据升级验收。
执行入口仍为 [REAL_PLAYER_TEST.md](../REAL_PLAYER_TEST.md) 与 [PLAN.md §10.2](../PLAN.md)。
