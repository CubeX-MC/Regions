package org.cubexmc.regions.mode

import org.bukkit.Location
import org.bukkit.entity.Player
import org.cubexmc.regions.RegionsPlugin
import org.cubexmc.regions.match.GearSnapshot
import java.util.UUID

/**
 * 装备托管的**崩溃安全**协议，竞速与捉迷藏共用。
 *
 * 顺序只有一种是对的：
 *
 * ```
 * peek 取快照 → 写回玩家背包 → 落盘"已确认" → 删除 escrow 记录
 * ```
 *
 * 反过来先删记录再写背包，只要写背包这一步没成（异常、玩家正好掉线、Folia 上
 * 实体任务没跑到），装备就**永久消失**了——记录已经没了，没人知道该还什么。
 * `RoundModeService.restoreStored()` 原本正是先 `take()` 再写背包；战斗层在 M0
 * 修过这个顺序，捉迷藏没跟上。收进这个类之后，三类玩法只有一份实现。
 *
 * 宕机可能停在"已写回、还没删记录"，重启后 [restore] 看到 `confirmed = true`
 * 只做清理，不会拿旧快照覆盖玩家此后拿到的新背包。
 */
class ModeGearEscrow(
    private val plugin: RegionsPlugin,
    fileName: String,
    /** 日志前缀，出问题时一眼看出是哪类玩法的托管。 */
    private val label: String,
) {
    private val store = CombatGearStore(plugin, fileName)

    fun load() = store.load()

    /** 入场前快照；已经有记录时不覆盖（同一场比赛只捕获一次原始装备）。 */
    fun capture(player: Player, regionId: String, respawn: Location?): GearSnapshot? {
        if (store.peek(player.uniqueId) != null) return null
        val snapshot = GearSnapshot.capture(player, respawn)
        store.put(player.uniqueId, regionId, snapshot)
        return snapshot
    }

    fun isEscrowed(playerId: UUID): Boolean = store.peek(playerId) != null

    fun escrowedRegion(playerId: UUID): String? = store.peek(playerId)?.regionId

    fun pendingPlayerIds(): Set<UUID> = store.allPlayerIds()

    /**
     * 按上面的顺序恢复一名玩家，返回是否**确实写回了**内容。
     *
     * 必须在玩家自己的线程上调用（Folia 的实体调度）。落盘确认失败时保留 escrow
     * 并抛出，下次登录或下次清理会重放——重写同一份快照是幂等的。
     */
    fun restore(player: Player, reason: String, teleport: Boolean = true): Boolean {
        // Death/respawn processing can still replace the inventory; keep the durable lease.
        if (player.isDead) return false
        val playerId = player.uniqueId
        val stored = store.peek(playerId) ?: return false
        if (stored.confirmed) {
            // 上次已经写回过，只是没来得及删记录：补清理，不覆盖玩家现在的背包。
            store.take(playerId)
            plugin.log().warn("Cleared already-restored $label escrow for ${player.name}: $reason")
            return false
        }
        ModeKit.writeStored(player, stored)
        player.saveData()
        store.markConfirmed(playerId)
        store.take(playerId)
        if (teleport) {
            stored.respawn?.let { plugin.regionScheduler().teleportAsync(player, it) }
        }
        plugin.log().debug("Restored $label escrow for ${player.name}: $reason")
        return true
    }

    /**
     * 把在线的待恢复玩家全部还回去（重载、停服、`/regions cleanup`）。
     *
     * [immediate] 为 true 时就地执行：停服／重载时调度器上的任务不保证还会跑，
     * 排队等于把装备留在 escrow 里。离线玩家保持待恢复，登录时由 [restore] 接手。
     */
    fun restoreAllOnline(reason: String, immediate: Boolean, shuttingDown: Boolean = false) {
        for (playerId in pendingPlayerIds()) {
            val player = plugin.server.getPlayer(playerId) ?: continue
            val task = Runnable {
                runCatching { restore(player, reason) }
                    .onFailure { plugin.log().severe("Failed to restore $label escrow for ${player.name}: ${it.message}") }
            }
            if (immediate) task.run() else if (!shuttingDown) plugin.regionScheduler().runAtEntity(player, task)
        }
    }
}
