package org.cubexmc.regions.gui

import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.cubexmc.regions.gui.GuiText.Ui
import org.cubexmc.regions.model.RegionDefinition
import org.cubexmc.regions.model.ValidationSeverity

/** The publish confirmation page: draft diff, dependencies, blocking issues and effective rules. */
internal class RegionPublishMenu(private val gui: RegionsGui) {
    private val plugin get() = gui.plugin
    private val text get() = gui.text
    private val items get() = gui.items
    private val modeMenu = RegionModeMenu(gui)
    private val rulesMenu = RegionRuleMenu(gui)

    fun open(player: Player, regionId: String) {
        val draft = plugin.publishing().draft(regionId) ?: return gui.openDetail(player, regionId)
        val report = plugin.publishing().previewReport(player, regionId) ?: return gui.openDetail(player, regionId)
        val changes = report.changes
        val issues = report.issues
        val errors = issues.count { it.severity == ValidationSeverity.ERROR }
        val warnings = issues.size - errors
        val inventory = Bukkit.createInventory(
            RegionsHolder(View.PUBLISH_PREVIEW, regionId, reviewedRevision = draft.revision),
            54,
            text.component(player, "gui.publish.title", mapOf("id" to regionId)),
        )

        changes.take(36).forEachIndexed { index, change ->
            val material = when {
                change.before == null -> Material.LIME_DYE
                change.after == null -> Material.RED_DYE
                else -> Material.YELLOW_DYE
            }
            inventory.setItem(
                index,
                text.item(
                    player,
                    material,
                    "gui.publish.change",
                    mapOf(
                        "path" to change.path,
                        "before" to previewValue(player, change.before),
                        "after" to previewValue(player, change.after),
                    ),
                ),
            )
        }
        if (changes.isEmpty()) {
            inventory.setItem(22, text.item(player, Material.PAPER, "gui.publish.no-changes"))
        }

        issues.take(8).forEachIndexed { index, issue ->
            inventory.setItem(36 + index, issueEntry(player, issues, index))
        }
        inventory.setItem(45, text.item(player, GuiIcons.BACK, "gui.publish.back"))
        inventory.setItem(46, dependencyItem(player, report))
        inventory.setItem(47, issueItem(player, issues, errors, warnings, regionId))
        inventory.setItem(48, effectiveRulesItem(player, report))
        inventory.setItem(49, confirmItem(player, errors, changes.size, draft.revision))
        inventory.setItem(
            50,
            text.item(
                player,
                Material.OBSERVER,
                "gui.publish.overlaps",
                mapOf("count" to report.resolution.orderedRegions.size.toString()),
                extraLore = report.resolution.orderedRegions.map {
                    text.text(
                        player,
                        "gui.publish.overlap-line",
                        mapOf("id" to it.id, "priority" to it.priority.toString(), "source" to it.source.type),
                    )
                },
            ),
        )
        player.openInventory(inventory)
    }

        /** M2.3：每条 issue 一个独立条目，点击跳到对应设置页（"立即修复"），修完可从模式页返回发布页。 */
        private fun issueEntry(
            viewer: Player,
            issues: List<org.cubexmc.regions.model.ValidationIssue>,
            index: Int,
        ): org.bukkit.inventory.ItemStack {
            val issue = issues[index]
            val target = PublishFixTarget.from(issue.code)
            val lore = mutableListOf(
                "${if (issue.severity == ValidationSeverity.ERROR) Ui.RED else Ui.YELLOW}" +
                    text.issueLine(viewer, issue.code, issue.args, issue.fieldPath, issue.message),
            )
            lore += if (target == null) {
                text.lore(viewer, "gui.publish.fix-hint.none")
            } else {
                text.lore(viewer, "gui.publish.fix-hint.goto", mapOf("target" to text.label(viewer, "gui.publish.fix-target." + target.name.lowercase(), target.name.lowercase())))
            }
            return text.named(
                if (issue.severity == ValidationSeverity.ERROR) Material.RED_CONCRETE else Material.YELLOW_CONCRETE,
                text.text(viewer, "gui.publish.issue-name", mapOf("severity" to text.severityLabel(viewer, issue.severity))),
                lore,
            )
        }

    private fun dependencyItem(
        viewer: Player,
        report: org.cubexmc.regions.service.PublishingPreview,
    ): org.bukkit.inventory.ItemStack {
        val satisfied = report.dependencies.all { it.available }
        val lore = if (report.dependencies.isEmpty()) {
            text.lore(viewer, "gui.publish.dependencies.none")
        } else {
            report.dependencies.map {
                "${if (it.available) Ui.GREEN else Ui.RED}${it.id}: ${text.label(viewer, "labels.dependency." + it.detail, it.detail)}"
            }
        }
        return text.named(
            if (satisfied) Material.ENDER_CHEST else Material.TRAPPED_CHEST,
            text.text(viewer, if (satisfied) "gui.publish.dependencies.ok" else "gui.publish.dependencies.missing"),
            lore,
        )
    }

    private fun issueItem(
        viewer: Player,
        issues: List<org.cubexmc.regions.model.ValidationIssue>,
        errors: Int,
        warnings: Int,
        regionId: String,
    ): org.bukkit.inventory.ItemStack {
        val material = when {
            errors > 0 -> Material.REDSTONE_BLOCK
            warnings > 0 -> Material.YELLOW_CONCRETE
            else -> Material.LIME_CONCRETE
        }
        val name = if (errors > 0) {
            text.text(viewer, "gui.publish.blocked", mapOf("errors" to errors.toString()))
        } else {
            text.text(viewer, "gui.publish.validated", mapOf("warnings" to warnings.toString()))
        }
        val lore = issues.take(5).map { issue ->
            "${if (issue.severity == ValidationSeverity.ERROR) Ui.RED else Ui.YELLOW}" +
                text.issueLine(viewer, issue.code, issue.args, issue.fieldPath, issue.message)
        } + if (issues.size > 5) {
            listOf(text.text(viewer, "gui.publish.more-issues", mapOf("count" to (issues.size - 5).toString(), "id" to regionId)))
        } else {
            emptyList()
        }
        return text.named(material, name, lore)
    }

    private fun effectiveRulesItem(
        viewer: Player,
        report: org.cubexmc.regions.service.PublishingPreview,
    ): org.bukkit.inventory.ItemStack {
        val resolution = report.resolution
        val none = text.text(viewer, "gui.common.none")
        val displayedMode = resolution.primaryModeRegion ?: resolution.orderedRegions.firstOrNull { it.mode != null }
        return text.item(
            viewer,
            Material.COMPARATOR,
            "gui.publish.effective",
            mapOf(
                "mode" to (displayedMode?.let { "${it.id}:${it.mode?.type}" } ?: none),
                "trigger" to (resolution.primaryTriggerRegion?.id ?: none),
                "flags" to resolution.flags.values
                    .joinToString { "${it.key}=${it.config.value}@${it.sourceRegionId}" }
                    .ifBlank { none },
                "effects" to resolution.effects
                    .joinToString { "${it.config.type}@${it.sourceRegionId}" }
                    .ifBlank { none },
            ),
        )
    }

    private fun confirmItem(
        viewer: Player,
        errors: Int,
        changeCount: Int,
        revision: Long,
    ): org.bukkit.inventory.ItemStack {
        val truncated = changeCount > 36
        return text.item(
            viewer,
            if (errors > 0) GuiIcons.BLOCKED else Material.EMERALD_BLOCK,
            if (errors > 0) "gui.publish.fix-first" else "gui.publish.confirm",
            mapOf("revision" to revision.toString(), "count" to changeCount.toString()),
            extraLore = listOf(text.text(viewer, if (truncated) "gui.publish.truncated" else "gui.publish.recheck")),
        )
    }

    fun click(player: Player, holder: RegionsHolder, slot: Int) {
        val regionId = holder.regionId ?: return
        // M2.3"立即修复"：issue 条目跳到对应设置页；模式页会带"返回发布页"。
        if (slot in 36..43) {
            val report = plugin.publishing().previewReport(player, regionId) ?: return gui.openDetail(player, regionId)
            val issue = report.issues.getOrNull(slot - 36) ?: return
            when (val target = PublishFixTarget.from(issue.code)) {
                PublishFixTarget.MODE -> modeMenu.open(player, regionId, returnToPublish = true)
                PublishFixTarget.SOURCE -> gui.openSource(player, regionId)
                PublishFixTarget.RULES -> rulesMenu.openFlags(player, regionId, returnToPublish = true)
                PublishFixTarget.EFFECTS -> rulesMenu.openEffects(player, regionId, returnToPublish = true)
                PublishFixTarget.TRIGGERS -> rulesMenu.openTriggers(player, regionId, returnToPublish = true)
                null -> Unit
            }
            return
        }
        when (slot) {
            45 -> gui.openDetail(player, regionId)
            49 -> {
                // 确认发布时核对草稿 revision（PLAN.md §5.2）：预览之后被别人改过就拒绝并刷新。
                val result = plugin.publishing().publish(player, regionId, expectedRevision = holder.reviewedRevision)
                if (result.success) {
                    text.send(player, "gui.publish.ok", mapOf("id" to regionId))
                } else {
                    text.send(player, "gui.publish.failed", mapOf("reason" to text.resultReason(player, result.code, result.args, result.reason)))
                }
                gui.openDetail(player, regionId)
            }
        }
    }

    fun sendRevisionHistory(player: Player, region: RegionDefinition) {
        val revisions = plugin.publishing().history(region.id)
        text.send(player, "gui.history.header", mapOf("id" to region.id, "count" to revisions.size.toString()))
        if (revisions.isEmpty()) text.send(player, "gui.history.empty")
        revisions.take(20).forEach { snapshot ->
            text.send(
                player,
                "gui.history.line",
                mapOf(
                    "revision" to snapshot.revision.toString(),
                    "name" to snapshot.name,
                    "mode" to (snapshot.mode?.type ?: text.text(player, "gui.common.none")),
                ),
            )
        }
        text.send(player, "gui.history.rollback-hint", mapOf("id" to region.id))
    }

    fun sendValidation(player: Player, region: RegionDefinition) {
        val issues = plugin.validation().validate(region)
        if (issues.isEmpty()) {
            plugin.lang().send(player, "validate-ok")
            return
        }
        plugin.lang().send(player, "validate-header", mapOf("count" to issues.size.toString()))
        for (issue in issues) {
            plugin.lang().send(
                player,
                "validate-line",
                mapOf(
                    "id" to issue.regionId,
                    "severity" to text.severityLabel(player, issue.severity),
                    "message" to text.issueLine(player, issue.code, issue.args, issue.fieldPath, issue.message),
                ),
            )
        }
    }

    private fun previewValue(viewer: Player, value: String?): String {
        if (value == null) return text.text(viewer, "gui.common.absent")
        return if (value.length <= 80) value else value.take(77) + "..."
    }
}
