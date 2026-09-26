# Regions 真人验证清单

本文用于首个公开版本前的真人验收。测试服使用 Java 21、Paper 1.21.11，并安装与正式服一致版本的 Lands、RuleGems、可选 Contract/Vault 及其依赖。开始前备份世界、玩家数据、`plugins/Regions` 和 `plugins/Contract`。

## 已执行的加载冒烟（2026-09-13，仅作对照，不构成验收）

环境：`Regions/run`（Paper 1.21.11、Java 21.0.5），数据目录里带着**升级前遗留**的 `plugins/Regions`（旧 `templates.yml`、语言文件、`regions.yml`）。用 `:Regions:runServer` 加载本工作树构建的 `regions-0.1.0.jar`（`Regions/build/libs/`，非 plain jar）。

观察到的结果：

- `[Regions] Enabling Regions v0.1.0` → `[Regions] Regions enabled with 1 configured regions.`，启动期能力目录校验通过（校验失败会直接抛错，等价于 8 种玩法含 `free_for_all` 的注册与 descriptor 一致）。
- `Migrated templates.yml from v1 to v2 (backup: plugins\Regions\backups\migrations\...)`，遗留模板按预期迁移并留备份。
- 日志中没有 Regions 级别的 ERROR/WARN。

明确的边界：**没有玩家连接，没有报名、开赛或结算**，因此这次只证明"能在真实服务端加载、完成注册与迁移"，README 与 PLAN 都不把它当作验收结果。下面的脚本仍全部待执行。

## 已执行的实服命令验证（2026-09-13，仍不含玩家）

环境同上，但这次用 RCON 驱动一个真实服务端跑命令，并把结果作为证据。**已经把三个真问题跑出来并修掉了**：

| 观察 | 结论 / 修复 |
|---|---|
| `regions doctor` 显示 `templates: 8`，而 jar 内置 9 个模板 | `saveIfMissing` 只在文件缺失时写入，**升级安装永远拿不到后来新增的内置模板** → 新玩法在 GUI 里没有创建入口。已改为：jar 内有、服主文件里没有的模板**补进内存**（只加不改、不重写服主文件），启动时记日志。修复后实服输出 `Loaded 1 built-in template(s) ... free_for_all` |
| `regions game ffa-probe status` 输出 `labels.state.idle`；`regions validate` 显示 `ERROR Contract reward funding is unavailable (...)` 与英文诊断 | `SimpleI18nService` 只读数据目录文件、**从不回退到 jar 内同语言默认文本**，所以既有安装上任何新增语言键都解析不出来。已在模块里让磁盘值优先、jar 内文本兜底；`lang-version` 同时 7 → 8，新增 `LangV7ToV8Step` 把缺的键按同语言内置文本补进服主文件（实服补了 469 个键，带备份，服主自定义值与未知键未动）。修复后输出 `ffa-probe: 空闲` 与本地化错误文案 |
| `regions validate` 里字段名仍是 `respawn` | 字段译名有两种历史写法（`labels.field.*` 与 `labels.*`）。`issueLine` 现在先查前者再退到后者，输出"缺少返回点的坐标" |

命令结果（RCON，`Regions/run`，Paper 1.21.11）：

- `regions doctor`：`modes: free_event, dual_pvp, union_war, free_for_all, run_race, boat_race, horse_race, hide_and_seek`；`capabilities: ... mode=8 ...`；`templates: 9` —— 启动期能力目录校验通过（不一致会直接抛错）。
- `regions create ffa-probe` → `bind cuboid world 0 60 0 30 80 30` → `mode set ffa-probe free_for_all min-players=2 max-players=2 respawn=... spawn-points=world,10,64,10;world,20,64,20 kit=STONE_SWORD:1` → `validate ffa-probe` 输出"区域配置校验通过。" → `publish ffa-probe` 成功。**大乱斗在真实服务端可从零创建、通过校验并发布**。
- `regions preview ffa-probe`：`变化: 17，问题: 0，确定重叠: 1`，`最终 Mode: ffa-probe:free_for_all`。
- `regions validate`（全部场地）：老场地 `integration-probe`（早于新规则）如实报出 3 个错误——奖励服务不可用、缺少返回点、决斗需要两个出生点。这正是 §8.4 要的"旧场地生成需要完善设置"行为，而不是静默放行。
- `regions mode set war-probe union_war ...` + `validate war-probe`：两条"出生点数量不足"警告 + 一条错误"工会战需要可用的工会数据来源，当前的后备提供方无法识别工会"；`publish war-probe` 被拒绝。**缺 Lands 时工会战不会降级成个人战，也不会被发布**。
- `regions language` / `regions game <id> join` 由控制台执行 → "该命令只能由玩家执行。"（发送者门禁生效）。

**边界没有变**：仍然没有真人玩家，所以报名→准备→开赛→结算→恢复这条链路、以及真实 Lands／Folia 的差异都没有被验证。下面的脚本仍全部待执行。

## Contract 奖励托管

1. 创建并双方接受一个 WAGER，把完整合同 id 配到 `dual_pvp` 的 `reward-contract`，确认发布和开赛成功。
2. 让甲方获胜，核对双方押注只向甲方支付一次，Contract 事件包含 region funding 记录，Regions lease 已清除。
3. 新建 WAGER 后强制结束比赛，核对双方完整退款且没有胜者付款。
4. 在锁定后临时停用 Contract，结束比赛并确认 `reward-funding.yml` 保留；恢复 Contract 后 reload/restart，核对同一 operation 被重放且没有双付。
5. 在结算故障场景确认 Contract 进入 DISPUTED/人工复核，Regions 不生成新的 operation id。
6. 注入“Contract 已持久化 lock、Regions 未收到回执”：准备阶段应中止，`PREPARING` lease 保留同一 operation id；重启或 reload 后只按该 ID 退款或留待复核，不得创建第二个资金操作。另测锁确实未生效时，恢复逻辑以同一 ID 重放锁定再退款。

## 战斗玩法验收脚本（M3–M7）

以下只是**待执行的步骤与通过标准**，不是验收结果。需要至少 4 名真实玩家（工会战与 16 人容量另加人）在同一测试服上执行；每轮记录服务端时间、Region id、revision、参与者名单和 `/regions game <id> status` 输出。开始前确认已按当前版本重新发布过测试场地。

### A. 进入区域不等于报名

1. 未报名的玩家走入已发布且可报名的战斗场地，确认只收到一条“这里可以报名”的提示。
2. 在提示冷却内（默认 60 秒，`modes.entry-prompt-cooldown-seconds`）反复进出场地，确认不再提示，也不产生报名记录。
3. 执行 `/regions game <id> status`，确认自己不在名单、不能准备、装备未被托管。
4. 冷却结束后再次走入，确认只再提示一次。
5. 通过 `/regions game <id> join` 或大厅按钮报名后，确认名单、准备状态与装备托管才发生变化。

### B. 双人决斗 BO1 / BO3

1. 场地使用 `best-of: 1`：两名玩家分别 `join` 并 `ready`，确认双方准备后进入倒计时与开赛。
2. 让一方击败另一方，核对结果页与 `/regions game <id> result`：获胜方、原因（对手淘汰）、装备恢复状态与奖励状态一致。
3. 把 `best-of` 改为 `3` 后重新发布并重赛，核对：回合间 5 秒休整、比分逐回合累计、先赢两回合结束、实际回合数不超过 5。
4. 构造双方同归于尽（范围伤害）与单回合 180 秒超时，核对该回合判平局且不产生胜者。
5. 打完 5 个回合仍未有人两胜时，核对整场平局。
6. 第三名玩家尝试 `join`，确认被拒绝并给出原因。

### C. 工会战：两个 Nation

1. 准备两个不同 Nation 的玩家（至少 2 对 2；默认 5 对 5 另跑一轮），每个 Nation 至少 2 个 Land，覆盖“同一 Nation 的不同 Land 成员”。
2. 场地主执行 `/regions game <id> teams <nationA> <nationB>`，核对未锁定时无法报名、锁定后只能加入这两个 Nation。
3. 让一名同时属于多个 Nation 的玩家 `join`，确认必须显式选队；让没有 Nation 的玩家 `join`，确认被拒绝并说明原因。
4. 双方满额且全部准备后才开赛；开赛后核对同队互相攻击无效。
5. 一方全员淘汰后核对获胜 Nation、结果展示，以及启用 Contract 时的收款签署人。
6. 赛后让获胜方成员换国／改名，核对已经发生的结算不改变收款人；再在锁定对阵后换国重跑一轮，核对不会按新 Nation 付款。

### D. 大乱斗：4 人

1. 4 名玩家 `join` 并 `ready`，核对每人分配到不同出生点并领取统一装备。
2. 依次淘汰 3 人，核对最后存活者获胜、淘汰顺序排名与结果展示。
3. 被淘汰玩家分别用 `/regions game <id> spectate` 和 `/regions game <id> leave` 走通观战与退出，退出后装备已恢复。
4. 构造同一批全灭（范围伤害）与超时仍多人生存，核对并列结算、不伪造唯一冠军。
5. 让被淘汰玩家死亡后重生、断线重连、再走回场地，确认不能重新参战。

### E. 装备恢复：死亡 / 断线 / 重启

1. 赛前记录背包、护甲、副手、经验与受控状态；分别在三场比赛中制造：比赛中死亡、比赛中断线、开赛后重启服务器。
2. 每种场景核对装备与状态按快照恢复一次，不吞装备也不复制装备；离线玩家在重新登录后完成恢复。
3. 重启场景核对未收尾比赛被中止、没有续打半局；启用 Contract 时核对走既有退款。
4. 核对 `matches.yml` 里已确认的恢复记录被清理，且没有覆盖玩家恢复后新获得的物品。

### F. 观战隔离

1. 未参赛玩家执行 `/regions game <id> spectate`（或大厅观战按钮），确认被送到配置的观战点。
2. 核对四个方向都无伤害：观战者攻击参赛者、观战者攻击其他观战者、参赛者攻击观战者、范围伤害波及观战者。
3. 核对观战者不能参与比赛的胜负判定，也不能被计入存活名单。
4. 观战者退出后核对自身状态未受比赛托管影响。

## 1. 测试角色

- `ruler_owner`：拥有 `regions.admin`，同时是目标 Lands 区域主人。
- `ruler_not_owner`：拥有 `regions.admin`，但不是目标区域主人。
- `owner_not_ruler`：是目标区域主人，但没有 `regions.admin`。
- `ordinary_player`：普通参与者。
- `superadmin`：拥有 `regions.superadmin`，用于事故恢复，不参与日常场地管理。

准入标准：只有 `ruler_owner` 能日常创建、编辑、试运行和发布自己的 Region；另外两种半授权身份都必须被拒绝；`superadmin` 的接管操作必须留下审计记录。

## 2. 创建与发布

1. `ruler_owner` 使用 `/regions gui` 从自己的 Lands area 创建场地。
2. 应用一个内置模板，修改当前 Mode 的专用设置、一个 Flag 和一个 Effect。
3. 在详情页点击“应用模板”，先选“小人国”并确认，再重新选择“躲猫猫”；确认覆盖前线上配置不变，保存草稿后预览中不再出现小人国的 scale Effect 和进入提醒。
4. 打开发布预览，确认能看到 revision diff、依赖、警告/错误、最终 Mode、主 Trigger Region、最终 Flag 和 Effect。
5. 故意填入无效物品、人数或位置，确认发布被阻止且错误能指出修改方向。
6. 修正后发布；普通玩家进入区域时只能运行已发布 revision，进入躲猫猫场地后没有小人国缩放或提醒残留。
7. 再修改草稿，确认未重新发布前不会影响正在运行的场地。
8. 撤回、再次发布、查看历史并回滚；确认回滚生成新 revision，而不是覆盖旧历史。

通过标准：整个流程无需直接编辑 YAML；重启后草稿、已发布 revision 和历史保持一致。

## 3. 权限与所有权

分别用三个非超级管理员角色尝试打开 GUI、编辑、试运行、发布和开赛：

- `ruler_owner`：允许。
- `ruler_not_owner`：拒绝，提示不是当前来源主人。
- `owner_not_ruler`：拒绝，提示只有 RuleGems 统治者可管理。

随后转让 Lands 所有权：

1. 旧主人再次编辑或开赛时必须立即失去权限。
2. Region 应冻结，不删除历史。
3. 新主人不能直接继承旧主人的未发布草稿。
4. `superadmin` 复核、解冻或处理后，审计中可看到操作者、原因和 Region。

## 4. 隔离试运行

1. `ruler_owner` 对草稿执行 `/regions trial <id>`。
2. 只有本人受到草稿 Flag 与 Effect 影响；附近其他玩家不受影响。
3. Mode 不应开赛，Trigger 不应向其他玩家或控制台执行。
4. 执行 `/regions trial <id> stop` 后立即恢复。
5. 分别在试运行中测试死亡、断线、reload 和插件关闭。

通过标准：所有退出路径均无 scale、速度、飞行、药水或其他临时状态残留；效果应用失败时整次试运行回滚。

## 5. 玩家状态恢复

测试前记录玩家原始飞行状态、速度、scale、药水效果和装备：

- 正常进入/离开。
- 区域内死亡与重生。
- 区域内断线并重连。
- `/regions reload`。
- Lands reload/短暂不可用。
- 正常停服并重启。
- 可控测试环境中的异常进程终止与恢复。

通过标准：Regions 只恢复自己持有 lease 的状态，不覆盖其他插件或玩家原本合法状态；`/regions inspect <玩家>` 与 `/regions cleanup <玩家>` 可用于诊断和恢复。

## 6. 重叠区域

创建两个有交集的 Cuboid 或可提供 geometry 的 Source 区域：

1. 设置不同 priority 和冲突 Flag，确认预览与运行时选择同一来源。
2. 测试 `HIGHEST_PRIORITY`、`EXCLUSIVE`、`STACK`、`MERGE_BY_TYPE` Effect。
3. 测试 `PRIMARY_REGION` Trigger 只由主 Region 执行。
4. 尝试发布两个重叠的有状态 Mode，确认发布被阻止。
5. 离开其中一个重叠区域，确认剩余效果重新解析且原始状态最终可恢复。

## 7. 多人 Mode

至少完成以下各一轮：

- `dual_pvp`：报名/确认、装备托管、开始、死亡/退出、结束、恢复装备。
- `union_war`：同工会/敌对工会判断、最低人数、强制结束。
- `run_race` 或载具赛：起点、检查点、终点、名次、超时。
- `hide_and_seek`：角色分配、躲藏计时、找到玩家、回合结束。

通过标准：Mode 的开始/结束、参与者、结果、revision 和强制操作均进入审计；中途退出或管理员终止不会复制或吞掉装备。

## 8. Paper 与 Folia

先在 Paper 完成全部流程，再在真实 Folia 重复以下关键项：

- GUI 创建、预览、发布。
- 玩家进入/离开与死亡/断线清理。
- 隔离试运行。
- 至少一个战斗 Mode 和一个计时 Mode。
- reload、正常停服和重启。

任何线程违规、Region scheduler 异常、未清理 lease、装备恢复失败或 Regions 栈追踪都视为阻断问题。

## 9. 记录方式

每个失败至少记录：

- 复现角色、Region id、Source 和 revision。
- 操作步骤、预期结果、实际结果。
- 服务端时间和相关日志。
- `/regions inspect <玩家>`、`/regions validate <id>`、`/regions preview <id>` 的输出。
- 是否可通过 `/regions cleanup <玩家>` 或 superadmin 操作恢复。

只有所有阻断问题修复并回归后，才进入首个公开版本打包。
