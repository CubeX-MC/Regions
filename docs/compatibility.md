# 兼容性

## 支持矩阵

| 组件 | 状态 | 说明 |
| --- | --- | --- |
| Java 21 | 必需 | 构建与运行基线 |
| Paper 1.21.11 | 支持 | 默认开发和 CI API 基线 |
| Folia | 支持，需真人回归 | `folia-supported: true`，实体操作使用统一调度器 |
| Lands | 可选 | 仅在插件已启用且配置允许时提供 Source/工会能力 |
| RuleGems | 可选治理集成 | 日常管理仍由 Regions 权限与来源所有权共同判定 |
| Contract | 可选 WAGER 托管 | 仅 `dual_pvp`/`union_war`；缺席时无奖励场地仍可运行 |

## 原生能力

`scale` 依赖服务端存在对应原生 Attribute；默认 `require-native-support: true`。Potion、Sound、Material 与 World 名称在可获得 Bukkit registry/server 时于发布前校验，在纯单元测试环境使用结构校验。

## 升级与回退

升级前备份 `plugins/Regions`。配置含 `config-version`；region 存储、revision、审计、Effect lease 和装备 escrow 都应随备份保留。回退 JAR 前确认旧版本是否识别新 schema；不确定时先在测试服用备份副本验证，禁止直接删除 escrow 文件来“解决”恢复问题。

Lands 或 RuleGems reload 后运行 `/regions validate` 并观察 Source 可用性。外部依赖短暂不可用不应使 Regions 丢失 revision；是否阻止启动由 `required-for-startup` 决定。

Contract 或 Vault 不可用时保留 `reward-funding.yml`，恢复服务后 reload/restart 以相同 operation id 继续。降级旧 Regions JAR 前先确认没有 funding lease；降级 Contract 前先退款或结算所有带 `region-funding-*` metadata 的 WAGER。


### 2026-09-13 行为补充

- `/regions game <id> end`（含 `stop`）改成**两步确认**：第一次执行只打印会结束哪一场、影响多少参赛者与版本号，30 秒内对同一场地再执行一次才真的结束。命令语法与权限没变；本来就没有进行中的比赛时仍直接提示"没有进行中的游戏"。
- 活动大厅新增筛选按钮（玩法循环＋只看可报名＋清除），默认不筛选，老玩家的操作路径不变。
- 创建向导新增阶段 3：选择玩法与地块后进入"设置必填项"页，只列当前玩法需要的格子；缺项时不放行到发布页。中途关闭 GUI 后再次 `/regions create` 会回到该阶段。
- 报名新增一道闸门：如果玩家还有上一局的装备待恢复，报名会被拒绝并说明原因；恢复完成后即可正常报名。
- 装备托管期间禁止丢弃与拾取物品（比赛发的是临时装备）。
- 装备恢复、开赛屏障与结算判定的时序见 [架构](architecture.md) 的"判定时序与保护"。

## M3–M7 升级要点（服主侧）

- **新玩法模式**：`free_for_all`（单命大乱斗）已登记完整链路——模式 registry、capability descriptor 与参数、校验器、内置模板、双语文案、GUI 玩法页与向导卡片、命令补全。`free_event` 及其他既有玩法语义不变。
- **新权限节点**：`regions.game.join`、`regions.game.spectate`，挂在 `regions.use` children 下默认开放，并由命令实际检查。这两个节点此前被删除过（声明了却没人检查的权限是假承诺），现在随报名与观战的实现一起加回；权限插件里若还留着它们的显式条目，请重新确认取值符合预期。退出比赛不设额外权限：失去参与权限的玩家仍然能退出并拿回装备。
- **新配置键**：`modes.entry-prompt-cooldown-seconds`，默认 60，缺失时按 60 处理，无需版本迁移。走入战斗场地只显示一次带冷却的提示；报名必须显式执行 `/regions game <id> join` 或点大厅按钮。
- **出生点要求**（发布校验，点位格式沿用 `world,x,y,z[,yaw,pitch]`，多个点用 `;` 分隔）：
  - `dual_pvp`：`spawn-points` 必须恰好两个点，否则报错。
  - `union_war`：`spawn-points` 与 `spawn-points-b` 各需至少一个不重复的点；少于每队人数只是警告。
  - `free_for_all`：不重复出生点数不得少于 `max-players`（否则报错），点位间距小于 6 格给警告而不是阻断。
- **人数与时限校验**：`dual_pvp` 的 `best-of` 只接受 `1` 或 `3`，`round-seconds` 必须为正；`union_war` 的 `team-size` 必须在 2–10，`min-unions` 不得大于 `min-players`；`free_for_all` 的 `max-players` 上限 16。
- **模板变化**：内置 `dual_pvp` 带 `best-of: 1`、`round-seconds: 180`、`intermission-seconds: 5` 与两个出生点；内置 `union_war` 用 `team-size`（默认 5）表示每队人数；新增 `free_for_all` 模板（`min-players: 4`、`max-players: 4`、`timeout-seconds: 600`，要开到 16 人需在玩法页继续补出生点）。重新应用模板仍会整体替换草稿中的 Mode、Flags、Effects 与 Triggers，发布前先在预览里确认 diff。
- **已有场地**：已发布 revision 与草稿都保留，不会被后台静默改写。决斗的“恰好 2 人”由服务层强制，不再只依赖模板写 `max-players: 2`。旧场地若缺少新模式要求的出生点或人数设置，`/regions validate` 会报错并阻止重新发布，补齐后再发布。
- **重启与升级**：未收尾的比赛会被中止，不续打半局；中断资金经既有 lease 退款；未确认的装备恢复保留给离线玩家，登录后继续。备份时除既有文件外，把 `matches.yml` 一并纳入；它与装备 escrow、`reward-funding.yml` 是三个独立的真相来源。
- **奖励范围**：`free_for_all` 不接奖励，设置 `reward-source` / `reward-contract` 直接校验失败。

