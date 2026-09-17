# Regions 发布检查单

## 自动门禁

- [ ] `:Regions:test` 全部通过，无 skipped。
- [ ] `:Regions:build` 通过。
- [ ] `:Regions:shadowJar --rerun-tasks` 通过。
- [ ] `regions-<version>.jar` 存在，且包含 Regions 主类、Kotlin runtime、Cubex 模块与 relocation 后的 FoliaLib。
- [ ] CI 的 Regions matrix job 与总 JAR artifact 均成功。
- [ ] 配置、模板、语言文件和 `plugin.yml` 能从最终 shadow JAR 读取。

## M3–M7（战斗玩法）自动门禁：已完成

以下项目在本次交付时已执行并通过，留作后续回归对照：

- [x] `:Regions:test` 192 例通过，含 `CombatDamagePolicyTest`、`MatchRulesTest`（决斗／工会战／大乱斗规则与出生点解析）、`CombatMatchCoordinatorTest`（准备屏障中止、倒计时、BO1/BO3、重复死亡回调、重启恢复、已确认恢复的清理、国家对阵锁定、显式选国、伤害矩阵、关机同步恢复、enemy-only 外交）、`ChineseLocaleLeakTest`（中文界面英文术语与键路径泄漏）。
- [x] `:Regions:build` 通过，`:Regions:jarGate` 通过。
- [x] `free_for_all` 的注册链路（模式 registry、capability descriptor、校验器、模板、双语、GUI、命令补全）纳入回归。
- [x] 权限声明与代码检查一致：`regions.game.join` / `regions.game.spectate` 由命令实际检查。
- [x] 错误码清单改为从源码扫描（`ErrorCatalogTest`）：新增码漏文案会直接失败，`errors.*` 里积压没有生产者的键超过阈值会报警。
- [x] 加载冒烟：`Regions/run`（Paper 1.21.11 + Java 21.0.5，数据目录带升级前遗留文件）加载本工作树构建的 jar，插件启用成功、启动期能力目录校验通过、`templates.yml` v1→v2 迁移留备份、无 Regions 级错误。**不含任何多人流程**，见 `REAL_PLAYER_TEST.md` 顶部说明。
- [x] 并发草稿写入守卫（`saveDraft`/`publish` 的 `expectedRevision`）与陈旧写入拒绝有单测覆盖。

## M3–M7（战斗玩法）实服验收：尚未执行

以下项目**没有**做过，不得在发布记录里写成已通过：

- [ ] 按 `REAL_PLAYER_TEST.md` 的“战斗玩法验收脚本”完成决斗 BO1 与 BO3。
- [ ] 用真实 Lands 完成两个 Nation（每 Nation 至少 2 个 Land）的工会战，至少 2 对 2，并验证默认 5 对 5。
- [ ] 大乱斗 4 人全流程；16 人容量与恢复按 `PLAN.md` §10.2 记录。
- [ ] 装备恢复：比赛中死亡、断线、reload、正常停服、可控崩溃各一次，比较恢复前后的物品元数据。
- [ ] 走入区域不自动报名；join/ready/leave/spectate 与 5 秒离开宽限符合预期；观战者在两个方向都无法造成或承受比赛伤害。
- [ ] 结果展示（`/regions game <id> result` 与大厅结果卡）与实际胜负、原因、奖励状态一致。
- [ ] 真实资金：锁定 Nation 后换国／改名／换届不改变收款人；中断退款不产生第二次付款。
- [ ] 旧数据升级演练：语言 v6、自定义翻译、已有模板与 revision、未结装备与资金 lease。
- [ ] Purpur 26.1.2 build 2592 与真实目标版本 Folia 各自单独记录，不从 Paper 通过推断通过。

## 部署前

- [ ] 备份测试服世界、玩家数据和 `plugins/Regions`。
- [ ] 记录 Lands、RuleGems、Paper/Folia 和 Java 的实际版本。
- [ ] 只部署 `regions-<version>.jar`，清除同目录旧 Regions JAR，避免重复加载。
- [ ] 启动后确认没有 schema、Source、registry、线程或 escrow 恢复错误。
- [ ] 若启用 Contract 奖励，确认 Contract/Vault 先启用且 `reward-funding.yml` 没有待人工复核 lease。
- [ ] 执行 `/regions validate`，检查 Lands Source 与已发布 revision。

## 真人验收

- [ ] 完成 `REAL_PLAYER_TEST.md` 的 Paper 全量流程。
- [ ] 在真实 Folia 完成关键线程与恢复流程。
- [ ] 完成一次正常停服恢复和一次可控异常终止恢复。
- [ ] 完成 dual/union、race、hide-and-seek 各一轮并检查审计。
- [ ] 用真实 Vault 完成一次 WAGER 胜者结算、强制结束退款、Contract 暂停后恢复重试，核对没有双付。
- [ ] 验证失败记录包含 Region id、revision、角色、日志和恢复结果。

所有阻断项修复并回归后，才使用 `regions-v<version>` 标签创建发布。M3–M7 的实服验收与既有真人验收全部通过前，不创建发布标签。
