# Changelog

## Unreleased

### R1 奖励托管恢复（2026-09-23）

- Contract 锁定回执丢失或返回不确定结果时保留 `PREPARING` lease 和原 operation id；撤销开赛／重启先尝试退款，只有确认无锁才以同 ID 重放锁定再退款。首次 lease 落盘失败时，在调用 Contract 前撤销内存记录。未解决的 lease 保留供复核。
- 385 项 Regions 测试、重新打包与 JAR 门禁通过；隔离 Paper 联合 Contract/Vault/EssentialsX 加载、重载与关闭通过。无真实 WAGER 余额及 Folia 验收，见 [R1 记录](docs/r1-recovery-2026-09-23.md)。

### 比赛记录与恢复加固（2026-09-23）

- 竞速和捉迷藏在发装备前写入进行中比赛记录；意外重启后把未完成比赛记为中止，保留结果，并由现有装备托管流程逐人恢复。战斗恢复不再误处理这两类记录。
- 旧局延迟执行的装备清理只处理所属场地，不能把玩家加入另一场地新局后的装备托管提前取走。
- 380 项自动化测试、强制打包、JAR 门禁和隔离 Paper 启动／重载／停服通过。范围与剩余实服验收见 [验证记录](docs/completion-2026-09-23.md)。

### 生命周期与候选包加固（2026-09-22）

- 竞速与捉迷藏等待全员实体准备完成才开始计时，缺失回执在 10 秒后中止；竞速和战斗等待异步传送成功，失败不进入比赛。
- 装备恢复延后到重生后，先写回并保存玩家数据，再确认和移除 escrow；保存失败保留记录。战斗恢复确认落盘失败时撤销内存确认标记。
- 七种比赛共用跨场报名／观战占位；有任何玩法装备待恢复时不得加入新局。运行中退出者的历史报名记录保留到本局收尾。
- 临时装备期间拦截外部容器打开、点击和拖拽，以及方块放置、盔甲架和物品展示框装备转移；自身背包和 Regions 菜单继续可用。
- 淘汰后的竞速选手不能触发检查点或终点；竞速／捉迷藏固定本局场地定义。单命战斗出局后重生到返回点。
- 修复校验命令显示 `respawn` 原始字段名的问题；语言 v10→v11 只补新键，保留自定义翻译。
- 376 项自动化通过。隔离 Paper 验证及发布边界见 [本轮证据](docs/completion-2026-09-22.md)。

本轮战斗玩法（`PLAN.md` M3–M7）的条目单独分组如下；`PLAN.md` §10.2 的实服／真人验收尚未执行。

### 玩法补齐（2026-09-19）

把另外七种玩法提到与双人决斗同级。此前八种玩法里只有战斗三兄弟有完整的报名册、
装备托管、成员隔离与结果记录；竞速与捉迷藏缺其中一部分或全部，`free_event` 则从未说清
自己不是比赛。**`:Regions:test` 365 例全绿，`:Regions:build` 与 `:Regions:jarGate` 通过；
实服与真人验收仍未执行。**

#### Fixed

- **竞速的装备键是一句空话**：`kit` / `armor` / `offhand` / `replace-gear` 能通过校验、能发布，
  而 `RaceModeService` 从头到尾不读它们——场地主以为配好了装备，开赛后什么都没发生。
  这正是 README 承诺的 "fail closed" 被参数模型本身破坏：八种玩法共用一份 40 多键的参数袋
  且 `strict = false`，于是决斗能设 `seeker-ratio`、工会战能设 `checkpoint-vehicles`。
  现在每种玩法只声明自己真的读取的键（`ModeParameterSchema`）且一律严格校验，
  竞速与捉迷藏也真的会发装备。
- **捉迷藏的恢复顺序会吞装备**：`restoreStored()` 先 `gearStore.take()` 删记录、再写背包，
  写背包一失败（异常、玩家正好掉线、Folia 上实体任务没跑到）装备就永久消失。
  战斗层在 M0 修过这个顺序，捉迷藏没跟上。现在两者共用 `ModeGearEscrow`：
  写回 → 落盘确认 → 删记录；宕机停在两步之间时重启只补清理，不覆盖玩家的新背包。
- **竹筏在划船赛里永远过不了终点**：`BAMBOO_RAFT` / `BAMBOO_CHEST_RAFT` 的类型名里没有
  `boat`，旧的包含匹配把它们判成"不在船上"。由新增的 `RaceCourseTest` 抓出。
- **走进竞速／捉迷藏场地就被拉进比赛**：`onEnter` 直接写名单，路过的人会被卷进一局、
  装备可能被换掉，而他从没说过要参加。战斗层在 M3 改成"只有 join 才入队"，这两类没跟上。
- **中途退赛与死亡的选手从结果里消失**：他们被直接移出名单，`result` 里查不到参加过这一局的人。
  现在保留记录并标记 `LEFT` / `ELIMINATED`，胜负判定改看"还在场上的人"。
- `/regions mode set` 的用法提示仍然只列 7 种玩法，第 8 种 `free_for_all` 发布了但没进帮助文本；
  现在直接由玩法清单生成，不会再漂移。
- 托管期间禁止丢弃与拾取、比赛中的指令封锁此前**只认战斗层的 escrow**；竞速与捉迷藏的
  托管装备可以被丢在地上，选手也能 `/spawn` 逃走。现在三类玩法一视同仁。

#### Added

- `ModeParameterSchema`：每种玩法自己的参数表，严格校验；玩法注册、能力目录与参数表
  共用同一份清单，不会再出现"注册了但没 descriptor"。
- `ModeRoster`：竞速与捉迷藏的显式报名册，阶段沿用 `MatchPhase`，文案共用 `labels.state.*`。
- `ModeGearEscrow` + `ModeKit`：崩溃安全的托管协议与唯一一份装备发放实现；
  `armor` / `offhand` 现在在捉迷藏与竞速里也真的生效。
  `CombatGearStore` 增加**可选**的 `confirmed` 落盘标记（旧文件读出来是 `false`，
  最坏是把同一份快照幂等地再写一次，而不是吞装备）。
- `ModeDamagePolicy`：竞速与捉迷藏的成员隔离，覆盖伤害与药水／滞留云；
  搜寻者抓躲藏者由 `RoundModeService` 先行接管，不走这条判定。
- `RaceCourse`：赛道纯逻辑（载具别名、逐点约束、三个同义超时键、半径回退），可脱离服务端穷举测试。
- 竞速与捉迷藏补上 `join` / `leave` / `unready` / `spectate`，命令与大厅按钮共用同一套流程；
  大厅主按钮对七种玩法自动在报名／准备／取消准备／退出／观战之间切换。
- 竞速与捉迷藏产生结构化 `MatchResult`：竞速写完赛顺序（`MatchResult.standings`，第 1 名在前，
  与淘汰顺序刻意分开——两者方向相反），捉迷藏写胜方与原因。
- 语言 `lang-version: 10` 与 `LangV9ToV10Step`：新增 `game.race.removed`、
  `game.match.start.not-supported` 与五条结束原因（`all-finished` / `no-finisher` /
  `all-found` / `seekers-gone` / `hiders-survived`），中英齐备。
- 新增测试：`ModeParameterSchemaTest`（每种玩法只接受自己读的键）、`RaceCourseTest`、
  `ModeDamagePolicyTest`、`ModeGearEscrowTest`（写回失败保留记录、已确认只清理）、
  `ModeParityTest`（走进区域不入队、装备被托管并归还、结果与名次、退赛仍在册）。
  共享装置 `ModeServiceHarness`。

#### Changed

> **行为变化，升级前请知悉。**

- **竞速与捉迷藏期间不再放行玩家造成的伤害**。竞速由谁先到终点决定，捉迷藏由抓到与否决定，
  两者都不靠打架；留一条 PVP 通道只会让一个带弓的人决定整场比赛。
  此前这两类玩法完全没有隔离，胜负由场地 `pvp` Flag 间接决定。
  生物与环境伤害不受影响，环境死亡照常出局。
- **走进竞速／捉迷藏场地不再自动报名**，必须 `join`。已有的自动化脚本若依赖"进区即参赛"需要改。
- 比赛结果 store 由插件统一持有（`matches.yml`），战斗、竞速与捉迷藏写同一份；
  `/regions game <id> result` 不再按玩法分派。
- 竞速新增 `race-escrow.yml`（捉迷藏的 `round-escrow.yml` 保持不变）。
- 接管装备的竞速／捉迷藏场地**必须**配返回点：没有它，被淘汰的选手会留在赛道里，恢复也无处可去。
  既有场地会在发布校验里如实报错，而不是静默放行。
- 战斗三兄弟执行 `/regions game <id> start` 时改为说明"本玩法在全员准备后自动开赛"，
  不再回一条不相干的用法提示。

### Fixed（实服验证发现，2026-09-13）

- **既有安装解析不出新增的语言键**：`cubex-i18n` 只读数据目录里的语言文件，从不回退到 jar 内同语言默认文本，所以插件升级后新增的 `labels.*`／`errors.*` 在真机上会原样显示成键名或退化成英文诊断。现在磁盘值优先、jar 内文本兜底（`SimpleI18nService`）。
- **语言文件缺少 7→8 迁移**：`lang-version` 长期停在 7，而后来的国际化工作新增了大量键，版本号相同于是迁移框架认为无需处理。版本升到 8 并新增 `LangV7ToV8Step`：把缺的叶子键按同语言内置文本补进服主文件，保留其自定义值与未知键，带备份、幂等。
- **升级安装拿不到新增的内置模板**：`saveIfMissing` 只在文件缺失时写入，导致新玩法（如 `free_for_all`）在 GUI 里没有创建入口。现在 jar 内有、服主文件里没有的模板会在内存里补齐并记日志，服主文件不被重写。
- **向导阶段 1 选了玩法却不约束模板**：可以"选工会战、套竞速模板"。模板列表现在按所选玩法过滤；该玩法没有模板时按玩法默认值直接建草稿进入阶段 3，不再把人卡在空列表上。
- 校验错误里的字段名会露出内部标识（如 `respawn`）：`issueLine` 现在先查 `labels.field.*` 再退到 `labels.*`。

### Changed（页面重整，2026-09-13）

- 场地详情页拆成**基础页**（玩法设置、场地点位、检查并发布、比赛管理、隔离试运行共 5 个操作）与**高级设置页**（规则组合、临时效果、触发动作、应用模板、完整 diff、历史版本、启停、清理、撤回、删除）。校验结果写进"检查并发布"的按钮说明，不再单设校验按钮；"比赛管理"在聊天里给出状态、名单、上一场结果与开停赛命令。
- 槽位与标签抽成 `RegionDetailLayout`，新增 `RegionDetailLayoutTest` 把"基础页恰好 5 个操作 / 两页槽位不重叠 / 标签中英双语齐备"变成会失败的门禁。
- 规则／效果／触发页从发布页跳进来时，返回键回发布页（补完 M2.3 的可用性遗留）。

### Added（流程与正确性补完，2026-09-13）

- **GUI 文案按查看者语言渲染**：`GuiText`/`GuiItems` 的取文案方法全部要求传 viewer（无 viewer 的重载已删除，编译器保证没有漏网点），共转换 287 处调用；`LanguageManager` 增加 `componentFor/messageListFor/labelFor/severityLabelFor/issueLineFor/resultReasonFor`，`gameStatusLine` 与模板显示名也补了 viewer 版本。`locale-mode: player` 下中文玩家与英文玩家各看各的菜单；`locale-mode: server`（默认）行为不变。新增 `GuiTextLocaleTest`。
- `/regions game <id> status` 的状态行按执行者语言渲染；向导自动生成的场地**名称**刻意保持服务器语言（它是落盘数据，不该随创建者客户端语言漂移）。
- `/regions game <id> end|stop` 改为两步确认：先说明会结束哪一场、影响多少参赛者与版本号，30 秒内再次执行才真的结束（命令语法与权限不变）。

- 活动大厅筛选：玩法循环筛选（全部 → 出现的每个玩法 → 全部）、"只看可报名"开关与"清除筛选"，切换后回到第 1 页；筛选后无结果给专门空态。纯逻辑 `LobbyFilter` / `ActivityLobbyLogic.applyFilter`。
- 创建向导阶段 3（`View.WIZARD_SETTINGS`）：只展示当前玩法真正需要的必填项（返回／观战点、出生点、工会战乙方出生点、装备预设、人数、时限），顶部如实显示"必填项 N/M"与缺项，缺项时不放行到发布页；支持返回上一阶段与聊天改名。
- 比赛结算观察窗口：成员变化后等 200 毫秒收齐同一批事件再判定胜负，范围伤害同归于尽判平局而不是判给后死的一方。
- 开赛准备屏障超时：屏障自身有上限，实体任务没回来时点名未回执玩家并撤销开赛。
- FINISHING 阶段向参与者显示尚待恢复人数与资金状态，每恢复一人刷新一次。
- 临时装备保护：装备托管期间禁止丢弃与拾取，避免恢复快照与场上物品分叉。

### Fixed（2026-09-13）

- `RegionOverviewMenu` 的撤回失败／试运行失败／删除失败把英文诊断原样显示给玩家，现改走 `LanguageManager.resultReason`。
- 恢复未完成时只拦截同一场地的新局，玩家仍可在别处报名；现在只要还有待恢复记录就拒绝报名并说明原因。
- 决斗在"回合用尽仍无人两胜"时一律报"回合用尽"，把同归于尽／超时说成"打满了回合数"；现在按最后一回合的实际原因收尾。

### Added（M3–M7 战斗玩法）

- `org.cubexmc.regions.match` 比赛层：`MatchModels`（`MatchPhase` `WAITING/PREPARING/COUNTDOWN/RUNNING/INTERMISSION/FINISHING/CLOSED`、`ParticipantState`、`MatchOutcome`、`RewardState`、`MatchParticipant`、`TeamSnapshot`、`MatchResult`、`MatchSnapshot`）、`MatchRules`（`MatchSettings`/`MatchView`/`MatchVerdict` 与 `DuelRules`/`NationBattleRules`/`LastPlayerStandingRules`/`MatchRulesCatalog`）、`CombatMatchCoordinator`、`CombatDamagePolicy`、`MatchSpawns`、`MatchStore`、`GearSnapshot`。
- `MatchStore` 以 `matches.yml`（schema `match-store-version: 1`）保存比赛阶段、名单、队伍／Nation 快照、结果与恢复进度，只在关键转移落盘，不复制物品与余额。
- 报名与观战入口：`/regions game <id> join|leave|ready|unready|spectate|status|result|teams` 及大厅报名／观战按钮；`plugin.yml` 的 `regions.game.join`／`regions.game.spectate` 声明与实际检查一致。
- 走入战斗场地的一次性报名提示及冷却 `modes.entry-prompt-cooldown-seconds`（默认 60 秒）；进入区域不再等于同意参赛。
- 决斗：服务层强制恰好 2 人、默认 BO1（`best-of: 1|3`）、每回合 180 秒、回合间 5 秒、BO3 先赢两回合且最多 5 个实际回合、双方同死或回合超时为回合平局、回合耗尽仍未两胜为整场平局。
- 工会战：`UnionProvider` 增加 `getUnions`（完整候选）、`unavailableReason()`、`allUnions()`；`LandsUnionProvider` 枚举玩家全部 Land、取非空 Nation、按 ULID 去重，并区分“Lands 缺失／API 不可用”与“玩家没有 Nation”；每队 2–10 人（默认 5）、双方满额等额、单局 600 秒、同队永不友伤。
- 大乱斗 `free_for_all` 全链路注册：模式 registry、capability descriptor 与参数、校验器、模板、双语文案、GUI 玩法页与向导卡片、命令补全；每人一条命、最后存活者获胜、按淘汰顺序排名、同批淘汰与超时存活并列、`reward-source: contract` 被校验拒绝、4–16 人（最低可设 2）、每个可能参赛者一个不重复出生点（间距不足 6 格仅告警）。
- 观战：`/regions game <id> spectate` 与大厅按钮；观战者既不造成也不承受比赛伤害。
- 结果展示：结果记录 outcome、获胜方、原因与奖励状态，`/regions game <id> result` 与大厅结果卡展示同一份结果。
- 自动化：新增 `CombatDamagePolicyTest`、`MatchRulesTest`、`CombatMatchCoordinatorTest`；`:Regions:test` 192 例、`:Regions:build` 与 `:Regions:jarGate` 通过。

### Changed（M3–M7 战斗玩法）

- `CombatModeService` 改为薄 facade，全部调用委托 `CombatMatchCoordinator`；原公开 API（`onEnter`/`onLeave`/`ready`/`forceEnd`/`onDeath`/`onRespawn`/`cleanupAll`/`restoreIfPending`/`status`/`isEnding`/`isCombatMode`）行为不变，新增 `join`/`leave`/`unready`/`spectate`/`selectTeams`/`participants`/`result`/`membership`/`damageDecision`/`recoverPersisted`/`tick`/`onDisconnect`。
- 开赛屏障：资金预留与 Nation↔合同方映射 → 逐人持久化装备 escrow → 在玩家自己的线程发装备并传送 → 收齐回执 → COUNTDOWN；任一玩家失败撤销整场开赛并恢复已取走的状态。
- 恢复协议：读 escrow → 在玩家线程写回完整快照 → 落盘“已确认”标记 → 删除记录；重启时已确认的恢复只清理记录，不覆盖新背包；未确认的恢复与离线玩家保留待恢复，登录后继续。
- 重启／reload 中止未收尾比赛，不自动续打半局；中断资金走既有 lease 退款。
- 伤害隔离由 `CombatDamagePolicy` 判定：同一场比赛、同一 RUNNING 阶段、双方存活且分属对立面才放行 PVP；候场者、观战者、局外人与其他比赛双向拒绝；归属覆盖近战、投射物、药水与滞留云雾、宠物与玩家引燃的 TNT；玩家来源但无法归属的伤害按拒绝处理；生物与环境伤害不受影响，环境死亡仍淘汰。
- 进行中比赛主动离开有 5 秒宽限，超过计弃权；主动退出或被踢立即计弃权。
- 资金结算改用带证据的重载（`FundingSettlement`）：开赛前锁定的 `Nation ID → 合同方` 映射随 lease 持久化，赛后换国、改名或换届都不改变收款人。
- 计时：倒计时任务、回合休整、1 秒 tick（回合超时、5 秒离开宽限、ActionBar）与 watchdog 兜底调用。

### Fixed（M3–M7 战斗玩法）

- 装备恢复改为“先写回玩家背包并确认、再删除 escrow”；重启时对已确认的恢复只做记录清理，不再有覆盖玩家新背包的窗口。
- 重复死亡、退出与重生回调只处理一次；延迟任务绑定具体局实例，旧局计时器不能结束或修改新局。
- 进入区域不再隐式报名：此前走入战斗场地即入队，现在只有 `join` 才写入名单。
- `regions.game.join`／`regions.game.spectate` 曾在 M2.5 被移除（声明了却没人检查的权限是假承诺）；本次随报名／观战实现一起加回 `plugin.yml`，并由 `GamePermissionsTest` 断言声明集合与代码检查集合一致。

### Added

- `on_interact` 与 `on_timer` 的运行时触发点；`on_timer` 有可配置间隔与 5 秒下限。
- TRIGGER 纳入 Capability Catalog，启动时校验枚举与 descriptor 一致。
- `RegionSource.containing` 批量位置解析，Lands 每次检测只做一次反射解析。
- GUI、命令与 Mode 运行时文案的完整 zh_CN / en_US 语言键（`lang-version: 6`）。
- 语言文件回归测试：键集一致、基线版本一致、en_US 无源语言残留、全部值为合法 MiniMessage 且无遗留 `&` 颜色码。
- `/regions reload` 失败时报出失败阶段（`reload-failed`），不再一律回报成功。
- 可选 Contract WAGER 奖励：`dual_pvp` / `union_war` 发布前校验、开赛锁定、唯一胜者结算和中断退款。
- `reward-funding.yml` 持久化 operation lease；启动/reload 重放相同操作，避免跨插件重试造成重复付款。

### Fixed

- Paper/Purpur 在根命令补全时传入空参数数组会令 `/regions` 读取 `args[0]` 并抛出 `ArrayIndexOutOfBoundsException`；现改用共享的安全根补全门禁，并覆盖普通玩家与管理补全。
- `/regions reload` 会丢弃只存在于内存中的草稿：`regions.yml` 仅在关服或显式 flush 时落盘，而 reload 直接 `load()` 覆盖内存。现在 reload 走 `ReloadChain`，数据阶段以 flush 成功为前提。

### Changed

- 语言文件改用 MiniMessage 与 `<name>` 占位符（`lang-version` 5 → 6）；帮助文本里 `/regions rollback <id>` 这类用法尖括号已转义，不会被当成占位符。按既有预发布策略，`RegionBaseline` 拒绝旧文件而非迁移，请重新生成。
- 语言读取改为共享 `I18nService`：缺失键沿 locale 链回退到 `zh_CN`，不再渲染成键名本身。删除本地的 `PaperText`。
- 存储与审计实现 `Reloadable` + `Terminable`，由 `bind(store)` 负责关服落盘。
- 日志改走 `CubexLogger`（`log()`）；Kotlin 源码从 `src/main/java` 迁到 `src/main/kotlin`。

- `RegionsGui` 拆分为协调器加五个菜单类及共享的文案/取值/物品辅助，单文件从 1436 行降到 257 行以内。
- Effect escrow 从每次应用/恢复整文件重写改为按批写入；进出区域的一组 Effect 只写一次。
- `RegionDetectionService` 按来源分组、缓存已发布集合，并按 `RegionStorage.publishedRevision()` 失效。

- 持久化 Effect lease 托管与启动/登录恢复。
- `glowing` 效果能力及捉迷藏角色视觉的租约化管理。
- 赛跑 `timeout-seconds` 自动结束和 GUI/模板配置。
- 模式旧任务隔离、结束恢复闸门及对应自动化回归。
- 深层 Action/Effect、物品、药水、声音、位置和赛跑超时验证。
- Regions 独立 CI 构建、产物上传及标签发布支持。

### Fixed

- `fly: deny` 现在对飞行中进入区域的玩家同样生效：合成 `allow_flight` Effect 托管并在离开时恢复，不再只拦截区域内的起飞按键。
- 移除永不触发的 `on_score`；未知 trigger 键在加载时明确警告，而不是静默丢弃。
- Paper 停服清理不再排入必然被取消的下一 tick。
- Folia 停服保留未安全恢复的效果/装备托管，供下次启动恢复。
- 玩家离开后排队的战斗/回合启动任务不再修改玩家状态。
- 旧回合/比赛计时器不再影响同 id 的新一局。
- Effect 应用失败后不再缓存成功签名，后续刷新可自动重试。
- Lands 已安装但禁用时不再错误报告 Source 可用。
- 装备托管写入或删除失败时回滚内存状态，避免内存与磁盘分叉。
