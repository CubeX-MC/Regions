# Regions

Regions 是 CubeX-MC 的可发布场地与小游戏框架。它将 Lands/Cuboid 区域来源、RuleGems 管理权限、可组合 Flag/Effect/Trigger，以及战斗、赛跑和捉迷藏 Mode 统一在带 revision 的发布流程中。

## 定位

外部领地插件负责"哪里是区域、谁拥有区域"；Regions 负责"这个区域在服务器规则中**变成什么场地**"。

它不重新实现领地、选区和所有权，而是把 Lands、Cuboid（以及未来的 Residence、WorldGuard）统一成
可配置的 Region，再允许 RuleGems 的统治者为这些区域安装玩法、规则、效果和 IF/THEN 行为。

日常场地管理者不是普通玩家，也不是仅凭领地主身份就能操作的人，而是**同时满足**两个条件的场地主：
持有 `regions.admin`（由 RuleGems 统治者身份授予）**且**是该 Region 所绑定外部区域的实际主人。
服务器管理员只负责紧急接管与事故恢复，走独立的 `regions.superadmin`。

**不做什么**（避免误装）：

- 不是领地插件，不提供圈地与所有权本身。
- 不做脚本语言；复杂玩法写成 Mode，而不是塞进 YAML。
- 未实现或校验不通过的能力**不会**在 GUI 里伪装成可用（fail closed）。

## 运行要求

- Java 21
- Paper 1.21.11 或兼容 Folia 版本
- 可选：Lands（区域和工会来源）、RuleGems（治理侧集成）、Contract（WAGER 奖励托管）

Lands 被配置为可选集成；插件不存在或未启用时，Lands Source 不会被宣告为可用。是否要求它在启动时存在由 `integrations.lands.required-for-startup` 控制。

Contract 同样只是可选连接。`dual_pvp` 和 `union_war` 可在 Mode 中设置 `reward-source: contract` 与 `reward-contract: <WAGER id>`；发布及开赛前会确认该 WAGER 已由双方接受且仍在进行。Contract 缺席或状态不符时只阻止这个有奖励的场地发布/开赛，Regions 其他场地照常运行。`free_for_all` 不接奖励，设置 `reward-source` / `reward-contract` 会在校验阶段被拒绝，而不是静默忽略。

## 构建与自动检查

```text
./gradlew :Regions:test
./gradlew :Regions:build
./gradlew :Regions:shadowJar
./gradlew :Regions:runServer
```

可部署产物是 `Regions/build/libs/regions-<version>.jar`；不要部署 `*-plain.jar`。根仓库 CI 会单独构建、测试并上传 Regions，同时支持 `regions-v<version>` 标签发布。

`runServer` 是 R1 托管连接的本地联合服：它会装入 Contract 的部署 jar、Vault 与 EssentialsX，
但不会给 Regions 增加任何编译期插件依赖。用
`./gradlew :Regions:runServer -PregionsRunWithContract=false` 可在独立的
`Regions/run-no-contract` 目录验证 Contract 缺席时的降级与 lease 保全。

## 管理流程

日常场地变更遵循：创建草稿 → GUI/命令编辑 → `validate`/`preview` → 隔离 `trial` → `publish`。运行时只读取已发布 revision；回滚会生成新 revision，不覆盖历史。

场地详情页分两层：基础页只放场地主日常的 5 个操作（玩法设置、场地点位、检查并发布、比赛管理、隔离试运行），规则组合、临时效果、触发动作、完整 diff、历史、启停、撤回与删除在“高级设置”页里。

已有场地也可以在详情页点击“应用模板”重新选择预设。确认后，模板会整体替换草稿中的 Mode、Flags、Effects 与 Triggers，不会把上一个模板的提醒或效果带过去；Region ID、名称、来源、所有权、优先级和版本历史保持不变。重新预览并发布前，运行态不会变化。

常用入口：

- `/regions gui`：管理界面
- `/regions validate <id>`：发布前验证
- `/regions preview <id>`：查看 diff、依赖和重叠解析
- `/regions trial <id>`：仅对操作者应用草稿效果
- `/regions publish <id>`：发布 revision
- `/regions inspect <玩家>`：查看会话与租约
- `/regions cleanup <玩家>`：事故恢复
- `/regions game <id> teams <nationA> <nationB>`：锁定工会战本场的两个 Nation（报名前设置）

权限以 `plugin.yml` 为准。常规管理需要治理权限和来源所有权同时满足；`regions.superadmin` 仅用于紧急接管。

## 玩家入口

- `/regions`：打开活动大厅（只列已发布且启用的场地；不可报名的卡片直接显示原因）。
  大厅支持玩法循环筛选与“只看可报名”，可清除筛选；筛选后仍无结果会给专门的空态提示。
  具备管理资格的人在大厅里额外有“我的场地”入口，管理界面仍是 `/regions gui`。
- `/regions create` 无参数进入创建向导：选玩法 → 选地块（自动生成场地 ID）→ 设置本玩法必填项
  → 检查并发布。阶段 3 只画当前玩法需要的格子（返回／观战点、出生点、装备预设、人数、时限），
  顶部显示“必填项 N/M”；中途关闭 GUI 后再次执行 `/regions create` 会回到离开时的阶段。
- `/regions game <id> join`：报名本场（权限 `regions.game.join`，默认随 `regions.use`）。
  工会战在报名时选择 Nation；没有 Nation 会被拒绝并说明原因。
- `/regions game <id> ready|unready`：准备与取消准备，权限 `regions.game.ready`。
- `/regions game <id> leave`：退出报名，或在进行中的比赛里弃权。退出通道不设额外权限，
  失去参与权限的玩家同样能退出并拿回装备。
- `/regions game <id> spectate`：到配置的场外观战点观看比赛（权限 `regions.game.spectate`）。
- `/regions game <id> status|result`：查看本场状态与上一场结果（权限 `regions.game.view`）。
- `/regions language <zh_CN|en_US|auto>`：选择自己的显示语言，权限 `regions.language.select`。
  **仅在 `config.yml` 的 `locale-mode: player` 时生效**；默认的 `locale-mode: server` 下全服统一使用 `language` 指定的语言。
  玩家未选择时按客户端语言归一（zh* → zh_CN、en* → en_US，其余回退服务器语言）。

## 战斗玩法

三种战斗玩法共用 `org.cubexmc.regions.match` 的比赛状态机与同一份报名、准备、恢复、伤害隔离逻辑：

| 玩法 | 阵容 | 赛制与限时 | 胜负判定 |
|---|---|---|---|
| `dual_pvp` 双人决斗 | 恰好 2 人，由服务层强制（第三人报名会被拒绝，不只是模板容量） | 默认 BO1，可设 `best-of: 3`；每回合 180 秒（`round-seconds`），回合间休整 5 秒 | 对手死亡或有效弃权而自己仍存活；双方同死或回合超时为回合平局；BO3 最多 5 个实际回合，仍未两胜则整场平局 |
| `union_war` 工会战 | 两个 Nation，双方必须满额且人数相等，每队 2–10 人（默认 5） | 单局 600 秒（`timeout-seconds`） | 一方全员淘汰／弃权且另一方仍有存活者则该 Nation 获胜；双方同归于尽或超时为平局；同队永不友伤 |
| `free_for_all` 大乱斗 | 4–16 人（最低可设 2），每人独立，无队伍 | 单局 600 秒（`timeout-seconds`） | 最后存活者获胜；按淘汰顺序给出排名；同批淘汰为并列，超时仍多人生存为并列存活，不伪造唯一冠军 |

- **走入场地不等于同意参赛**：进入战斗场地只提示一次“这里可以报名”，提示带冷却
  （`modes.entry-prompt-cooldown-seconds`，默认 60 秒）；只有 `join`（命令或大厅按钮）才会进入名单。
- 工会战开报名前，场地主先用 `/regions game <id> teams <nationA> <nationB>` 锁定本场两个 Nation
  （权限 `regions.game.start` 加场地管理资格）。同时属于多个 Nation 的玩家必须在报名时显式选队；
  比赛不会退化成自由混战。
- 开赛前会一次性检查资金映射、装备托管与传送：任何一名玩家失败都会撤销整场开赛，
  不会出现部分玩家先打的情况。
- 进行中比赛主动离开有 5 秒宽限，超过即计弃权；主动退出或被踢立即计弃权。
- 成员变化后先等 200 毫秒把同一批事件收齐再判胜负，因此范围伤害同归于尽会判平局，
  而不是判给后死的一方；准备阶段有超时上限，实体任务没回来就撤销开赛并点名未回执的玩家。
- 装备托管期间禁止丢弃与拾取（避免恢复快照与场上物品分叉）；只要还有上一局的待恢复记录，
  就不能报名新的一局。结算期间会告知还有几人待恢复以及资金状态。
- 观战者既不能造成也不能承受比赛伤害；候场者、局外人与其他比赛的玩家同样被隔离。
- 结果记录 outcome、获胜方、原因与奖励状态：`/regions game <id> result` 与大厅结果卡展示同一份结果。

## 权限

以 `plugin.yml` 为准。参与类叶节点挂在 `regions.use` 之下，声明与命令里的实际检查一致
（声明了却不检查的节点会让服主按错误的前提配权限）：

| 权限 | 默认 | 说明 |
|---|---|---|
| `regions.use` | true | 参与场地的活动（`/regions game ... join\|ready\|spectate\|status`）。撤销它可挡住一组玩家参加他人活动；办赛另有来源所有权门槛，不靠这个节点。 |
| `regions.game.view` | true | 查看比赛状态与结果（`/regions game <id> status\|result`）。 |
| `regions.game.join` | true | 报名（`/regions game <id> join`）。进入区域本身不等于报名，只有该节点加命令才会把你放进名单。 |
| `regions.game.ready` | true | 准备与取消准备（`/regions game <id> ready\|unready`）。 |
| `regions.game.spectate` | true | 到观战点观看进行中的比赛（`/regions game <id> spectate`）。 |
| `regions.language.select` | true | 选择个人显示语言，仅 `locale-mode: player` 时生效。 |
| `regions.admin` | op | RuleGems 统治者管理入口，仍需来源所有权；下含 `regions.game.start` / `regions.game.end`。 |
| `regions.game.start` | false | 裁判发令（赛跑／回合模式）与锁定国家对阵（`/regions game <id> teams`），另需场地管理资格。 |
| `regions.game.end` | false | 强制结束比赛，另需场地管理资格；战斗场地还要求 `regions.region.edit`。 |
| `regions.superadmin` | op | 紧急接管与事故恢复。 |

退出比赛（`/regions game <id> leave`）不设额外权限：失去参与权限的玩家必须仍能退出并拿回装备。

## 配置

| 键 | 默认 | 说明 |
|---|---|---|
| `modes.entry-prompt-cooldown-seconds` | 60 | 走入战斗场地时“这里可以报名”提示的冷却秒数；缺失时按 60 处理。 |

其余配置见 `config.yml` 内的注释；语言策略 `locale-mode`、审计上限与发布保留数等沿用既有说明。

## 状态安全

临时效果使用持久化 lease（`effect-escrow.yml`），战斗和回合装备分别使用装备托管文件。正常退出、死亡、reload、停服和下次启动/登录都会尝试恢复。Folia 停服阶段不会提交无法保证执行的实体任务，而是保留托管数据供下次安全恢复。

Contract 奖励操作另存于 `reward-funding.yml`。一局比赛的 lock、自然胜者 settle、强制结束/reload/崩溃恢复 refund 共用同一个 transaction operation id，只有持有该锁的交易才能终结资金。无法确认的部分结算保留 lease 并要求人工复核，不会生成第二次付款。删除该文件会破坏恢复链，禁止把它当作清理手段。

比赛元数据另存于 `matches.yml`（schema `match-store-version: 1`），只保存阶段、名单、队伍／Nation 快照、结果与恢复进度，不复制物品和余额：装备内容只在装备托管文件，资金只在 `reward-funding.yml`。三者是相互独立的真相来源，任何一个文件都不保存另一份的可变副本。

装备恢复先读托管记录，在玩家所属线程写回完整快照并确认，然后落盘“已确认”标记，最后才删除该记录。
待恢复期间玩家不能报名新的一局，也不会被新的托管覆盖旧快照。重启时若恢复已经确认，只补做记录清理，不会覆盖玩家之后获得的新背包；尚未确认的恢复保留待恢复，离线玩家在登录后继续。重启或 reload 会中止未收尾的比赛，不续打半局，资金经既有 lease 退款。

战斗玩法的限时按各自规则：决斗每回合 180 秒（`round-seconds`），工会战与大乱斗 600 秒（`timeout-seconds`）；赛跑／回合模式沿用各自的 `timeout-seconds`。所有延迟任务都绑定具体局实例；旧局计时器不能结束或修改新局。上一局恢复完成前，同一区域不会启动下一局。



## 已知边界

- 尚未发布首个正式版本；当前数据格式将作为首个 release 基线，内部开发期的旧格式不在兼容范围内。
- 未知或未实现的 Capability / Condition 一律**校验失败**而不是默认放行——这是有意的安全默认。
- 校验与发布诊断已改成稳定错误码 + 双语 `errors.*`；尚未键化的还有 `ServiceResult.reason`、
  资金类 `FundingResult.detail` 与审计行里的部分英文诊断文本。
- 聊天、标题、比赛广播与 **GUI 菜单文案**都已按接收者语言渲染（`locale-mode: player` 时中文玩家与
  英文玩家各看各的）；例外是控制台输出与 `ChatInputState` 的取消关键词（后者是输入匹配，不是展示文本）。
- race / hide-and-seek / 赞助 / 多人分成的**真实资金结算**尚未实现（见 `PLAN.md` §5.2 阶段 D）。
- **战斗玩法的实服／真人验收尚未执行**：目前只有自动化验证（`:Regions:test` 192 例、`:Regions:build` 与
  `:Regions:jarGate` 通过）。真实 Lands API 行为、Purpur／真实 Folia 运行、旧数据升级演练与真人多人流程
  仍属待办，见 `PLAN.md` §10.2 与 [`REAL_PLAYER_TEST.md`](REAL_PLAYER_TEST.md)。文档不会把未做的验收写成已通过。

## 相关文档

- 本轮实施计划：[`PLAN.md`](PLAN.md)（国际化、流程简化、Nation 工会战、PVP 与单命大乱斗；
  M3–M7 的代码与自动化验证已完成，§10.2 的实服／真人验收尚未执行）
- 总体路线与历史：仓库根 [`PLAN.md`](../PLAN.md)
- [架构](docs/architecture.md)
- [兼容性](docs/compatibility.md)
- [自动回归基线](docs/regression-baseline.md)
- [发布检查单](docs/release-checklist.md)
- [真人验收清单](REAL_PLAYER_TEST.md)
