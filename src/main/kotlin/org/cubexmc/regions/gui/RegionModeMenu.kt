package org.cubexmc.regions.gui

import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.cubexmc.regions.match.MatchSpawns
import org.cubexmc.regions.model.ModeConfig
import org.cubexmc.regions.model.RegionDefinition
import java.util.Locale
import java.util.UUID

/**
 * The gameplay-mode editor. Only the settings that belong to the selected mode are rendered, and the
 * raw `key=value` entry stays reserved for super-administrators.
 */
internal class RegionModeMenu(private val gui: RegionsGui) {
    private val plugin get() = gui.plugin
    private val text get() = gui.text
    private val items get() = gui.items

    private val modeSlots = linkedMapOf(
        10 to "free_event",
        11 to "run_race",
        12 to "dual_pvp",
        13 to "boat_race",
        14 to "union_war",
        15 to "horse_race",
        16 to "hide_and_seek",
        17 to "free_for_all",
    )

    fun open(player: Player, regionId: String, returnToPublish: Boolean = false) {
        val region = gui.editable(regionId) ?: return gui.openMain(player)
        val mode = region.mode ?: ModeConfig("free_event")
        val values = mode.values
        val inventory = Bukkit.createInventory(
            RegionsHolder(View.MODE, region.id, returnToPublish = returnToPublish),
            54,
            text.component(player, "gui.mode.title", mapOf("id" to region.id)),
        )
        inventory.setItem(4, items.region(player, region))
        for ((slot, type) in modeSlots) {
            inventory.setItem(slot, items.mode(player, type, region.mode?.type))
        }

        val defaultVehicle = GuiValues.defaultVehicle(mode.type)
        val here = GuiValues.formatLocation(player.location)
        inventory.setItem(19, setting(player, Material.PLAYER_HEAD, "gui.mode.min-players", values["min-players"] ?: "2"))
        inventory.setItem(20, setting(player, Material.SKELETON_SKULL, "gui.mode.max-players", values["max-players"] ?: text.text(player, "gui.common.unlimited")))
        inventory.setItem(21, setting(player, Material.BELL, "gui.mode.require-ready", text.boolDisplay(player, values["require-ready"] ?: "true")))
        inventory.setItem(22, setting(player, Material.CHEST, "gui.mode.replace-gear", text.boolDisplay(player, values["replace-gear"] ?: "true")))
        inventory.setItem(23, setting(player, Material.BEACON, "gui.mode.min-unions", values["min-unions"] ?: "2"))
        inventory.setItem(24, setting(player, Material.MINECART, "gui.mode.vehicle", items.describeVehicle(player, values["vehicle"] ?: defaultVehicle)))
        inventory.setItem(25, text.item(player, Material.ENDER_PEARL, "gui.mode.set-respawn", mapOf("location" to here)))
        inventory.setItem(
            26,
            setting(player, Material.TRIPWIRE_HOOK, "gui.mode.start-vehicle", items.describeVehicle(player, values["start-vehicle"] ?: values["vehicle"] ?: defaultVehicle)),
        )
        inventory.setItem(28, text.item(player, Material.IRON_SWORD, "gui.mode.kit-iron"))
        inventory.setItem(29, text.item(player, Material.BOW, "gui.mode.kit-bow"))
        inventory.setItem(30, text.item(player, Material.DIAMOND_SWORD, "gui.mode.kit-diamond"))
        inventory.setItem(31, text.item(player, GuiIcons.CLEAR, "gui.mode.kit-clear"))
        inventory.setItem(
            32,
            setting(player, Material.REDSTONE, "gui.mode.finish-vehicle", items.describeVehicle(player, values["finish-vehicle"] ?: values["vehicle"] ?: defaultVehicle)),
        )
        inventory.setItem(
            33,
            text.item(player, Material.LODESTONE, "gui.mode.respawn", mapOf("value" to (values["respawn"] ?: values["outside"] ?: text.text(player, "gui.common.unset")))),
        )
        inventory.setItem(34, text.item(player, Material.LAVA_BUCKET, "gui.mode.respawn-clear"))
        inventory.setItem(36, text.item(player, Material.GREEN_WOOL, "gui.mode.set-start", mapOf("value" to (values["start"] ?: text.text(player, "gui.common.unset")))))
        inventory.setItem(37, text.item(player, Material.RED_WOOL, "gui.mode.set-finish", mapOf("value" to (values["finish"] ?: text.text(player, "gui.common.unset")))))
        inventory.setItem(
            38,
            text.item(
                player,
                Material.YELLOW_WOOL,
                "gui.mode.add-checkpoint",
                mapOf(
                    "count" to GuiValues.checkpointCount(values["checkpoints"]).toString(),
                    "vehicle" to items.describeVehicle(player, values["vehicle"] ?: defaultVehicle),
                    "location" to here,
                ),
            ),
        )
        inventory.setItem(39, text.item(player, Material.SHEARS, "gui.mode.clear-checkpoints"))
        inventory.setItem(40, setting(player, Material.TARGET, "gui.mode.require-start", text.boolDisplay(player, values["require-start"] ?: "true")))
        inventory.setItem(41, setting(player, Material.ENDER_EYE, "gui.mode.teleport-start", text.boolDisplay(player, values["teleport-start"] ?: "false")))
        inventory.setItem(42, setting(player, Material.LEVER, "gui.mode.start-mode", text.enumDisplay(player, "start-mode", values["start-mode"] ?: "vote")))
        inventory.setItem(43, setting(player, Material.SLIME_BALL, "gui.mode.radius", values["radius"] ?: "2.5"))
        inventory.setItem(44, judgeItem(player, values))
        inventory.setItem(45, setting(player, Material.ENDER_EYE, "gui.mode.seekers", values["seekers"] ?: text.text(player, "gui.common.auto")))
        inventory.setItem(46, setting(player, Material.CLOCK, "gui.mode.hide-seconds", values["hide-seconds"] ?: "30"))
        inventory.setItem(47, setting(player, Material.RECOVERY_COMPASS, "gui.mode.round-seconds", values["round-seconds"] ?: "300"))
        inventory.setItem(48, text.item(player, Material.PAPER, "gui.mode.advanced"))
        inventory.setItem(49, items.back(player))
        inventory.setItem(50, setting(player, Material.PLAYER_HEAD, "gui.mode.found-becomes-seeker", text.boolDisplay(player, values["found-becomes-seeker"] ?: "true")))
        inventory.setItem(51, setting(player, Material.CLOCK, "gui.mode.timeout-seconds", values["timeout-seconds"] ?: "300"))
        // 战斗玩法的点位与赛制（M3/M4/M6）：与竞速/捉迷藏共用槽位，由 modeConfiguration 决定显隐。
        inventory.setItem(
            45,
            text.item(
                player,
                Material.LODESTONE,
                if (mode.type.equals("union_war", ignoreCase = true)) "gui.mode.add-spawn-a" else "gui.mode.add-spawn",
                mapOf(
                    "count" to MatchSpawns.parseList(values["spawn-points"]).size.toString(),
                    "location" to here,
                ),
            ),
        )
        inventory.setItem(
            46,
            if (mode.type.equals("union_war", ignoreCase = true)) {
                text.item(
                    player,
                    Material.RESPAWN_ANCHOR,
                    "gui.mode.add-spawn-b",
                    mapOf(
                        "count" to MatchSpawns.parseList(values["spawn-points-b"]).size.toString(),
                        "location" to here,
                    ),
                )
            } else {
                text.item(player, Material.SHEARS, "gui.mode.clear-spawns")
            },
        )
        inventory.setItem(
            47,
            when (mode.type.lowercase(Locale.ROOT)) {
                "dual_pvp" -> setting(player, Material.RECOVERY_COMPASS, "gui.mode.round-seconds", values["round-seconds"] ?: "180")
                "union_war" -> setting(player, Material.PLAYER_HEAD, "gui.mode.team-size", values["team-size"] ?: "5")
                else -> setting(player, Material.CLOCK, "gui.mode.timeout-seconds", values["timeout-seconds"] ?: "600")
            },
        )
        inventory.setItem(
            50,
            when (mode.type.lowercase(Locale.ROOT)) {
                "dual_pvp" -> setting(player, Material.NETHER_STAR, "gui.mode.best-of", values["best-of"] ?: "1")
                "union_war" -> setting(player, Material.CLOCK, "gui.mode.timeout-seconds", values["timeout-seconds"] ?: "600")
                else -> inventory.getItem(50)
            },
        )
        if (mode.type.equals("union_war", ignoreCase = true)) {
            inventory.setItem(51, text.item(player, Material.SHEARS, "gui.mode.clear-spawns"))
            inventory.setItem(
                24,
                setting(player, Material.WRITABLE_BOOK, "gui.mode.diplomacy", values["diplomacy"] ?: "agreed"),
            )
        }

        val allowed = GuiSlots.modeConfiguration(mode.type).toMutableSet()
        if (plugin.authority().isSuperAdmin(player)) allowed.add(48)
        for (slot in GuiSlots.MODE_CONFIGURATION) {
            if (!allowed.contains(slot)) inventory.setItem(slot, null)
        }
        player.openInventory(inventory)
    }

    private fun setting(viewer: Player, material: Material, key: String, value: String) =
        text.item(viewer, material, key, mapOf("value" to value))

    fun click(player: Player, holder: RegionsHolder, slot: Int, rightClick: Boolean) {
        val regionId = holder.regionId ?: return gui.openMain(player)
        val region = gui.editable(regionId) ?: return gui.openMain(player)
        val mode = region.mode ?: ModeConfig("free_event")
        if (slot in GuiSlots.MODE_CONFIGURATION && slot != 48 && !GuiSlots.modeConfiguration(mode.type).contains(slot)) {
            return
        }
        if (slot == 48 && !plugin.authority().isSuperAdmin(player)) return
        modeSlots[slot]?.let { type ->
            val values = if (region.mode?.type == type) region.mode.values else GuiValues.defaultModeValues(type)
            return gui.saveAndReopen(player, region.copy(mode = ModeConfig(type, values))) { open(player, regionId) }
        }
        val values = mode.values
        combatSlot(values, mode.type, slot, rightClick)?.let { updated ->
            save(player, region, updated)
            return
        }
        val updated: Map<String, String> = when (slot) {
            19 -> GuiValues.adjustInt(values, "min-players", 2, rightClick, min = 1)
            20 -> GuiValues.adjustInt(values, "max-players", 0, rightClick, min = 0, removeAtZero = true)
            21 -> GuiValues.toggleBool(values, "require-ready", true)
            22 -> GuiValues.toggleBool(values, "replace-gear", true)
            23 -> GuiValues.adjustInt(values, "min-unions", 2, rightClick, min = 2)
            24 -> GuiValues.cycleVehicle(values, "vehicle", GuiValues.defaultVehicle(mode.type))
            25 -> LinkedHashMap(values).apply { this["respawn"] = GuiValues.formatLocation(player.location) }
            26 -> GuiValues.cycleVehicle(values, "start-vehicle", values["vehicle"] ?: GuiValues.defaultVehicle(mode.type))
            28, 29, 30 -> GuiValues.applyKit(values, slot)
            31 -> GuiValues.clearKit(values)
            32 -> GuiValues.cycleVehicle(values, "finish-vehicle", values["vehicle"] ?: GuiValues.defaultVehicle(mode.type))
            34 -> LinkedHashMap(values).apply {
                remove("respawn")
                remove("outside")
            }
            36 -> LinkedHashMap(values).apply { put("start", GuiValues.formatLocation(player.location)) }
            37 -> LinkedHashMap(values).apply { put("finish", GuiValues.formatLocation(player.location)) }
            38 -> appendCheckpoint(values, mode.type, player)
            39 -> LinkedHashMap(values).apply {
                remove("checkpoints")
                remove("checkpoint-vehicles")
            }
            40 -> GuiValues.toggleBool(values, "require-start", true)
            41 -> GuiValues.toggleBool(values, "teleport-start", false)
            42 -> LinkedHashMap(values).apply {
                this["start-mode"] = if (this["start-mode"].equals("judge", ignoreCase = true)) "vote" else "judge"
            }
            43 -> GuiValues.adjustDouble(values, "radius", 2.5, rightClick, min = 1.0)
            44 -> return if (rightClick) {
                save(player, region, GuiValues.toggleJudge(values, player.uniqueId))
            } else {
                promptJudge(player, region)
            }
            45 -> GuiValues.adjustInt(values, "seekers", 1, rightClick, min = 1)
            46 -> GuiValues.adjustInt(values, "hide-seconds", 30, rightClick, min = 0, step = 10)
            47 -> GuiValues.adjustInt(values, "round-seconds", 300, rightClick, min = 0, removeAtZero = true, step = 60)
            48 -> return promptModeValue(player, region)
            49 -> return if (holder.returnToPublish) {
                gui.publish.open(player, regionId)
            } else {
                gui.openDetail(player, regionId)
            }
            50 -> GuiValues.toggleBool(values, "found-becomes-seeker", true)
            51 -> GuiValues.adjustInt(values, "timeout-seconds", 300, rightClick, min = 60, step = 60)
            // 战斗玩法：点位与赛制（M3/M4/M5/M6）。
            45 -> appendSpawn(values, if (mode.type.equals("union_war", ignoreCase = true)) "spawn-points" else "spawn-points", player)
            46 -> if (mode.type.equals("union_war", ignoreCase = true)) {
                appendSpawn(values, "spawn-points-b", player)
            } else {
                LinkedHashMap(values).apply { remove("spawn-points") }
            }

            47 -> when (mode.type.lowercase(Locale.ROOT)) {
                "dual_pvp" -> GuiValues.adjustInt(values, "round-seconds", 180, rightClick, min = 30, step = 30)
                "union_war" -> GuiValues.adjustInt(values, "team-size", 5, rightClick, min = 2)
                else -> GuiValues.adjustInt(values, "timeout-seconds", 600, rightClick, min = 60, step = 60)
            }

            else -> return
        }
        save(player, region, updated)
    }

    /** 队伍槽位与 50/51 在战斗玩法下另有含义；先按玩法分派，再走通用数值逻辑。 */
    private fun combatSlot(
        values: Map<String, String>,
        modeType: String,
        slot: Int,
        rightClick: Boolean,
    ): Map<String, String>? = when {
        slot == 24 && modeType.equals("union_war", ignoreCase = true) -> LinkedHashMap(values).apply {
            this["diplomacy"] = if (this["diplomacy"].equals("enemy-only", ignoreCase = true)) "agreed" else "enemy-only"
        }

        slot == 50 && modeType.equals("dual_pvp", ignoreCase = true) -> LinkedHashMap(values).apply {
            val bestOf = this["best-of"]?.toIntOrNull() ?: 1
            if (bestOf >= 3) this["best-of"] = "1" else this["best-of"] = "3"
        }

        slot == 50 && modeType.equals("union_war", ignoreCase = true) ->
            GuiValues.adjustInt(values, "timeout-seconds", 600, rightClick, min = 60, step = 60)

        slot == 51 && modeType.equals("union_war", ignoreCase = true) -> LinkedHashMap(values).apply {
            remove("spawn-points")
            remove("spawn-points-b")
        }

        else -> null
    }

    private fun appendSpawn(values: Map<String, String>, key: String, player: Player): Map<String, String> =
        LinkedHashMap(values).apply {
            val existing = this[key].orEmpty()
            val here = GuiValues.formatLocation(player.location)
            this[key] = if (existing.isBlank()) here else "$existing;$here"
        }

    private fun appendCheckpoint(values: Map<String, String>, modeType: String, player: Player): Map<String, String> =
        LinkedHashMap(values).apply {
            val existing = this["checkpoints"].orEmpty()
            val here = GuiValues.formatLocation(player.location)
            this["checkpoints"] = if (existing.isBlank()) here else "$existing;$here"
            val existingVehicles = this["checkpoint-vehicles"].orEmpty()
            val checkpointVehicle = this["vehicle"] ?: GuiValues.defaultVehicle(modeType)
            this["checkpoint-vehicles"] =
                if (existingVehicles.isBlank()) checkpointVehicle else "$existingVehicles;$checkpointVehicle"
        }

    private fun save(player: Player, region: RegionDefinition, values: Map<String, String>) {
        val mode = region.mode ?: ModeConfig("free_event")
        gui.saveAndReopen(player, region.copy(mode = mode.copy(values = values))) { open(player, region.id) }
    }

    /** 名单存的是 UUID，展示时换回名字——否则这一格就是一串没人看得懂的十六进制。 */
    private fun judgeItem(viewer: Player, values: Map<String, String>): ItemStack {
        val judges = GuiValues.parseJudges(values)
        val names = judges.map { id ->
            Bukkit.getOfflinePlayer(id).name ?: id.toString()
        }
        return text.item(
            viewer,
            Material.NAME_TAG,
            "gui.mode.judges",
            mapOf("value" to names.joinToString(", ").ifBlank { text.text(viewer, "gui.common.none") }),
        )
    }

    private fun promptJudge(player: Player, region: RegionDefinition) {
        gui.promptLine(player, "gui.prompt.judge") { raw ->
            val name = raw.trim()
            val mode = region.mode ?: ModeConfig("free_event")
            if (name.equals("clear", ignoreCase = true)) {
                return@promptLine save(player, region, GuiValues.clearJudges(mode.values))
            }
            val target = resolvePlayer(name)
            if (target == null) {
                text.send(player, "gui.mode.judge-unknown", mapOf("name" to name))
                return@promptLine open(player, region.id)
            }
            save(player, region, GuiValues.toggleJudge(mode.values, target))
        }
    }

    /**
     * 在线玩家优先，否则只认服务器已经缓存过的档案。
     *
     * 刻意不用 `Bukkit.getOfflinePlayer(name)`：那个方法对没见过的名字会去请求 Mojang API，
     * 既阻塞主线程，又会给打错的名字凭空造出一个 UUID——那意味着把发令权发给一个不存在的人。
     */
    private fun resolvePlayer(name: String): UUID? {
        if (name.isBlank()) return null
        Bukkit.getPlayerExact(name)?.let { return it.uniqueId }
        val cached = Bukkit.getOfflinePlayerIfCached(name) ?: return null
        return cached.uniqueId
    }

    private fun promptModeValue(player: Player, region: RegionDefinition) {
        gui.promptLine(player, "gui.prompt.mode-value") { raw ->
            val args = GuiValues.splitArgs(raw)
            val mode = region.mode ?: ModeConfig("free_event")
            val values = LinkedHashMap(mode.values)
            if (args.size == 2 && args[0].equals("clear", ignoreCase = true)) {
                values.remove(args[1])
            } else {
                val pair = args.firstOrNull()?.let { GuiValues.parsePair(it) }
                if (pair == null) {
                    text.send(player, "gui.mode.key-value-required")
                    open(player, region.id)
                    return@promptLine
                }
                values[pair.first] = pair.second
            }
            gui.saveAndReopen(player, region.copy(mode = mode.copy(values = values))) { open(player, region.id) }
        }
    }
}
