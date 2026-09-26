package org.cubexmc.regions.mode

import org.bukkit.entity.Player
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.PlayerRespawnEvent
import org.cubexmc.regions.RegionsPlugin
import org.cubexmc.regions.match.CombatMatchCoordinator
import org.cubexmc.regions.match.JoinResult
import org.cubexmc.regions.match.MatchParticipant
import org.cubexmc.regions.match.MatchResult
import org.cubexmc.regions.match.MatchStore
import org.cubexmc.regions.match.SpectateResult
import org.cubexmc.regions.model.RegionDefinition
import java.util.UUID

/**
 * 双人决斗 / 工会战 / 大乱斗的对外入口。
 *
 * 这里只做门面：真正的状态机、装备托管与恢复协议在
 * [CombatMatchCoordinator]（PLAN.md §6.1「保留 CombatModeService 作为现有调用方的入口，
 * 逐步委托 Regions 内部的 CombatMatchCoordinator」）。调用方（会话、命令、GUI、监听器）
 * 不需要知道比赛模型；需要比赛细节时用 [participants]、[result]、[membership]。
 */
class CombatModeService(private val plugin: RegionsPlugin) {
    private val gearStore = CombatGearStore(plugin)

    /** 结果与比赛快照的落盘由插件统一持有（八种玩法共用一份），这里只借用。 */
    private val matchStore: MatchStore = plugin.matchStore()
    private val coordinator = CombatMatchCoordinator(plugin, gearStore, matchStore)

    init {
        gearStore.load()
    }

    // ------------------------------------------------------------ 会话入口

    fun onEnter(player: Player, region: RegionDefinition): Boolean = coordinator.onEnter(player, region)

    fun onLeave(player: Player, regionId: String, reason: String) = coordinator.onLeave(player, regionId, reason)

    /** 断线/踢出：正式战斗中立即弃权，候场阶段退报名。 */
    fun onDisconnect(player: Player, reason: String) = coordinator.onDisconnect(player, reason)

    fun ready(player: Player, regionId: String): Boolean = coordinator.ready(player, regionId)

    fun forceEnd(regionId: String, reason: String): Boolean = coordinator.forceEnd(regionId, reason)

    fun onDeath(event: PlayerDeathEvent): Boolean = coordinator.onDeath(event)

    fun onRespawn(event: PlayerRespawnEvent) = coordinator.onRespawn(event)

    fun cleanupAll(reason: String, shuttingDown: Boolean = false) = coordinator.cleanupAll(reason, shuttingDown)

    fun restoreIfPending(player: Player, reason: String): Boolean = coordinator.restoreIfPending(player, reason)

    fun status(regionId: String): GameStatus = coordinator.status(regionId)

    fun isEnding(regionId: String): Boolean = coordinator.isEnding(regionId)

    /** 比赛进行中（开赛屏障→回合间隔）的选手；指令封锁据此判定。 */
    fun isPlayingLiveMatch(playerId: java.util.UUID): Boolean = coordinator.isPlayingLiveMatch(playerId)

    /** 玩家正在参与（或等待恢复）的比赛所在场地；命令省略 `<id>` 时优先用它。 */
    fun activeRegionId(playerId: java.util.UUID): String? = coordinator.activeRegionId(playerId)

    fun isCombatMode(region: RegionDefinition): Boolean = coordinator.isCombatMode(region)

    /** 装备是否仍在托管中（比赛中或等待恢复）；托管期间不得丢弃/拾取临时装备。 */
    fun isGearEscrowed(playerId: UUID): Boolean = coordinator.isGearEscrowed(playerId)

    // ------------------------------------------------------------ 报名与观战

    fun join(player: Player, regionId: String, teamId: String? = null): JoinResult =
        coordinator.join(player, regionId, teamId)

    fun spectate(player: Player, regionId: String): SpectateResult = coordinator.spectate(player, regionId)

    /** 取消准备；返回 false 表示当前不在可准备的等待阶段。 */
    fun unready(player: Player, regionId: String): Boolean = coordinator.unready(player, regionId)

    /** 明确退出/弃权；返回 false 表示这名玩家本来就不在这场比赛里。 */
    fun leave(player: Player, regionId: String): Boolean = coordinator.leave(player, regionId)

    /** 场地主选定工会战本场双方；失败返回 false（不是 Nation 战、已开局或已有报名者）。 */
    fun selectTeams(regionId: String, nationA: String, nationB: String, nameA: String? = null, nameB: String? = null): Boolean =
        coordinator.selectTeams(regionId, nationA, nationB, nameA, nameB)

    fun selectedTeams(regionId: String): Pair<String?, String?> = coordinator.selectedTeams(regionId)

    /** GUI 对阵页：一次设一个队伍，两边都可为 null（= 取消该队）。 */
    fun setTeams(regionId: String, nationA: String?, nationB: String?, nameA: String? = null, nameB: String? = null): Boolean =
        coordinator.setTeams(regionId, nationA, nationB, nameA, nameB)

    fun clearTeams(regionId: String): Boolean = coordinator.setTeams(regionId, null, null, null, null)

    // ------------------------------------------------------------ 查询

    fun membership(playerId: UUID) = coordinator.membership(playerId)

    /** 伤害隔离判定；[playerSourced] 为 false 时表示生物/环境伤害，交回原有规则。 */
    fun damageDecision(attackerId: UUID?, victimId: UUID, playerSourced: Boolean) =
        coordinator.damageDecision(attackerId, victimId, playerSourced)

    /** 药水/范围效果的隔离判定；与 [damageDecision] 同一份成员关系。 */
    fun effectDecision(throwerId: UUID?, targetId: UUID, kind: org.cubexmc.regions.match.MatchEffectPolicy.EffectKind) =
        coordinator.effectDecision(throwerId, targetId, kind)

    fun participants(regionId: String): List<MatchParticipant> = coordinator.participants(regionId)

    fun result(regionId: String): MatchResult? = coordinator.result(regionId)

    fun spectatorIds(regionId: String): Set<UUID> = coordinator.spectatorIds(regionId)

    /** 启动/重载后的比赛恢复：中止未完成的比赛并逐人恢复装备。 */
    fun recoverPersisted(reason: String) = coordinator.recoverPersisted(reason)

    /** 计时驱动；[RegionsPlugin] 的看门狗会兜底调用。 */
    fun tick() = coordinator.tick()

    fun stopTicking() = coordinator.stopTicking()
}
