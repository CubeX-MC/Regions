package org.cubexmc.regions.mode

import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.cubexmc.regions.match.GearSnapshot
import java.util.Locale

/**
 * 临时装备的唯一实现：解析 `kit` / `armor` / `offhand`，发装备，写回快照。
 *
 * 在这之前每类玩法各写一份：战斗层认 `kit`+`armor`+`offhand`，捉迷藏只认 `kit`
 * （`armor`、`offhand` 设了没反应），竞速一个都不认（设了校验还通过）。
 * 同一个字段在三种玩法里有三种行为，本身就是 bug 的温床，所以收成一处。
 *
 * 物品写法：`MATERIAL[:数量]`，用 `,` 或 `;` 分隔，例如
 * `IRON_SWORD:1,BOW:1,ARROW:16`。认不出的材料名会被跳过——
 * 名字是否有效由发布前校验负责报错，运行时不该因为一个错别字把整场比赛掀了。
 */
object ModeKit {

    /** 护甲槽数量；多出来的条目会被丢弃（由校验阶段提示）。 */
    private const val ARMOR_SLOTS = 4

    /** 单个条目的数量上限，和原版堆叠上限一致。 */
    private const val MAX_STACK = 64

    fun parseItems(value: String?): List<ItemStack> {
        if (value.isNullOrBlank()) return emptyList()
        return value.split(',', ';')
            .mapNotNull { raw ->
                val parts = raw.trim().split(':')
                val material = Material.matchMaterial(parts[0].trim().uppercase(Locale.ROOT)) ?: return@mapNotNull null
                val amount = parts.getOrNull(1)?.toIntOrNull()?.coerceIn(1, MAX_STACK) ?: 1
                ItemStack(material, amount)
            }
    }

    /**
     * 清空背包后按配置发装备。
     *
     * [kit] 进主背包，[armor] 按顺序进护甲槽（靴→腿→胸→头，与 Bukkit 的
     * `armorContents` 下标一致），[offhand] 取第一件进副手。
     * 调用方必须已经把入场前状态存进 escrow——这个方法**不做**任何备份。
     */
    fun apply(player: Player, kit: String?, armor: String?, offhand: String?) {
        player.inventory.clear()
        player.inventory.armorContents = arrayOfNulls(ARMOR_SLOTS)
        player.inventory.setItemInOffHand(null)
        for (item in parseItems(kit)) {
            player.inventory.addItem(item)
        }
        val armorItems = parseItems(armor).take(ARMOR_SLOTS)
        val armorContents = arrayOfNulls<ItemStack>(ARMOR_SLOTS)
        for (index in armorItems.indices) {
            armorContents[index] = armorItems[index]
        }
        player.inventory.armorContents = armorContents
        parseItems(offhand).firstOrNull()?.let { player.inventory.setItemInOffHand(it) }
        player.updateInventory()
    }

    /** [apply] 的玩法配置重载。[kitKeys] 按顺序取第一个非空值，用于捉迷藏的分角色套件。 */
    fun apply(player: Player, values: Map<String, String>, vararg kitKeys: String) {
        val kit = kitKeys.firstNotNullOfOrNull { key -> values[key]?.takeIf { it.isNotBlank() } }
        apply(player, kit, values["armor"], values["offhand"])
    }

    /**
     * 这块场地是否要接管玩家装备。
     *
     * 显式 `replace-gear: true` 当然算；只填了套件而没写 `replace-gear` 也算——
     * 填了套件却不生效比报错更难查。[extraKitKeys] 给捉迷藏传 `seeker-kit` / `hider-kit`。
     */
    fun shouldReplaceGear(values: Map<String, String>?, vararg extraKitKeys: String): Boolean {
        if (values == null) return false
        if (values["replace-gear"]?.toBooleanStrictOrNull() == true) return true
        val keys = listOf("kit", "armor", "offhand") + extraKitKeys
        return keys.any { !values[it].isNullOrBlank() }
    }

    /** 把快照写回玩家；**不**碰 escrow 记录，删除时机由 [ModeGearEscrow] 决定。 */
    fun writeSnapshot(player: Player, snapshot: GearSnapshot) {
        player.inventory.contents = snapshot.contents
        player.inventory.armorContents = snapshot.armor
        player.inventory.setItemInOffHand(snapshot.offhand)
        player.level = snapshot.level
        player.exp = snapshot.exp
        player.gameMode = snapshot.gameMode
        player.updateInventory()
    }

    /** 同上，针对落盘记录。 */
    fun writeStored(player: Player, stored: CombatGearStore.StoredGear) {
        player.inventory.contents = stored.contents
        player.inventory.armorContents = stored.armor
        player.inventory.setItemInOffHand(stored.offhand)
        player.level = stored.level
        player.exp = stored.exp
        player.gameMode = stored.gameMode
        player.updateInventory()
    }
}
