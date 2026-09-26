# Regions 候选包增量验证 — 2026-09-23

延续 [2026-09-22 候选包记录](completion-2026-09-22.md)。本次只在工作区修改 Regions；没有提交、推送、发布或接触生产服。

## 修复

- 竞速和捉迷藏在发放临时装备之前，先将比赛 ID、场地、模式和参赛名单写入 `matches.yml`。正常结束写结果并移除活动记录；意外重启时，将残留比赛写成 `ABORTED` 结果，不续打半局。装备仍由各玩法独立的 escrow 恢复；离线玩家登录后继续恢复。
- 战斗协调器跳过竞速和捉迷藏的活动记录，由对应服务恢复，避免将它们误判为战斗孤儿记录。
- 旧局延迟清理按场地核对装备 escrow，防止玩家已经加入另一场地新局时被旧任务恢复新局装备。

## 验证

- PowerShell：`.\gradlew.bat :Regions:test :Regions:build :Regions:shadowJar --rerun-tasks :Regions:jarGate` 成功。
- 380 项测试，0 failures、0 errors、0 skipped。新增回归覆盖 Race/Round 的旧局清理与重启后 `ABORTED` 结果持久化。
- jarGate：EMBEDDED，`unrelocatedKotlin=0`，`reflectImpl=0`，448 个自身 class，字节码 major 61。
- 本机隔离 Paper 1.21.11 build 132、Java 21.0.5，仅安装 Regions，监听 `127.0.0.1:25579`。最终 JAR 加载、`regions doctor`、`regions reload` 和正常停服成功；2 个旧场地的 3 条预期校验问题仍在。没有玩家客户端连接。
- 隔离服另注入两条无玩家的模拟 `RUNNING` 记录（`run_race`、`hide_and_seek`）。下一次启动分别告警恢复 1 场；写回的 `matches.yml` 已无活动比赛，两个 `results` 均为 `ABORTED`、原比赛 ID、`server-stop`。输出另存 `recovery-probe-results.yml`，避免模拟结果进入后续测试。
- 产物：`Regions/build/libs/regions-0.1.0.jar`，3,951,241 字节，非 plain JAR；SHA-256 `5B2B6CDC42D1BB579D8A3D0B8FD219E36C54F2C3F950AC2090A8BD5372FA4E6D`。

## 尚待验收

真实 Lands Nation 与保护交互、目标 Folia/Purpur、多人 BO1/BO3／工会战／大乱斗／竞速／捉迷藏、真实进程崩溃与离线玩家装备元数据恢复、Contract/Vault 资金，以及生产数据副本升级矩阵。执行步骤见 [REAL_PLAYER_TEST.md](../REAL_PLAYER_TEST.md) 与 [PLAN.md §10.2](../PLAN.md)。
