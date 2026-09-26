package org.cubexmc.regions.mode

import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.inventory.ItemStack
import org.cubexmc.regions.RegionsPlugin
import org.cubexmc.regions.match.GearSnapshot
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Base64
import java.util.UUID

class CombatGearStore(private val plugin: RegionsPlugin, fileName: String = "combat-escrow.yml") {
    private val file = File(plugin.dataFolder, fileName)
    private val entries: MutableMap<UUID, StoredGear> = LinkedHashMap()

    @Synchronized
    fun load() {
        entries.clear()
        if (!file.exists()) {
            return
        }
        val yaml = YamlConfiguration.loadConfiguration(file)
        val version = yaml.getInt("escrow-version", -1)
        require(version == ESCROW_VERSION) {
            "Unsupported combat escrow version $version; expected $ESCROW_VERSION."
        }
        for (key in yaml.getKeys(false)) {
            if (key == "escrow-version") continue
            val uuid = try {
                UUID.fromString(key)
            } catch (ex: IllegalArgumentException) {
                throw IllegalStateException("Invalid combat escrow player id '$key'.", ex)
            }
            val section = yaml.getConfigurationSection(key) ?: continue
            try {
                entries[uuid] = StoredGear(
                    section.getString("region", "") ?: "",
                    decodeItems(section.getString("contents")),
                    decodeItems(section.getString("armor")),
                    decodeItems(section.getString("offhand")).firstOrNull(),
                    section.getInt("level", 0),
                    section.getDouble("exp", 0.0).toFloat(),
                    parseGameMode(section.getString("game-mode")),
                    parseLocation(section.getString("respawn")),
                    section.getBoolean("confirmed", false),
                )
            } catch (ex: RuntimeException) {
                throw IllegalStateException(
                    "Refusing to load Regions with unreadable combat escrow for $uuid: ${ex.message}",
                    ex,
                )
            }
        }
    }

    @Synchronized
    fun put(playerId: UUID, regionId: String, snapshot: GearSnapshot) {
        val previous = entries.put(playerId, StoredGear(
            regionId,
            snapshot.contents,
            snapshot.armor,
            snapshot.offhand,
            snapshot.level,
            snapshot.exp,
            snapshot.gameMode,
            snapshot.respawn,
            confirmed = false,
        ))
        try {
            save()
        } catch (error: RuntimeException) {
            if (previous == null) entries.remove(playerId) else entries[playerId] = previous
            throw error
        } catch (error: java.io.IOException) {
            if (previous == null) entries.remove(playerId) else entries[playerId] = previous
            throw error
        }
    }

    /**
     * 读取但不删除。恢复流程先用它取快照、写回玩家背包，写入确认成功后才用 [take]
     * 确认删除——这条顺序保证写背包失败时持久化记录还在，不会吞装备。
     */
    @Synchronized
    fun peek(playerId: UUID): StoredGear? = entries[playerId]

    /** [peek] 之后的确认删除：移除并落盘，落盘失败时把记录放回并抛出。 */
    @Synchronized
    fun take(playerId: UUID): StoredGear? {
        val removed = entries.remove(playerId)
        if (removed != null) {
            try {
                save()
            } catch (error: RuntimeException) {
                entries[playerId] = removed
                throw error
            } catch (error: java.io.IOException) {
                entries[playerId] = removed
                throw error
            }
        }
        return removed
    }

    /**
     * 把 [peek] 出来的快照标记为"已经写回玩家背包"并**立即落盘**。
     *
     * 必须在 [take] 之前调用：只有确认先落了盘，宕机停在两步之间时才分得清
     * "还没写回"（重放）与"写回过了"（只清理）。落盘失败时回滚内存标记并抛出，
     * 调用方应保留 escrow 等下次恢复——重写同一份快照是幂等的。
     */
    @Synchronized
    fun markConfirmed(playerId: UUID): Boolean {
        val current = entries[playerId] ?: return false
        if (current.confirmed) return true
        entries[playerId] = current.copy(confirmed = true)
        try {
            save()
        } catch (error: RuntimeException) {
            entries[playerId] = current
            throw error
        } catch (error: java.io.IOException) {
            entries[playerId] = current
            throw error
        }
        return true
    }

    @Synchronized
    fun allPlayerIds(): Set<UUID> = entries.keys.toSet()

    private fun save() {
        val yaml = YamlConfiguration()
        yaml.set("escrow-version", ESCROW_VERSION)
        for ((uuid, stored) in entries) {
            val path = uuid.toString()
            yaml.set("$path.region", stored.regionId)
            yaml.set("$path.contents", encodeItems(stored.contents))
            yaml.set("$path.armor", encodeItems(stored.armor))
            yaml.set("$path.offhand", encodeItems(arrayOf(stored.offhand)))
            yaml.set("$path.level", stored.level)
            yaml.set("$path.exp", stored.exp.toDouble())
            yaml.set("$path.game-mode", stored.gameMode.name)
            yaml.set("$path.respawn", formatLocation(stored.respawn))
            yaml.set("$path.confirmed", stored.confirmed)
        }
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, "${file.name}.tmp")
        try {
            yaml.save(temporary)
            try {
                Files.move(
                    temporary.toPath(),
                    file.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    private fun encodeItems(items: Array<ItemStack?>): String {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { output ->
            output.writeInt(items.size)
            for (item in items) {
                output.writeBoolean(item != null)
                if (item != null) {
                    val itemBytes = item.serializeAsBytes()
                    output.writeInt(itemBytes.size)
                    output.write(itemBytes)
                }
            }
        }
        return PAPER_FORMAT_PREFIX + Base64.getEncoder().encodeToString(bytes.toByteArray())
    }

    private fun decodeItems(value: String?): Array<ItemStack?> {
        if (value.isNullOrBlank()) {
            return emptyArray()
        }
        require(value.startsWith(PAPER_FORMAT_PREFIX)) {
            "Unsupported pre-release combat escrow format; only $PAPER_FORMAT_PREFIX data is accepted."
        }
        return decodePaperItems(value.removePrefix(PAPER_FORMAT_PREFIX))
    }

    private fun decodePaperItems(value: String): Array<ItemStack?> {
        val bytes = Base64.getDecoder().decode(value)
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            val size = input.readInt()
            require(size in 0..MAX_ITEM_COUNT) { "Invalid Paper item array size $size." }
            return Array(size) {
                if (!input.readBoolean()) {
                    null
                } else {
                    val byteCount = input.readInt()
                    require(byteCount in 1..MAX_ITEM_BYTES) { "Invalid Paper item payload size $byteCount." }
                    ItemStack.deserializeBytes(input.readNBytes(byteCount).also {
                        require(it.size == byteCount) { "Truncated Paper item payload." }
                    })
                }
            }
        }
    }

    private fun parseGameMode(value: String?): GameMode =
        try {
            if (value == null) GameMode.SURVIVAL else GameMode.valueOf(value)
        } catch (ex: IllegalArgumentException) {
            GameMode.SURVIVAL
        }

    private fun parseLocation(value: String?): Location? {
        if (value.isNullOrBlank()) {
            return null
        }
        val parts = value.split(',')
        if (parts.size < 4) {
            return null
        }
        val world = plugin.server.getWorld(parts[0]) ?: return null
        val x = parts[1].toDoubleOrNull() ?: return null
        val y = parts[2].toDoubleOrNull() ?: return null
        val z = parts[3].toDoubleOrNull() ?: return null
        val yaw = parts.getOrNull(4)?.toFloatOrNull() ?: 0.0f
        val pitch = parts.getOrNull(5)?.toFloatOrNull() ?: 0.0f
        return Location(world, x, y, z, yaw, pitch)
    }

    private fun formatLocation(location: Location?): String? {
        if (location == null) {
            return null
        }
        return "${location.world?.name},${location.x},${location.y},${location.z},${location.yaw},${location.pitch}"
    }

    data class StoredGear(
        val regionId: String,
        val contents: Array<ItemStack?>,
        val armor: Array<ItemStack?>,
        val offhand: ItemStack?,
        val level: Int,
        val exp: Float,
        val gameMode: GameMode,
        val respawn: Location?,
        /**
         * 这份快照是否**已经写回过**玩家背包。
         *
         * 恢复顺序是"写回 → 落盘确认 → 删记录"，所以宕机可能停在"已写回但记录还在"。
         * 重启后看到 `confirmed = true` 只做清理，不再覆盖玩家此后拿到的新背包。
         * 旧版本文件没有这个字段，读出来是 false —— 那是安全的一侧：最坏是把同一份
         * 快照幂等地再写一次，而不是把装备吞掉。
         */
        val confirmed: Boolean = false,
    )

    private companion object {
        const val ESCROW_VERSION = 1
        const val PAPER_FORMAT_PREFIX = "paper-v1:"
        const val MAX_ITEM_COUNT = 256
        const val MAX_ITEM_BYTES = 4 * 1024 * 1024
    }
}
