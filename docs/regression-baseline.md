# 自动回归基线

2026-09-22：376 项通过。新增 `ModeSafetyTest`（跨玩法占位、死亡待恢复、玩家数据写入失败、
旧终点与淘汰选手、准备超时）、`GearTransferListenerTest`（容器转移及背包／菜单放行），
并覆盖战斗异步传送失败和双语校验字段翻译；见 [本轮证据](completion-2026-09-22.md)。

合并或部署候选版本必须满足：

```text
./gradlew :Regions:test
./gradlew :Regions:build
./gradlew :Regions:shadowJar --rerun-tasks
```

当前自动化覆盖以下风险域：

- 配置文本、默认区域与模板结构。
- capability 真值、深层 Action/Effect 和运行时 registry 参数验证。
- 权限、所有权、发布、revision diff、rollback、模板和审计存储。
- 重叠解析、Effect 组合失败重试、持久化 lease 跨实例恢复。
- Lands 禁用状态、trial 隔离和 Paper 停服同步会话清理。
- 赛跑超时、旧比赛/回合计时器隔离、玩家离开后的旧战斗启动任务隔离。
- Region 存储 schema/revision 读写，以及坏 revision/lease 的 fail-closed reload。
- `deny` Flag 到 Effect 的合成桥接、显式 Effect 的优先级、以及 bypass/创造模式豁免。
- Trigger 枚举与 TRIGGER descriptor 的能力真值一致性。
- Effect escrow 批量写入的原子性与恢复后清空。
- 检测按来源分组、已发布集合缓存失效与不可用来源短路。
- zh_CN/en_US 键集一致性、基线版本一致性与 en_US 无源语言残留。
- Contract API 反射 ABI、WAGER 参与方/工会胜者映射、operation 冲突、provider 暂停后的 SETTLING 重放与终态清理。
- 错误码目录：每个 `errors.*` 码的双语覆盖与占位符一致性，以及术语标签（`labels.*`）的双语覆盖。
- 数据迁移：语言文件 6→7（含自定义 lore 不被拆分）与 `templates.yml` 1→2（仅转换精确匹配内置默认的字段）的幂等性。
- 玩家入口纯逻辑：活动大厅分页与不可报名原因优先级、报名页规则摘要、创建向导的 ID 生成与草稿隔离、
  发布页错误码到设置页的跳转映射。
- 权限声明：参与类叶节点随 `regions.use`、管理叶节点不随，且 `plugin.yml` 里声明的 `regions.game.*`
  与代码真正检查的集合一致（死节点会让服主以为能力已存在）。
- 关机清理：enable 中途失败后的 disable 不会因未构造的服务而二次报错；战斗装备恢复的顺序与故障注入。
- 战斗报名流程：走入区域只触发一次带冷却的提示、不自动报名；join / ready / unready / leave / spectate
  的状态转换，以及进行中比赛离开的 5 秒宽限与退出／被踢立即弃权。
- 准备屏障：资金预留与国家映射、逐人 escrow 持久化、发装备与传送任一环节失败都会撤销整场开赛，
  并恢复已经取走的状态。
- 比赛持久化与重启恢复：未收尾比赛被中止而不续打半局；待恢复记录对离线玩家保留；
  已确认的恢复只清理记录、不覆盖玩家新背包。
- 伤害隔离：同局敌对放行，候场／观战／局外／其他比赛双向拒绝；近战、投射物、药水与滞留云雾、
  宠物、玩家引燃 TNT 的归属；玩家来源但无法归属的伤害按拒绝处理；生物与环境伤害不受影响。
- 比赛规则：决斗 BO1/BO3 与回合上限、双方同死与回合超时、工会战等额阵容与同队友伤禁止、
  大乱斗单命淘汰与并列排名、出生点解析与间距告警、`free_for_all` 拒绝 `reward-source: contract`。
- 结果展示：`/regions game <id> result` 与大厅结果卡的 outcome / 获胜方 / 原因 / 奖励状态与实际终态一致。

自动化不能替代真实服务端线程模型、外部插件行为、GUI 交互和多人体验；这些由 `REAL_PLAYER_TEST.md` 覆盖。任何失败测试、测试报告中的 skipped、启动栈追踪、线程违规或无法恢复的 escrow 都是测试环境准入阻断项。

M3–M7 的自动化部分已纳入本基线（`:Regions:test` 192 例）；`PLAN.md` §10.2 的实服与真人验收尚未执行，真实 Lands API 行为、Purpur／Folia 运行和旧数据升级演练都不在上表覆盖范围内。
