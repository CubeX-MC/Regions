package org.cubexmc.regions.listener

import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityPickupItemEvent
import org.bukkit.event.player.PlayerKickEvent
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerRespawnEvent
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.event.player.PlayerToggleFlightEvent
import org.bukkit.entity.AreaEffectCloud
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.entity.Tameable
import org.bukkit.entity.TNTPrimed
import org.bukkit.inventory.EquipmentSlot
import org.cubexmc.regions.RegionsPlugin
import org.bukkit.event.entity.AreaEffectCloudApplyEvent
import org.bukkit.event.entity.PotionSplashEvent
import org.bukkit.potion.PotionEffectType
import org.cubexmc.regions.match.MatchEffectPolicy
import org.cubexmc.regions.match.DamageDecision
import org.cubexmc.regions.match.MatchCommandPolicy
import org.cubexmc.regions.mode.ModeDamagePolicy
import org.cubexmc.regions.model.RegionTrigger
import java.util.Locale
import java.util.UUID

class PlayerLifecycleListener(private val plugin: RegionsPlugin) : Listener {
    @EventHandler
    fun onJoin(event: PlayerJoinEvent) {
        plugin.effects().restoreIfPending(event.player, "join-recovery")
        plugin.combatModes().restoreIfPending(event.player, "join-recovery")
        plugin.roundModes().restoreIfPending(event.player, "join-recovery")
        plugin.raceModes().restoreIfPending(event.player, "join-recovery")
    }

    @EventHandler
    fun onMove(event: PlayerMoveEvent) {
        val from = event.from
        val to = event.to ?: return
        if (from.world == to.world && from.blockX == to.blockX && from.blockY == to.blockY && from.blockZ == to.blockZ) {
            return
        }
        plugin.detection().updatePlayer(event.player)
        plugin.raceModes().onMove(event.player)
        plugin.roundModes().onMove(event)
    }

    @EventHandler
    fun onTeleport(event: PlayerTeleportEvent) {
        plugin.regionScheduler().runAtEntityLater(event.player, Runnable {
            plugin.detection().updatePlayer(event.player)
        }, 1L)
    }

    @EventHandler(ignoreCancelled = true)
    fun onToggleFlight(event: PlayerToggleFlightEvent) {
        if (plugin.flagRules().isDenied(event.player, "fly")) {
            event.isCancelled = true
            event.player.isFlying = false
        }
    }

    /**
     * 伤害与成员隔离（PLAN.md §6.3）。
     *
     * 判定顺序：先问比赛（同局敌对才放行、候场/观战/局外人/阶段保护一律拒绝），
     * 与比赛无关时才落回场地 PVP 规则。**已经取消的事件不再改回来**——
     * 其他插件取消的伤害要尊重，通用监听器不抢这个决定权。
     */
    @EventHandler(ignoreCancelled = true)
    fun onDamage(event: EntityDamageByEntityEvent) {
        val victim = event.entity as? Player ?: return
        val attacker = attackingPlayer(event)
        when (plugin.combatModes().damageDecision(attacker?.uniqueId, victim.uniqueId, isPlayerSourced(event.damager))) {
            DamageDecision.DENY -> {
                event.isCancelled = true
                return
            }

            DamageDecision.UNRELATED, DamageDecision.ALLOW -> Unit
        }
        // 搜寻者抓躲藏者要先于隔离判定：那一下会被取消并记成“抓到”，不是普通伤害。
        if (plugin.roundModes().onDamage(event)) {
            return
        }
        // 竞速与捉迷藏的成员隔离。只对**可归因于玩家**的伤害生效——
        // 僵尸咬一口、掉进岩浆仍然照常结算，环境死亡同样计入淘汰。
        if (isPlayerSourced(event.damager) && modeDamageDenied(attacker?.uniqueId, victim.uniqueId)) {
            event.isCancelled = true
            return
        }
        val responsible = attacker ?: return
        if (plugin.flagRules().isDenied(victim, "pvp") || plugin.flagRules().isDenied(responsible, "pvp")) {
            event.isCancelled = true
        }
    }

    /**
     * 竞速／捉迷藏的伤害隔离：任何一方牵涉到一场进行中的这类比赛就拒绝。
     * 两边都与这类比赛无关时返回 false，交回场地 Flag 处理。
     */
    private fun modeDamageDenied(attackerId: UUID?, victimId: UUID): Boolean {
        val attackerMembership = attackerId?.let { modeMembership(it) }
        val victimMembership = modeMembership(victimId)
        if (attackerMembership == null && victimMembership == null) return false
        return ModeDamagePolicy.decide(attackerMembership, victimMembership) == DamageDecision.DENY
    }

    /** 一名玩家与竞速或捉迷藏的关系；两者互斥，同一时刻最多命中一个。 */
    private fun modeMembership(playerId: UUID): ModeDamagePolicy.Membership? =
        plugin.raceModes().membership(playerId) ?: plugin.roundModes().membership(playerId)

    /**
     * 浓缩药水（泼洒）的成员隔离。
     *
     * 伤害类药水会走伤害事件，已经被 [onDamage] 拦下；**纯效果药水不会**，
     * 所以中毒、缓慢、虚弱这些在补上这一层之前能直接穿过比赛边界。
     * 拒绝时把该目标的强度设为 0，而不是取消整个事件——同一瓶药可能同时泼到该受影响的人。
     */
    @EventHandler(ignoreCancelled = true)
    fun onPotionSplash(event: PotionSplashEvent) {
        val thrower = event.entity.shooter as? Player
        val kind = effectKind(event.potion.effects.map { it.type })
        for (affected in event.affectedEntities.toList()) {
            val target = affected as? Player ?: continue
            if (plugin.combatModes().effectDecision(thrower?.uniqueId, target.uniqueId, kind) == DamageDecision.DENY ||
                modeEffectDenied(thrower?.uniqueId, target.uniqueId, kind)
            ) {
                event.setIntensity(target, 0.0)
            }
        }
    }

    /** 滞留药水云：同上，拒绝的人直接从受影响名单里移除。 */
    @EventHandler(ignoreCancelled = true)
    fun onAreaEffectCloudApply(event: AreaEffectCloudApplyEvent) {
        val cloud = event.entity
        val thrower = cloud.source as? Player
        val kind = effectKind(cloud.customEffects.map { it.type })
        event.affectedEntities.removeIf { affected ->
            val target = affected as? Player ?: return@removeIf false
            plugin.combatModes().effectDecision(thrower?.uniqueId, target.uniqueId, kind) == DamageDecision.DENY ||
                modeEffectDenied(thrower?.uniqueId, target.uniqueId, kind)
        }
    }

    /** 药水／滞留云版本的 [modeDamageDenied]；自己喝的药不拦。 */
    private fun modeEffectDenied(
        throwerId: UUID?,
        targetId: UUID,
        kind: MatchEffectPolicy.EffectKind,
    ): Boolean {
        val throwerMembership = throwerId?.let { modeMembership(it) }
        val targetMembership = modeMembership(targetId)
        if (throwerMembership == null && targetMembership == null) return false
        return ModeDamagePolicy.decideEffect(
            throwerMembership,
            targetMembership,
            kind,
            selfInflicted = throwerId != null && throwerId == targetId,
        ) == DamageDecision.DENY
    }

    /**
     * 一瓶药的性质。混合药水（自定义或插件给的）只要含一个负面效果就整瓶当负面处理：
     * 否则把一个回血效果搭进去就能把中毒送进场里。
     */
    private fun effectKind(types: List<PotionEffectType>): MatchEffectPolicy.EffectKind {
        val categories = types.map { it.effectCategory }
        return when {
            categories.contains(PotionEffectType.Category.HARMFUL) -> MatchEffectPolicy.EffectKind.HARMFUL
            categories.contains(PotionEffectType.Category.BENEFICIAL) -> MatchEffectPolicy.EffectKind.BENEFICIAL
            else -> MatchEffectPolicy.EffectKind.NEUTRAL
        }
    }

    /**
     * 丢弃与拾取：场地规则之外，**装备托管期间**一律禁止（PLAN.md §6.3.6）。
     * 托管期间背包里是比赛发的临时装备，转移出去会让恢复快照与场上物品分叉。
     */
    @EventHandler(ignoreCancelled = true)
    fun onDrop(event: PlayerDropItemEvent) {
        if (isGearEscrowed(event.player.uniqueId)) {
            event.isCancelled = true
            return
        }
        if (plugin.flagRules().isDenied(event.player, "item_drop")) {
            event.isCancelled = true
        }
    }

    @EventHandler(ignoreCancelled = true)
    fun onPickup(event: EntityPickupItemEvent) {
        val player = event.entity as? Player ?: return
        if (isGearEscrowed(player.uniqueId)) {
            event.isCancelled = true
            return
        }
        if (plugin.flagRules().isDenied(player, "item_pickup")) {
            event.isCancelled = true
        }
    }

    /**
     * 托管期间禁止丢弃与拾取（AGENTS.md 的装备不变量）。
     * 三类玩法各有一份 escrow 文件，但保护是同一条：背包里是比赛发的临时装备，
     * 转移出去会让恢复快照与场上物品分叉。
     */
    private fun isGearEscrowed(playerId: UUID): Boolean =
        plugin.combatModes().isGearEscrowed(playerId) ||
            plugin.raceModes().isGearEscrowed(playerId) ||
            plugin.roundModes().isGearEscrowed(playerId)

    /** 正在打的任何一类比赛：指令封锁对八种玩法一视同仁。 */
    private fun isPlayingLiveMatch(playerId: UUID): Boolean =
        plugin.combatModes().isPlayingLiveMatch(playerId) ||
            plugin.raceModes().isRacing(playerId) ||
            plugin.roundModes().isPlaying(playerId)

    @EventHandler(ignoreCancelled = true)
    fun onInteract(event: PlayerInteractEvent) {
        // Both hands raise the event for a single right click; only the main hand should fire once.
        if (event.hand != null && event.hand != EquipmentSlot.HAND) {
            return
        }
        for (session in plugin.sessions().activeSessions(event.player.uniqueId)) {
            val region = plugin.regions().find(session.regionId) ?: continue
            session.metadata["last_interact"] = event.action.name.lowercase(Locale.ROOT)
            plugin.triggers().fire(RegionTrigger.ON_INTERACT, event.player, region)
        }
    }

    @EventHandler(ignoreCancelled = true)
    fun onCommand(event: PlayerCommandPreprocessEvent) {
        val label = event.message.removePrefix("/").trim().substringBefore(' ')
        for (session in plugin.sessions().activeSessions(event.player.uniqueId)) {
            val region = plugin.regions().find(session.regionId) ?: continue
            session.metadata["last_command"] = label
            plugin.triggers().fire(RegionTrigger.ON_COMMAND, event.player, region)
        }
        // 比赛进行中的封锁排在场地 Flag 前面，且**不认 `regions.bypass.flags`**：
        // 比赛内部保护不受场地 Flag bypass 影响（PLAN.md §6.3.1）。
        if (isPlayingLiveMatch(event.player.uniqueId) &&
            MatchCommandPolicy.isBlocked(label, plugin.matchAllowedCommands()) &&
            !plugin.authority().isSuperAdmin(event.player)
        ) {
            event.isCancelled = true
            plugin.lang().sendPlain(event.player, "command.match-denied", mapOf("command" to label))
            return
        }
        if (plugin.flagRules().isCommandBlocked(event.player, label)) {
            event.isCancelled = true
            plugin.lang().sendRaw(event.player, plugin.lang().message("command.flag-denied", mapOf("command" to label)))
        }
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        plugin.trials().stop(event.player, "quit")
        // 比赛先按弃权处理：即使服主关掉了 cleanup-on-quit，也不能把离线玩家当成存活选手。
        plugin.combatModes().onDisconnect(event.player, "quit")
        plugin.raceModes().onDisconnect(event.player, "quit")
        plugin.roundModes().onDisconnect(event.player, "quit")
        if (plugin.config.getBoolean("safety.cleanup-on-quit", true)) {
            plugin.sessions().cleanup(event.player, "quit")
        }
    }

    @EventHandler
    fun onKick(event: PlayerKickEvent) {
        plugin.trials().stop(event.player, "kick")
        plugin.combatModes().onDisconnect(event.player, "kick")
        plugin.raceModes().onDisconnect(event.player, "kick")
        plugin.roundModes().onDisconnect(event.player, "kick")
        if (plugin.config.getBoolean("safety.cleanup-on-quit", true)) {
            plugin.sessions().cleanup(event.player, "kick")
        }
    }

    @EventHandler
    fun onDeath(event: PlayerDeathEvent) {
        plugin.trials().stop(event.entity, "death")
        val combatHandled = plugin.combatModes().onDeath(event)
        val roundHandled = plugin.roundModes().onDeath(event)
        val raceHandled = plugin.raceModes().onDeath(event)
        for (session in plugin.sessions().activeSessions(event.entity.uniqueId)) {
            val region = plugin.regions().find(session.regionId) ?: continue
            plugin.triggers().fire(RegionTrigger.ON_DEATH, event.entity, region)
        }
        val killer = event.entity.killer
        if (killer != null) {
            for (session in plugin.sessions().activeSessions(killer.uniqueId)) {
                val region = plugin.regions().find(session.regionId) ?: continue
                plugin.triggers().fire(RegionTrigger.ON_KILL, killer, region)
            }
        }
        if (!combatHandled && !roundHandled && !raceHandled &&
            plugin.config.getBoolean("safety.cleanup-on-death", true)
        ) {
            plugin.sessions().cleanup(event.entity, "death")
        }
    }

    @EventHandler
    fun onRespawn(event: PlayerRespawnEvent) {
        plugin.combatModes().onRespawn(event)
        plugin.regionScheduler().runAtEntityLater(event.player, Runnable {
            plugin.roundModes().onRespawn(event.player)
            plugin.raceModes().onRespawn(event.player)
            for (session in plugin.sessions().activeSessions(event.player.uniqueId)) {
                val region = plugin.regions().find(session.regionId) ?: continue
                plugin.triggers().fire(RegionTrigger.ON_RESPAWN, event.player, region)
            }
        }, 1L)
    }

    /**
     * 伤害来源里的责任玩家：近战、投射物（箭矢/三叉戟/雪球）、药水与滞留云的投掷者、
     * 有主人的宠物、玩家点燃的 TNT。返回 null 但 [isPlayerSourced] 为 true 时，
     * 说明这次伤害可归因于玩家却定位不到具体是谁，比赛成员一律拒绝（PLAN.md §6.3.2）。
     */
    private fun attackingPlayer(event: EntityDamageByEntityEvent): Player? = attackerOf(event.damager)

    /**
     * 拆出来的纯归因：只看 damager 实体，因此能用 mock 穷举（[PlayerLifecycleAttributionTest]）。
     *
     * `AreaEffectCloud.getSource()` 返回的是 **`ProjectileSource`**，对滞留药水而言就是扔它的玩家本人。
     * `Projectile` **不实现** `ProjectileSource`，所以曾经那句"先把 source 当成 `Projectile` 再取 shooter"
     * 运行时永远不成立，滞留药水于是退化成"来源不可归因"：比赛里被一律拒绝，
     * 比赛外则因为拿不到责任人而跳过场地 `pvp` 规则（一个保护空子）。
     */
    internal fun attackerOf(damager: Entity): Player? =
        when (damager) {
            is Player -> damager
            is Projectile -> damager.shooter as? Player
            // source 的静态类型就是 ProjectileSource，没有"它可能是投射物"这种分支可言。
            is AreaEffectCloud -> damager.source as? Player
            is Tameable -> damager.ownerUniqueId?.let { plugin.server.getPlayer(it) }
            is TNTPrimed -> damager.source as? Player
            else -> null
        }

    private fun isPlayerSourced(damager: Entity): Boolean =
        damager is Player ||
            damager is Projectile ||
            damager is AreaEffectCloud ||
            damager is Tameable ||
            damager is TNTPrimed
}
