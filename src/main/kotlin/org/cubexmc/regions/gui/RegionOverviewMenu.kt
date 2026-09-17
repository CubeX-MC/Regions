package org.cubexmc.regions.gui

import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.cubexmc.regions.mode.gameStatusLine
import org.cubexmc.regions.model.RegionDefinition
import org.cubexmc.regions.model.RegionLifecycle
import org.cubexmc.regions.model.ValidationSeverity
import org.cubexmc.regions.service.RegionOverlapResolver

/** The region list, the per-region hub, and the source binding page. */
internal class RegionOverviewMenu(private val gui: RegionsGui) {
    private val plugin get() = gui.plugin
    private val text get() = gui.text
    private val items get() = gui.items

    fun openMain(player: Player) {
        if (!gui.canEnterManagement(player)) return
        val regions = plugin.authority().visibleRegions(player, plugin.regions().all())
            .map { gui.editable(it.id) ?: it }
            .sortedWith(RegionOverlapResolver.REGION_ORDER)
        val inventory = Bukkit.createInventory(RegionsHolder(View.MAIN), 54, text.component(player, "gui.main.title"))
        for ((index, region) in regions.take(45).withIndex()) {
            inventory.setItem(index, items.region(player, region))
        }
        inventory.setItem(45, text.item(player, Material.WRITABLE_BOOK, "gui.main.create"))
        if (plugin.authority().isSuperAdmin(player)) {
            inventory.setItem(47, text.item(player, Material.COMPASS, "gui.main.reload"))
        }
        inventory.setItem(49, text.item(player, Material.MAP, "gui.main.count", mapOf("count" to regions.size.toString())))
        inventory.setItem(51, text.item(player, Material.SPYGLASS, "gui.main.doctor"))
        inventory.setItem(53, text.named(GuiIcons.CLOSE, text.text(player, "gui.common.close")))
        player.openInventory(inventory)
    }

    fun clickMain(player: Player, item: ItemStack?, slot: Int) {
        when (slot) {
            53 -> return player.closeInventory()
            45 -> return gui.creation.openModePicker(player)
            47 -> return reload(player)
            51 -> return runDoctor(player)
        }
        val meta = item?.itemMeta ?: return
        val regionId = meta.persistentDataContainer.get(gui.keys.region, PersistentDataType.STRING) ?: return
        openDetail(player, regionId)
    }

    private fun reload(player: Player) {
        if (!gui.allow(player, plugin.authority().canUseGlobalAdministration(player))) return
        plugin.reloadRegions()
        text.send(player, "gui.main.reloaded")
        openMain(player)
    }

    private fun runDoctor(player: Player) {
        val visible = plugin.authority().visibleRegions(player, plugin.regions().all())
            .map { gui.editable(it.id) ?: it }
        val issues = plugin.validation().validateAll(visible)
        if (issues.isEmpty()) {
            text.send(player, "gui.doctor.clean")
            return
        }
        text.send(player, "gui.doctor.found", mapOf("count" to issues.size.toString()))
        for (issue in issues.take(8)) {
            text.send(
                player,
                "gui.doctor.line",
                mapOf(
                    "id" to issue.regionId,
                    "severity" to text.severityLabel(player, issue.severity),
                    "message" to text.issueLine(player, issue.code, issue.args, issue.fieldPath, issue.message),
                ),
            )
        }
    }

    /**
     * 基础页（PLAN.md §5.2）：只突出场地主日常要用的 5 个操作——玩法设置（含装备与人数）、
     * 场地点位、检查并发布、比赛管理、隔离试运行。规则组合、临时效果、触发动作、原始 ID、完整
     * diff、历史、启停与删除都收进 [openDetailAdvanced]，普通页面不再一次摆出十几个入口。
     */
    fun openDetail(player: Player, regionId: String) {
        val region = gui.editable(regionId) ?: return openMain(player)
        if (!gui.canManageRegion(player, region)) return
        val draft = plugin.publishing().draft(region.id)
        val inventory = Bukkit.createInventory(
            RegionsHolder(View.DETAIL, region.id),
            54,
            text.component(player, "gui.detail.title", mapOf("id" to region.id)),
        )
        inventory.setItem(RegionDetailLayout.INFO_SLOT, items.region(player, region))
        inventory.setItem(
            RegionDetailLayout.BASIC_OPERATIONS.keys.elementAt(0),
            text.item(
                player,
                Material.DIAMOND_SWORD,
                "gui.detail.mode",
                mapOf("mode" to plugin.lang().label("labels.mode." + (region.mode?.type ?: ""), region.mode?.type ?: text.text(player, "gui.common.none"))),
            ),
        )
        inventory.setItem(RegionDetailLayout.BASIC_OPERATIONS.keys.elementAt(1), text.item(player, Material.ENDER_EYE, "gui.detail.source", mapOf("source" to region.source.describe())))
        // 校验结果直接写进"检查并发布"的说明里，避免基础页再摆一个校验按钮。
        val blocking = if (draft != null) {
            plugin.publishing().publishingIssues(player, draft).count { it.severity == ValidationSeverity.ERROR }
        } else {
            0
        }
        inventory.setItem(
            RegionDetailLayout.BASIC_OPERATIONS.keys.elementAt(2),
            text.item(
                player,
                if (draft == null) GuiIcons.BLOCKED else Material.EMERALD,
                "gui.detail.publish",
                mapOf("revision" to region.revision.toString(), "errors" to blocking.toString()),
                extraLore = if (draft == null) text.lore(player, "gui.detail.publish.no-draft") else emptyList(),
            ),
        )
        inventory.setItem(RegionDetailLayout.BASIC_OPERATIONS.keys.elementAt(3), text.item(player, Material.BELL, "gui.detail.manage", mapOf("mode" to (region.mode?.type ?: "-"))))
        val trialActive = plugin.trials().active(player.uniqueId)?.regionId == region.id
        inventory.setItem(
            RegionDetailLayout.BASIC_OPERATIONS.keys.elementAt(4),
            text.item(
                player,
                if (trialActive) Material.REDSTONE_BLOCK else Material.SPYGLASS,
                when {
                    trialActive -> "gui.detail.trial-stop"
                    draft == null -> "gui.detail.trial-no-draft"
                    else -> "gui.detail.trial-start"
                },
            ),
        )
        inventory.setItem(RegionDetailLayout.ADVANCED_SLOT, text.item(player, Material.COMPARATOR, "gui.detail.advanced"))
        inventory.setItem(RegionDetailLayout.BACK_SLOT, items.back(player))
        player.openInventory(inventory)
    }

    fun clickDetail(player: Player, regionId: String, slot: Int) {
        val region = gui.editable(regionId) ?: return openMain(player)
        when (slot) {
            RegionDetailLayout.BASIC_OPERATIONS.keys.elementAt(0) -> gui.openMode(player, region.id)
            RegionDetailLayout.BASIC_OPERATIONS.keys.elementAt(1) -> openSource(player, region.id)
            RegionDetailLayout.BASIC_OPERATIONS.keys.elementAt(2) -> if (plugin.publishing().draft(region.id) == null) {
                text.send(player, "gui.detail.publish.no-draft")
            } else {
                gui.openPublishPreview(player, region.id)
            }

            RegionDetailLayout.BASIC_OPERATIONS.keys.elementAt(3) -> sendMatchSummary(player, region)
            RegionDetailLayout.BASIC_OPERATIONS.keys.elementAt(4) -> if (plugin.trials().active(player.uniqueId)?.regionId == region.id) {
                toggleTrial(player, region)
            } else if (plugin.publishing().draft(region.id) == null) {
                text.send(player, "gui.detail.trial-no-draft")
            } else {
                toggleTrial(player, region)
            }

            RegionDetailLayout.BACK_SLOT -> openMain(player)
            RegionDetailLayout.ADVANCED_SLOT -> openDetailAdvanced(player, region.id)
        }
    }

    /**
     * 高级页（PLAN.md §5.2）：规则组合、临时效果、触发动作、应用模板、原始 ID 与完整 diff、
     * 历史版本，以及启用停用、清理、撤回发布与删除这些不常用但必要的入口。
     */
    fun openDetailAdvanced(player: Player, regionId: String) {
        val region = gui.editable(regionId) ?: return openMain(player)
        if (!gui.canManageRegion(player, region)) return
        val draft = plugin.publishing().draft(region.id)
        val inventory = Bukkit.createInventory(
            RegionsHolder(View.DETAIL_ADVANCED, region.id),
            54,
            text.component(player, "gui.advanced.title", mapOf("id" to region.id)),
        )
        inventory.setItem(4, items.region(player, region))
        inventory.setItem(19, text.item(player, Material.OAK_SIGN, "gui.detail.flags", mapOf("count" to region.flags.size.toString())))
        inventory.setItem(20, text.item(player, Material.BLAZE_POWDER, "gui.detail.effects", mapOf("count" to region.effects.size.toString())))
        inventory.setItem(
            21,
            text.item(player, GuiIcons.TRIGGER, "gui.detail.triggers", mapOf("count" to region.triggers.values.sumOf { it.size }.toString())),
        )
        inventory.setItem(22, text.item(player, Material.KNOWLEDGE_BOOK, "gui.detail.apply-template"))
        if (draft != null) {
            inventory.setItem(
                23,
                text.item(player, Material.WRITABLE_BOOK, "gui.detail.preview-diff", mapOf("revision" to draft.revision.toString())),
            )
        }
        inventory.setItem(
            24,
            text.item(player, Material.BOOK, "gui.detail.history", mapOf("count" to plugin.publishing().history(region.id).size.toString())),
        )
        inventory.setItem(
            30,
            text.named(
                if (region.enabled) Material.REDSTONE_TORCH else Material.LEVER,
                text.text(player, if (region.enabled) "gui.detail.disable" else "gui.detail.enable"),
            ),
        )
        inventory.setItem(31, text.item(player, Material.MILK_BUCKET, "gui.detail.cleanup"))
        if (plugin.regions().find(region.id)?.lifecycle == RegionLifecycle.PUBLISHED) {
            inventory.setItem(32, text.item(player, Material.PAPER, "gui.detail.withdraw"))
        }
        inventory.setItem(40, text.item(player, Material.LAVA_BUCKET, "gui.detail.delete"))
        inventory.setItem(34, text.item(player, GuiIcons.BACK, "gui.advanced.back"))
        player.openInventory(inventory)
    }

    fun clickDetailAdvanced(player: Player, regionId: String, slot: Int) {
        val region = gui.editable(regionId) ?: return openMain(player)
        when (slot) {
            19 -> gui.openFlags(player, region.id)
            20 -> gui.openEffects(player, region.id)
            21 -> gui.openTriggers(player, region.id)
            22 -> gui.openTemplatesForRegion(player, region.id)
            23 -> gui.openPublishPreview(player, region.id)
            24 -> gui.sendRevisionHistory(player, region)
            30 -> gui.saveAndReopen(player, region.copy(enabled = !region.enabled)) { openDetailAdvanced(player, regionId) }
            31 -> {
                val count = plugin.sessions().cleanup(player, "gui-cleanup")
                plugin.lang().send(player, "cleanup-done", mapOf("player" to player.name, "count" to count.toString()))
            }
            32 -> withdraw(player, region)
            34 -> openDetail(player, regionId)
            40 -> promptDelete(player, region)
        }
    }

    /**
     * 比赛管理：把状态、名单、结果与开停赛入口一次性报给场地主。
     *
     * 开赛与强制结束走既有命令（它们各自有权限与两步确认），这里只做汇总，不再造一套平行入口。
     */
    private fun sendMatchSummary(player: Player, region: RegionDefinition) {
        val status = when {
            plugin.raceModes().isRaceMode(region) -> plugin.raceModes().status(region.id)
            plugin.roundModes().isRoundMode(region) -> plugin.roundModes().status(region.id)
            else -> plugin.combatModes().status(region.id)
        }
        plugin.lang().sendPlain(
            player,
            "gui.detail.manage-status",
            mapOf("id" to region.id, "state" to plugin.gameStatusLine(player, status)),
        )
        val participants = if (plugin.combatModes().isCombatMode(region)) {
            plugin.combatModes().participants(region.id)
        } else {
            emptyList()
        }
        if (participants.isNotEmpty()) {
            plugin.lang().sendPlain(
                player,
                "gui.detail.manage-roster",
                mapOf(
                    "count" to participants.size.toString(),
                    "players" to participants.joinToString(", ") { "${it.name}(${it.state.name.lowercase()})" },
                ),
            )
        }
        val result = plugin.combatModes().result(region.id)
        if (result != null) {
            plugin.lang().sendPlain(
                player,
                "gui.detail.manage-result",
                mapOf(
                    "outcome" to plugin.lang().label("labels.outcome.${result.outcome.name.lowercase()}", result.outcome.name.lowercase()),
                    "reason" to plugin.lang().label(result.reasonKey, result.reasonKey),
                ),
            )
        }
        plugin.lang().sendPlain(player, "gui.detail.manage-hint", mapOf("id" to region.id))
        openDetail(player, region.id)
    }

    private fun withdraw(player: Player, region: RegionDefinition) {
        val result = plugin.publishing().withdraw(player, region.id)
        if (result.success) {
            text.send(player, "gui.detail.withdrawn", mapOf("id" to region.id))
        } else {
            text.send(player, "gui.detail.withdraw-failed", mapOf("reason" to text.resultReason(player, result.code, result.args, result.reason)))
        }
        openDetail(player, region.id)
    }

    private fun toggleTrial(player: Player, region: RegionDefinition) {
        val active = plugin.trials().active(player.uniqueId)?.regionId == region.id
        val result = if (active) plugin.trials().stop(player, "gui-stop") else plugin.trials().start(player, region.id)
        if (result.success) {
            text.send(player, if (active) "gui.trial.stopped" else "gui.trial.started")
        } else {
            text.send(player, "gui.trial.failed", mapOf("reason" to text.resultReason(player, result.code, result.args, result.reason)))
        }
        openDetail(player, region.id)
    }

    private fun promptDelete(player: Player, region: RegionDefinition) {
        gui.promptLine(player, "gui.prompt.delete", mapOf("id" to region.id)) { raw ->
            if (!raw.trim().equals(region.id, ignoreCase = true)) {
                text.send(player, "gui.delete.cancelled")
                openDetail(player, region.id)
                return@promptLine
            }
            if (!gui.canManageRegion(player, region)) return@promptLine
            plugin.sessions().cleanupRegionAll(region.id, "gui-delete-${region.id}")
            val result = plugin.regions().remove(region.id)
            if (!result.success) {
                text.send(player, "gui.delete.failed", mapOf("reason" to text.resultReason(player, result.code, result.args, result.reason)))
            } else {
                plugin.audit().record(player, region.id, "region.remove", "gui")
                text.send(player, "gui.delete.ok", mapOf("id" to region.id))
            }
            openMain(player)
        }
    }

    fun openSource(player: Player, regionId: String) {
        val region = gui.editable(regionId) ?: return openMain(player)
        val inventory = Bukkit.createInventory(
            RegionsHolder(View.SOURCE, region.id),
            27,
            text.component(player, "gui.source.title", mapOf("id" to region.id)),
        )
        inventory.setItem(4, items.region(player, region))
        inventory.setItem(10, text.item(player, Material.GRASS_BLOCK, "gui.source.lands", mapOf("source" to region.source.describe())))
        if (plugin.authority().isSuperAdmin(player)) {
            inventory.setItem(12, text.item(player, Material.STONE_AXE, "gui.source.cuboid"))
        }
        inventory.setItem(14, text.item(player, Material.NAME_TAG, "gui.source.rename", mapOf("name" to region.name)))
        inventory.setItem(16, text.item(player, Material.COMPARATOR, "gui.source.priority", mapOf("priority" to region.priority.toString())))
        inventory.setItem(22, items.back(player))
        player.openInventory(inventory)
    }

    fun clickSource(player: Player, regionId: String, slot: Int, rightClick: Boolean) {
        val region = gui.editable(regionId) ?: return openMain(player)
        when (slot) {
            10 -> gui.openOwnedAreas(player, OwnedAreaContext(OwnedAreaPurpose.BIND, region.id, region.name))
            12 -> if (rightClick) {
                promptBindCuboid(player, region)
            } else {
                gui.saveAndReopen(player, region.copy(source = GuiValues.cuboidFromCurrentChunk(player, region.id))) {
                    openSource(player, region.id)
                }
            }
            14 -> promptRename(player, region)
            16 -> gui.saveAndReopen(player, region.copy(priority = region.priority + if (rightClick) -1 else 1)) {
                openSource(player, region.id)
            }
            22 -> openDetail(player, regionId)
        }
    }

    private fun promptBindCuboid(player: Player, region: RegionDefinition) {
        gui.promptLine(player, "gui.prompt.cuboid") { raw ->
            val parts = raw.split(',', ' ', ';').map { it.trim() }.filter { it.isNotBlank() }
            if (parts.size != 7 || parts.drop(1).any { it.toDoubleOrNull() == null }) {
                text.send(player, "gui.source.cuboid-format")
                openSource(player, region.id)
                return@promptLine
            }
            val source = org.cubexmc.regions.model.RegionSourceRef(
                "cuboid",
                linkedMapOf(
                    "id" to region.id,
                    "name" to region.name,
                    "world" to parts[0],
                    "min-x" to parts[1],
                    "min-y" to parts[2],
                    "min-z" to parts[3],
                    "max-x" to parts[4],
                    "max-y" to parts[5],
                    "max-z" to parts[6],
                ),
            )
            gui.saveAndReopen(
                player,
                region.copy(source = source, ownerPolicy = org.cubexmc.regions.model.OwnerPolicy.ADMIN),
            ) { openSource(player, region.id) }
        }
    }

    private fun promptRename(player: Player, region: RegionDefinition) {
        gui.promptLine(player, "gui.prompt.rename") { raw ->
            val name = raw.trim()
            if (name.isBlank()) {
                text.send(player, "gui.source.name-blank")
                openSource(player, region.id)
                return@promptLine
            }
            gui.saveAndReopen(player, region.copy(name = name)) { openSource(player, region.id) }
        }
    }
}

/**
 * 详情页布局（PLAN.md §5.2：基础页最多突出 5 个操作，高级项收进高级页）。
 *
 * 槽位与标签写成常数而不是散落在渲染代码里，是为了让"基础页只有 5 个操作"这条约定**可被测试**：
 * [RegionDetailLayoutTest] 断言 [BASIC_OPERATIONS] 恰好 5 项、槽位不重复，并且每个标签键在
 * 中英两个语言文件里都存在。新增第 6 个基础操作会直接让用例失败。
 */
internal object RegionDetailLayout {

    /** 基础页的 5 个操作：槽位 → 标签键。 */
    val BASIC_OPERATIONS: Map<Int, String> = linkedMapOf(
        20 to "gui.detail.mode",
        21 to "gui.detail.source",
        22 to "gui.detail.publish",
        23 to "gui.detail.manage",
        24 to "gui.detail.trial-start",
    )

    const val INFO_SLOT = 4
    const val BACK_SLOT = 34

    /** 高级页入口：是导航，不计入"5 个操作"。 */
    const val ADVANCED_SLOT = 40

    /** 高级页上的入口槽位 → 标签键（顺序即渲染顺序）。 */
    val ADVANCED_ENTRIES: Map<Int, String> = linkedMapOf(
        19 to "gui.detail.flags",
        20 to "gui.detail.effects",
        21 to "gui.detail.triggers",
        22 to "gui.detail.apply-template",
        23 to "gui.detail.preview-diff",
        24 to "gui.detail.history",
        30 to "gui.detail.enable",
        31 to "gui.detail.cleanup",
        32 to "gui.detail.withdraw",
        40 to "gui.detail.delete",
    )
}
