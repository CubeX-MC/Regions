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
import org.cubexmc.regions.match.DamageDecision
import org.cubexmc.regions.model.RegionTrigger
import java.util.Locale

class PlayerLifecycleListener(private val plugin: RegionsPlugin) : Listener {
    @EventHandler
    fun onJoin(event: PlayerJoinEvent) {
        plugin.effects().restoreIfPending(event.player, "join-recovery")
        plugin.combatModes().restoreIfPending(event.player, "join-recovery")
        plugin.roundModes().restoreIfPending(event.player, "join-recovery")
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
        val responsible = attacker ?: return
        if (plugin.roundModes().onDamage(event)) {
            return
        }
        if (plugin.flagRules().isDenied(victim, "pvp") || plugin.flagRules().isDenied(responsible, "pvp")) {
            event.isCancelled = true
        }
    }

    /**
     * 丢弃与拾取：场地规则之外，**装备托管期间**一律禁止（PLAN.md §6.3.6）。
     * 托管期间背包里是比赛发的临时装备，转移出去会让恢复快照与场上物品分叉。
     */
    @EventHandler(ignoreCancelled = true)
    fun onDrop(event: PlayerDropItemEvent) {
        if (plugin.combatModes().isGearEscrowed(event.player.uniqueId)) {
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
        if (plugin.combatModes().isGearEscrowed(player.uniqueId)) {
            event.isCancelled = true
            return
        }
        if (plugin.flagRules().isDenied(player, "item_pickup")) {
            event.isCancelled = true
        }
    }

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
        if (plugin.config.getBoolean("safety.cleanup-on-quit", true)) {
            plugin.sessions().cleanup(event.player, "quit")
        }
    }

    @EventHandler
    fun onKick(event: PlayerKickEvent) {
        plugin.trials().stop(event.player, "kick")
        plugin.combatModes().onDisconnect(event.player, "kick")
        if (plugin.config.getBoolean("safety.cleanup-on-quit", true)) {
            plugin.sessions().cleanup(event.player, "kick")
        }
    }

    @EventHandler
    fun onDeath(event: PlayerDeathEvent) {
        plugin.trials().stop(event.entity, "death")
        val combatHandled = plugin.combatModes().onDeath(event)
        val roundHandled = plugin.roundModes().onDeath(event)
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
        if (!combatHandled && !roundHandled && plugin.config.getBoolean("safety.cleanup-on-death", true)) {
            plugin.sessions().cleanup(event.entity, "death")
        }
    }

    @EventHandler
    fun onRespawn(event: PlayerRespawnEvent) {
        plugin.combatModes().onRespawn(event)
        plugin.roundModes().onRespawn(event.player)
        plugin.regionScheduler().runAtEntityLater(event.player, Runnable {
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
    private fun attackingPlayer(event: EntityDamageByEntityEvent): Player? =
        when (val damager = event.damager) {
            is Player -> damager
            is Projectile -> damager.shooter as? Player
            is AreaEffectCloud -> (damager.source as? Projectile)?.shooter as? Player
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
