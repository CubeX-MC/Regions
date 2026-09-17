package org.cubexmc.regions.match

import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

/**
 * 入场前的玩家状态快照（装备、护甲、副手、经验、游戏模式与我们写入的返回点）。
 *
 * 只被 `CombatGearStore` 持有和落盘；比赛自身不保存第二份可变装备真相（PLAN.md §6.1）。
 * 新增受控状态时在这里加字段并带版本迁移，旧记录必须继续可读。
 */
data class GearSnapshot(
    val contents: Array<ItemStack?>,
    val armor: Array<ItemStack?>,
    val offhand: ItemStack?,
    val level: Int,
    val exp: Float,
    val gameMode: GameMode,
    val respawn: Location?,
) {
    companion object {
        fun capture(player: Player, respawn: Location?): GearSnapshot =
            GearSnapshot(
                player.inventory.contents.map { it?.clone() }.toTypedArray(),
                player.inventory.armorContents.map { it?.clone() }.toTypedArray(),
                player.inventory.itemInOffHand.clone(),
                player.level,
                player.exp,
                player.gameMode,
                respawn,
            )
    }
}
