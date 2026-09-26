package org.cubexmc.regions.command

import io.papermc.paper.command.brigadier.BasicCommand
import io.papermc.paper.command.brigadier.CommandSourceStack
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.cubexmc.core.CubexCommandSuggestions
import org.cubexmc.regions.RegionsPlugin
import org.cubexmc.regions.capability.CapabilityKind
import org.cubexmc.regions.capability.CapabilityRisk
import org.cubexmc.regions.capability.ModeParameterSchema
import org.cubexmc.regions.match.JoinResult
import org.cubexmc.regions.match.SpectateResult
import org.cubexmc.regions.mode.gameStatusLine
import org.cubexmc.regions.model.EffectConfig
import org.cubexmc.regions.model.EffectCombination
import org.cubexmc.regions.model.EffectScope
import org.cubexmc.regions.model.FlagConfig
import org.cubexmc.regions.model.ModeConfig
import org.cubexmc.regions.model.OwnerPolicy
import org.cubexmc.regions.integration.UnionLookup
import org.cubexmc.regions.service.RegionLookup
import org.cubexmc.regions.model.RegionDefinition
import org.cubexmc.regions.model.RegionSourceRef
import org.cubexmc.regions.model.UnionRef
import org.cubexmc.regions.model.ValidationIssue
import org.cubexmc.regions.service.AuthorityDecision
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class RegionsCommand(private val plugin: RegionsPlugin) : BasicCommand {
    /** 待确认的强制结束：操作者+场地 → 首次执行时间。 */
    private val forceEndConfirmations: MutableMap<String, Long> = java.util.concurrent.ConcurrentHashMap()

    override fun execute(commandSourceStack: CommandSourceStack, args: Array<String>) {
        execute(commandSourceStack.sender, args)
    }

    private fun execute(sender: CommandSender, args: Array<String>): Boolean {
        if (args.isEmpty()) {
            // PLAN.md §5.1：/regions 对玩家打开活动大厅；管理入口是 /regions gui（大厅内有"我的场地"）。
            if (sender is Player) {
                plugin.gui().openLobby(sender)
                return true
            }
            sendHelp(sender)
            return true
        }

        if (args[0].equals("help", ignoreCase = true)) {
            sendHelp(sender)
            return true
        }

        if (args[0].equals("gui", ignoreCase = true)) {
            val player = sender as? Player
            if (player == null) {
                plugin.lang().send(sender, "player-only")
                return true
            }
            if (!canEnterManagement(player)) {
                return true
            }
            plugin.gui().openMain(player)
            return true
        }

        if (!canEnterManagementSilent(sender) && !args[0].equals("game", ignoreCase = true) && !args[0].equals("language", ignoreCase = true)) {
            plugin.lang().send(sender, "help-player")
            return true
        }

        return when (args[0].lowercase(Locale.ROOT)) {
            "list" -> list(sender)
            "create" -> create(sender, args)
            "remove", "delete" -> remove(sender, args)
            "enable" -> enabled(sender, args, true)
            "disable" -> enabled(sender, args, false)
            "bind" -> bind(sender, args)
            "mode" -> mode(sender, args)
            "flag" -> flag(sender, args)
            "effect" -> effect(sender, args)
            "trial" -> trial(sender, args)
            "preview" -> preview(sender, args)
            "publish" -> publish(sender, args)
            "withdraw", "unpublish" -> withdraw(sender, args)
            "history" -> history(sender, args)
            "rollback" -> rollback(sender, args)
            "archive" -> archive(sender, args)
            "freeze" -> freeze(sender, args)
            "unfreeze" -> unfreeze(sender, args)
            "audit" -> audit(sender, args)
            "game" -> game(sender, args)
            "reload" -> reload(sender)
            "validate" -> validate(sender, args)
            "inspect" -> inspect(sender, args)
            "cleanup" -> cleanup(sender, args)
            "doctor" -> doctor(sender)
            "language" -> language(sender, args)
            else -> {
                sendHelp(sender)
                true
            }
        }
    }

    private fun list(sender: CommandSender): Boolean {
        if (!canEnterManagement(sender)) {
            return true
        }
        val regions = plugin.authority().visibleRegions(sender, plugin.regions().all())
            .map { plugin.publishing().editable(it.id) ?: it }
        if (regions.isEmpty()) {
            plugin.lang().send(sender, "list-empty")
            return true
        }
        plugin.lang().send(sender, "list-header", mapOf("count" to regions.size.toString()))
        for (region in regions.sortedWith(compareByDescending<RegionDefinition> { it.priority }.thenBy { it.id })) {
            plugin.lang().sendRaw(
                sender,
                plugin.lang().message(
                    "list-line",
                    mapOf(
                        "id" to region.id,
                        "name" to region.name,
                        "enabled" to plugin.lang().message(if (region.enabled) "enabled" else "disabled"),
                        "source" to region.source.describe(),
                        "mode" to (region.mode?.type ?: "none"),
                        "lifecycle" to region.lifecycle.name.lowercase(Locale.ROOT),
                    ),
                ),
            )
        }
        return true
    }

    private fun create(sender: CommandSender, args: Array<String>): Boolean {
        if (!has(sender, "regions.region.create")) {
            return true
        }
        if (args.size < 3) {
            // M2.2：`/regions create` 无参数进入 4 阶段向导（自动 ID、玩法优先）；旧的手写 ID 语法保留。
            if (sender is Player && args.size == 1) {
                // 进行中的向导直接回到它所在的阶段（PLAN.md §5.2：关闭 GUI 后可继续）。
                val wizard = plugin.gui().wizardDrafts.get(sender.uniqueId)
                val draftId = wizard?.regionId
                if (wizard != null && draftId != null && plugin.publishing().draft(draftId) != null) {
                    plugin.gui().openWizardSettings(sender, draftId, backToMode = true)
                } else {
                    plugin.gui().creation.openModePicker(sender)
                }
                return true
            }
            plugin.lang().send(sender, "invalid-usage", mapOf("usage" to "/regions create <id> <name>"))
            return true
        }
        val id = args[1].lowercase(Locale.ROOT)
        if (plugin.regions().find(id) != null) {
            plugin.lang().sendPlain(sender, "gui.create.exists", mapOf("id" to id))
            return true
        }
        val landMarker = args.indexOfFirst { it.equals("--land", ignoreCase = true) }
        val source = if (landMarker >= 0) {
            val land = args.getOrNull(landMarker + 1)
            if (land.isNullOrBlank()) {
                plugin.lang().send(sender, "invalid-usage", mapOf("usage" to "/regions create <id> <name> --land <land> [area]"))
                return true
            }
            RegionSourceRef("lands", mapOf("land" to land, "area" to (args.getOrNull(landMarker + 2) ?: "default")))
        } else {
            RegionSourceRef("cuboid", mapOf("id" to id))
        }
        if (!allow(sender, plugin.authority().canCreate(sender, source))) {
            return true
        }
        val nameEnd = if (landMarker >= 0) landMarker else args.size
        val name = args.slice(2 until nameEnd).joinToString(" ").ifBlank { id }
        val region = withOwnerSnapshot(RegionDefinition(
            id = id,
            name = name,
            source = source,
            ownerPolicy = if (source.type == "lands") OwnerPolicy.LANDS_OWNER else OwnerPolicy.ADMIN,
            mode = ModeConfig("free_event"),
        ))
        val result = plugin.publishing().createDraft(sender, region)
        if (!result.success) {
            plugin.lang().sendPlain(sender, "command.region-failed", mapOf("reason" to plugin.lang().resultReason(result.code, result.args, result.reason)))
            return true
        }
        plugin.lang().sendPlain(sender, "command.region-created", mapOf("id" to id, "source" to source.describe()))
        return true
    }

    private fun remove(sender: CommandSender, args: Array<String>): Boolean {
        if (!has(sender, "regions.region.remove")) {
            return true
        }
        val region = requireRegion(sender, args, "/regions remove <id>") ?: return true
        if (!canManage(sender, region)) return true
        val result = plugin.regions().remove(region.id)
        if (!result.success) {
            plugin.lang().send(sender, "not-found", mapOf("id" to region.id))
            return true
        }
        plugin.audit().record(sender, region.id, "region.remove")
        plugin.lang().sendPlain(sender, "command.region-removed", mapOf("id" to region.id))
        return true
    }

    private fun enabled(sender: CommandSender, args: Array<String>, enabled: Boolean): Boolean {
        if (!has(sender, "regions.region.enable")) {
            return true
        }
        val region = requireEditableRegion(sender, args, "/regions ${args[0]} <id>") ?: return true
        if (!canManage(sender, region)) return true
        val result = plugin.publishing().saveDraft(sender, region.copy(enabled = enabled))
        if (!result.success) {
            plugin.lang().sendPlain(sender, "command.region-failed", mapOf("reason" to plugin.lang().resultReason(result.code, result.args, result.reason)))
            return true
        }
        plugin.audit().record(sender, region.id, if (enabled) "region.enable" else "region.disable")
        plugin.lang().sendPlain(
            sender,
            if (enabled) "command.region-enabled" else "command.region-disabled",
            mapOf("id" to region.id),
        )
        return true
    }

    private fun bind(sender: CommandSender, args: Array<String>): Boolean {
        if (!has(sender, "regions.region.bind")) {
            return true
        }
        val region = requireEditableRegion(sender, args, "/regions bind <id> <cuboid|lands> ...") ?: return true
        if (!canManage(sender, region)) return true
        if (args.size < 4) {
            plugin.lang().send(sender, "invalid-usage", mapOf("usage" to "/regions bind <id> <cuboid|lands> ..."))
            return true
        }
        val source = when (args[2].lowercase(Locale.ROOT)) {
            "lands" -> RegionSourceRef("lands", mapOf("land" to args[3], "area" to (args.getOrNull(4) ?: "default")))
            "cuboid" -> {
                if (args.size < 10) {
                    plugin.lang().send(sender, "invalid-usage", mapOf("usage" to "/regions bind <id> cuboid <world> <minX> <minY> <minZ> <maxX> <maxY> <maxZ>"))
                    return true
                }
                val keys = listOf("world", "min-x", "min-y", "min-z", "max-x", "max-y", "max-z")
                RegionSourceRef("cuboid", keys.zip(args.drop(3).take(7)).toMap())
            }
            else -> {
                plugin.lang().sendPlain(sender, "command.unknown-source", mapOf("input" to args[2]))
                return true
            }
        }
        if (!allow(sender, plugin.authority().canCreate(sender, source))) return true
        val result = plugin.publishing().saveDraft(sender, withOwnerSnapshot(region.copy(source = source)))
        if (!result.success) {
            plugin.lang().sendPlain(sender, "command.region-failed", mapOf("reason" to plugin.lang().resultReason(result.code, result.args, result.reason)))
            return true
        }
        plugin.audit().record(sender, region.id, "region.bind", details = mapOf("source" to source.describe()))
        plugin.lang().sendPlain(sender, "command.bound", mapOf("id" to region.id, "source" to source.describe()))
        return true
    }

    private fun flag(sender: CommandSender, args: Array<String>): Boolean {
        if (!has(sender, "regions.region.edit")) {
            return true
        }
        if (args.size < 5 || !args[1].equals("set", ignoreCase = true)) {
            plugin.lang().send(sender, "invalid-usage", mapOf("usage" to "/regions flag set <id> <flag> <allow|deny|pass> [key=value...]"))
            return true
        }
        val region = plugin.publishing().editable(args[2])
        if (region == null) {
            plugin.lang().send(sender, "not-found", mapOf("id" to args[2]))
            return true
        }
        if (!canManage(sender, region)) return true
        val key = args[3].lowercase(Locale.ROOT)
        val flags = LinkedHashMap(region.flags)
        flags[key] = FlagConfig(key, args[4].lowercase(Locale.ROOT), parsePairs(args, 5))
        val result = plugin.publishing().saveDraft(sender, region.copy(flags = flags))
        if (!result.success) {
            plugin.lang().sendPlain(sender, "command.region-failed", mapOf("reason" to plugin.lang().resultReason(result.code, result.args, result.reason)))
            return true
        }
        plugin.audit().record(sender, region.id, "region.flag.set", details = mapOf("flag" to key, "value" to args[4]))
        plugin.lang().sendPlain(
            sender,
            "command.flag-set",
            mapOf("id" to region.id, "flag" to key, "value" to args[4]),
        )
        return true
    }

    private fun mode(sender: CommandSender, args: Array<String>): Boolean {
        if (!has(sender, "regions.region.edit")) {
            return true
        }
        if (args.size < 4 || !args[1].equals("set", ignoreCase = true)) {
            plugin.lang().send(sender, "invalid-usage", mapOf("usage" to "/regions mode set <id> <${ModeParameterSchema.ALL_MODES.joinToString("|")}> [key=value...]"))
            return true
        }
        val region = plugin.publishing().editable(args[2])
        if (region == null) {
            plugin.lang().send(sender, "not-found", mapOf("id" to args[2]))
            return true
        }
        if (!canManage(sender, region)) return true
        val type = args[3].lowercase(Locale.ROOT)
        val result = plugin.publishing().saveDraft(sender, region.copy(mode = ModeConfig(type, parsePairs(args, 4))))
        if (!result.success) {
            plugin.lang().sendPlain(sender, "command.region-failed", mapOf("reason" to plugin.lang().resultReason(result.code, result.args, result.reason)))
            return true
        }
        plugin.audit().record(sender, region.id, "region.mode.set", details = mapOf("mode" to type))
        plugin.lang().sendPlain(sender, "command.mode-set", mapOf("id" to region.id, "mode" to type))
        return true
    }

    private fun effect(sender: CommandSender, args: Array<String>): Boolean {
        if (!has(sender, "regions.region.edit")) {
            return true
        }
        if (args.size < 4 || !args[1].equals("add", ignoreCase = true)) {
            plugin.lang().send(sender, "invalid-usage", mapOf("usage" to "/regions effect add <id> <effect> [key=value...]"))
            return true
        }
        val region = plugin.publishing().editable(args[2])
        if (region == null) {
            plugin.lang().send(sender, "not-found", mapOf("id" to args[2]))
            return true
        }
        if (!canManage(sender, region)) return true
        val values = parsePairs(args, 4)
        val scope = when (values.remove("scope")?.lowercase(Locale.ROOT)) {
            "timed" -> EffectScope.TIMED
            "until_mode_end", "until-mode-end" -> EffectScope.UNTIL_MODE_END
            else -> EffectScope.WHILE_INSIDE
        }
        val combination = when (values.remove("combination")?.lowercase(Locale.ROOT)) {
            "exclusive" -> EffectCombination.EXCLUSIVE
            "stack" -> EffectCombination.STACK
            "merge_by_type", "merge-by-type", "merge" -> EffectCombination.MERGE_BY_TYPE
            else -> EffectCombination.HIGHEST_PRIORITY
        }
        val effects = ArrayList(region.effects)
        effects.add(EffectConfig(args[3].lowercase(Locale.ROOT), scope, values, combination))
        val result = plugin.publishing().saveDraft(sender, region.copy(effects = effects))
        if (!result.success) {
            plugin.lang().sendPlain(sender, "command.region-failed", mapOf("reason" to plugin.lang().resultReason(result.code, result.args, result.reason)))
            return true
        }
        plugin.audit().record(sender, region.id, "region.effect.add", details = mapOf("effect" to args[3]))
        plugin.lang().sendPlain(sender, "command.effect-added", mapOf("id" to region.id, "effect" to args[3]))
        return true
    }

    private fun publish(sender: CommandSender, args: Array<String>): Boolean {
        if (!has(sender, "regions.region.publish")) return true
        val regionId = args.getOrNull(1)
        if (regionId == null) {
            plugin.lang().send(sender, "invalid-usage", mapOf("usage" to "/regions publish <id>"))
            return true
        }
        val result = plugin.publishing().publish(sender, regionId)
        if (result.success) {
            plugin.lang().sendPlain(sender, "command.published", mapOf("id" to regionId))
        } else {
            plugin.lang().sendPlain(sender, "command.publish-failed", mapOf("reason" to plugin.lang().resultReason(result.code, result.args, result.reason)))
        }
        return true
    }

    private fun trial(sender: CommandSender, args: Array<String>): Boolean {
        val player = sender as? Player
        if (player == null) {
            plugin.lang().send(sender, "player-only")
            return true
        }
        val regionId = args.getOrNull(1)
        val operation = args.getOrNull(2)?.lowercase(Locale.ROOT) ?: "start"
        if (regionId == null) {
            plugin.lang().send(sender, "invalid-usage", mapOf("usage" to "/regions trial <id> [start|stop]"))
            return true
        }
        val result = if (operation == "stop" || operation == "end") {
            plugin.trials().stop(player, "command-stop")
        } else {
            plugin.trials().start(player, regionId)
        }
        if (result.success) {
            val stopping = operation == "stop" || operation == "end"
            plugin.lang().sendPlain(player, if (stopping) "command.trial-stopped" else "command.trial-started")
        } else {
            plugin.lang().sendPlain(player, "command.trial-failed", mapOf("reason" to plugin.lang().resultReason(result.code, result.args, result.reason)))
        }
        return true
    }

    private fun preview(sender: CommandSender, args: Array<String>): Boolean {
        val regionId = args.getOrNull(1)
        if (regionId == null) {
            plugin.lang().send(sender, "invalid-usage", mapOf("usage" to "/regions preview <id>"))
            return true
        }
        val region = plugin.publishing().draft(regionId)
        if (region == null) {
            plugin.lang().sendPlain(sender, "command.preview-no-draft", mapOf("id" to regionId))
            return true
        }
        if (!allow(sender, plugin.authority().canView(sender, region))) return true
        val report = plugin.publishing().previewReport(sender, regionId) ?: return true
        val none = plugin.lang().message("gui.common.none")
        plugin.lang().sendPlain(
            sender,
            "command.preview-header",
            mapOf("id" to regionId, "revision" to region.revision.toString()),
        )
        plugin.lang().sendPlain(
            sender,
            "command.preview-summary",
            mapOf(
                "changes" to report.changes.size.toString(),
                "issues" to report.issues.size.toString(),
                "overlaps" to report.resolution.orderedRegions.size.toString(),
            ),
        )
        report.dependencies.forEach {
            plugin.lang().sendPlain(
                sender,
                if (it.available) "command.preview-dependency-ok" else "command.preview-dependency-missing",
                mapOf("id" to it.id, "detail" to plugin.lang().label("labels.dependency." + it.detail, it.detail)),
            )
        }
        val displayedMode = report.resolution.primaryModeRegion
            ?: report.resolution.orderedRegions.firstOrNull { it.mode != null }
        plugin.lang().sendPlain(
            sender,
            "command.preview-resolution-summary",
            mapOf(
                "mode" to (displayedMode?.let { "${it.id}:${it.mode?.type}" } ?: none),
                "trigger" to (report.resolution.primaryTriggerRegion?.id ?: none),
            ),
        )
        report.resolution.flags.values.take(12).forEach {
            plugin.lang().sendPlain(
                sender,
                "command.preview-flag-line",
                mapOf("flag" to it.key, "value" to it.config.value, "source" to it.sourceRegionId),
            )
        }
        report.resolution.effects.take(12).forEach {
            plugin.lang().sendPlain(
                sender,
                "command.preview-effect-line",
                mapOf(
                    "effect" to it.config.type,
                    "combination" to it.config.combination.name.lowercase(Locale.ROOT),
                    "source" to it.sourceRegionId,
                ),
            )
        }
        sendIssues(sender, report.issues)
        return true
    }

    private fun withdraw(sender: CommandSender, args: Array<String>): Boolean {
        if (!has(sender, "regions.region.publish")) return true
        val regionId = args.getOrNull(1)
        if (regionId == null) {
            plugin.lang().send(sender, "invalid-usage", mapOf("usage" to "/regions withdraw <id>"))
            return true
        }
        val result = plugin.publishing().withdraw(sender, regionId)
        if (result.success) {
            plugin.lang().sendPlain(sender, "command.withdrawn", mapOf("id" to regionId))
        } else {
            plugin.lang().sendPlain(sender, "command.withdraw-failed", mapOf("reason" to plugin.lang().resultReason(result.code, result.args, result.reason)))
        }
        return true
    }

    private fun history(sender: CommandSender, args: Array<String>): Boolean {
        if (!has(sender, "regions.region.rollback")) return true
        val region = requireEditableRegion(sender, args, "/regions history <id>") ?: return true
        if (!allow(sender, plugin.authority().canView(sender, region))) return true
        val revisions = plugin.publishing().history(region.id)
        plugin.lang().sendPlain(
            sender,
            "command.history-header",
            mapOf("id" to region.id, "count" to revisions.size.toString()),
        )
        if (revisions.isEmpty()) plugin.lang().sendPlain(sender, "command.history-empty")
        revisions.take(20).forEach { snapshot ->
            plugin.lang().sendPlain(
                sender,
                "command.history-line",
                mapOf(
                    "revision" to snapshot.revision.toString(),
                    "name" to snapshot.name,
                    "mode" to (snapshot.mode?.type ?: "none"),
                    "state" to snapshot.lifecycle.name.lowercase(Locale.ROOT),
                ),
            )
        }
        return true
    }

    private fun rollback(sender: CommandSender, args: Array<String>): Boolean {
        if (!has(sender, "regions.region.rollback")) return true
        val regionId = args.getOrNull(1)
        val revision = args.getOrNull(2)?.toLongOrNull()
        if (regionId == null || revision == null) {
            plugin.lang().send(sender, "invalid-usage", mapOf("usage" to "/regions rollback <id> <revision>"))
            return true
        }
        val result = plugin.publishing().rollback(sender, regionId, revision)
        if (result.success) {
            plugin.lang().sendPlain(sender, "command.rolled-back", mapOf("id" to regionId, "revision" to revision.toString()))
        } else {
            plugin.lang().sendPlain(sender, "command.rollback-failed", mapOf("reason" to plugin.lang().resultReason(result.code, result.args, result.reason)))
        }
        return true
    }

    private fun archive(sender: CommandSender, args: Array<String>): Boolean {
        if (!has(sender, "regions.region.archive")) return true
        val regionId = args.getOrNull(1)
        if (regionId == null) {
            plugin.lang().send(sender, "invalid-usage", mapOf("usage" to "/regions archive <id>"))
            return true
        }
        val result = plugin.publishing().archive(sender, regionId)
        if (result.success) {
            plugin.lang().sendPlain(sender, "command.archived", mapOf("id" to regionId))
        } else {
            plugin.lang().sendPlain(sender, "command.region-failed", mapOf("reason" to plugin.lang().resultReason(result.code, result.args, result.reason)))
        }
        return true
    }

    private fun freeze(sender: CommandSender, args: Array<String>): Boolean {
        if (!allow(sender, plugin.authority().canUseGlobalAdministration(sender))) return true
        val region = requireRegion(sender, args, "/regions freeze <id> [reason]") ?: return true
        val reason = join(args, 2).ifBlank { "manual-command" }
        val result = plugin.lifecycle().freeze(sender, region.id, reason)
        if (result.success) {
            plugin.lang().sendPlain(sender, "command.frozen", mapOf("id" to region.id))
        } else {
            plugin.lang().sendPlain(sender, "command.freeze-failed", mapOf("reason" to plugin.lang().resultReason(result.code, result.args, result.reason)))
        }
        return true
    }

    private fun unfreeze(sender: CommandSender, args: Array<String>): Boolean {
        if (!allow(sender, plugin.authority().canUseGlobalAdministration(sender))) return true
        val region = requireRegion(sender, args, "/regions unfreeze <id> [reason]") ?: return true
        val reason = join(args, 2).ifBlank { "manual-command" }
        val result = plugin.lifecycle().unfreeze(sender, region.id, reason)
        if (result.success) {
            plugin.lang().sendPlain(sender, "command.unfrozen", mapOf("id" to region.id))
        } else {
            plugin.lang().sendPlain(sender, "command.freeze-failed", mapOf("reason" to plugin.lang().resultReason(result.code, result.args, result.reason)))
        }
        return true
    }

    private fun audit(sender: CommandSender, args: Array<String>): Boolean {
        if (!allow(sender, plugin.authority().canUseGlobalAdministration(sender))) return true
        val regionId = args.getOrNull(1)
        if (regionId != null && plugin.regions().find(regionId) == null) {
            plugin.lang().send(sender, "not-found", mapOf("id" to regionId))
            return true
        }
        val limit = args.getOrNull(2)?.toIntOrNull()?.coerceIn(1, 100) ?: 20
        val events = plugin.audit().recent(regionId, limit)
        plugin.lang().sendPlain(sender, "command.audit-header", mapOf("count" to events.size.toString()))
        if (events.isEmpty()) plugin.lang().sendPlain(sender, "command.audit-empty")
        for (event in events) {
            plugin.lang().sendPlain(
                sender,
                "command.audit-line",
                mapOf(
                    "time" to DATE_FORMAT.format(Instant.ofEpochMilli(event.createdAtMillis)),
                    "region" to event.regionId,
                    "action" to event.action,
                    "actor" to event.actorName,
                    "reason" to (event.reason?.let { " " + plugin.lang().label("labels.reason." + it, it) } ?: ""),
                ),
            )
        }
        return true
    }

    /**
     * 参与 / 主持一局活动。
     *
     * `regions.use` 是**玩家侧的总开关**:没有它就不能参与别人的场地活动。
     * [has] 会让统治者与超管直接通过,所以场主不会被自己的开关挡住;
     * `start`/`end` 另有 [canManage](场主 + Source owner)把关。
     */
    private fun game(sender: CommandSender, args: Array<String>): Boolean {
        if (!has(sender, USE_PERMISSION)) {
            return true
        }
        if (args.size < 2) {
            plugin.lang().send(sender, "invalid-usage", mapOf("usage" to GAME_USAGE))
            return true
        }
        // `<id>` 可省：第二个参数直接写动作时，用玩家当下的场地。
        // 站在场上的人不应该还要背场地 ID，但旧的写法完全保留。
        val omittedId = args[1].lowercase(Locale.ROOT) in GAME_ACTIONS
        val args = if (omittedId) {
            val current = currentGameRegionId(sender)
            if (current == null) {
                plugin.lang().send(sender, "game.region-required", mapOf("usage" to GAME_USAGE))
                return true
            }
            arrayOf(args[0], current) + args.drop(1)
        } else {
            args
        }
        if (args.size < 3) {
            plugin.lang().send(sender, "invalid-usage", mapOf("usage" to GAME_USAGE))
            return true
        }
        val region = findRegion(sender, args[1]) ?: return true
        val isRace = plugin.raceModes().isRaceMode(region)
        val isRound = plugin.roundModes().isRoundMode(region)
        val isCombat = plugin.combatModes().isCombatMode(region)
        val player = sender as? Player
        val action = args[2].lowercase(Locale.ROOT)
        when (action) {
            "join" -> {
                if (!has(sender, "regions.game.join")) return true
                if (player == null) {
                    plugin.lang().send(sender, "player-only")
                    return true
                }
                val result = when {
                    isCombat -> plugin.combatModes().join(player, region.id, args.getOrNull(3))
                    isRace -> plugin.raceModes().join(player, region.id)
                    isRound -> plugin.roundModes().join(player, region.id)
                    else -> JoinResult.Rejected("game.match.join.not-a-match")
                }
                when (result) {
                    is JoinResult.Joined -> Unit
                    is JoinResult.TeamSelection -> sendTeamSelection(player, region, result.candidates)
                    is JoinResult.Rejected -> plugin.lang().send(player, result.key, result.args)
                }
            }
            "leave", "quit" -> {
                if (player == null) {
                    plugin.lang().send(sender, "player-only")
                    return true
                }
                // 退出通道不设额外权限：失去参与权限的玩家也必须能退出并拿回装备。
                val left = when {
                    isRace -> plugin.raceModes().leave(player, region.id)
                    isRound -> plugin.roundModes().leave(player, region.id)
                    else -> plugin.combatModes().leave(player, region.id)
                }
                if (!left) {
                    plugin.lang().sendPlain(player, "game.match.leave.not-joined", mapOf("id" to region.id))
                }
            }
            "ready" -> {
                if (!has(sender, "regions.game.ready")) return true
                if (player == null) {
                    plugin.lang().send(sender, "player-only")
                    return true
                }
                if (isRace) {
                    plugin.raceModes().ready(player, args[1])
                } else if (isRound) {
                    plugin.roundModes().ready(player, args[1])
                } else {
                    plugin.combatModes().ready(player, args[1])
                }
            }
            "unready" -> {
                if (!has(sender, "regions.game.ready")) return true
                if (player == null) {
                    plugin.lang().send(sender, "player-only")
                    return true
                }
                val unreadied = when {
                    isRace -> plugin.raceModes().unready(player, region.id)
                    isRound -> plugin.roundModes().unready(player, region.id)
                    isCombat -> plugin.combatModes().unready(player, region.id)
                    else -> false
                }
                if (!unreadied) {
                    plugin.lang().sendPlain(player, "game.match.unready.not-possible")
                }
            }
            "spectate" -> {
                if (!has(sender, "regions.game.spectate")) return true
                if (player == null) {
                    plugin.lang().send(sender, "player-only")
                    return true
                }
                val result = when {
                    isCombat -> plugin.combatModes().spectate(player, region.id)
                    isRace -> plugin.raceModes().spectate(player, region.id)
                    isRound -> plugin.roundModes().spectate(player, region.id)
                    else -> SpectateResult.Rejected("game.match.spectate.not-a-match")
                }
                if (result is SpectateResult.Rejected) {
                    plugin.lang().send(player, result.key, result.args)
                }
            }
            "teams" -> {
                if (!has(sender, "regions.game.start")) return true
                if (!canManage(sender, region)) return true
                val candidates = nationCandidates()
                val mine = editUnionOf(sender)
                // 只给一个参数 = "我方 vs 他"：甲方取发令者 `/l edit` 选定领地的国家。
                val explicitFirst = args.getOrNull(3)
                val explicitSecond = args.getOrNull(4)
                val first = if (explicitSecond == null && explicitFirst != null && mine != null) mine.id else explicitFirst
                val second = if (explicitSecond == null && explicitFirst != null && mine != null) explicitFirst else explicitSecond
                if (first == null || second == null) {
                    val (currentA, currentB) = plugin.combatModes().selectedTeams(region.id)
                    plugin.lang().sendPlain(
                        sender,
                        "command.game-teams",
                        mapOf(
                            "id" to region.id,
                            "a" to (currentA?.let { describeUnion(it, candidates) } ?: plugin.lang().message("gui.common.none")),
                            "b" to (currentB?.let { describeUnion(it, candidates) } ?: plugin.lang().message("gui.common.none")),
                        ),
                    )
                    // 把候选列成带编号的一份清单：服主可以直接 `teams 1 2`，不必拄 ULID。
                    listUnionCandidates(sender, candidates, mine)
                    return true
                }
                val resolvedA = resolveUnion(sender, first, candidates) ?: return true
                val resolvedB = resolveUnion(sender, second, candidates) ?: return true
                if (resolvedA.id == resolvedB.id) {
                    plugin.lang().sendPlain(
                        sender,
                        "command.game-teams-same",
                        mapOf("name" to UnionLookup.plainName(resolvedA.name)),
                    )
                    return true
                }
                val nameA = UnionLookup.plainName(resolvedA.name)
                val nameB = UnionLookup.plainName(resolvedB.name)
                if (!plugin.combatModes().selectTeams(region.id, resolvedA.id, resolvedB.id, nameA, nameB)) {
                    plugin.lang().sendPlain(sender, "command.game-teams-failed", mapOf("id" to region.id))
                    return true
                }
                plugin.audit().record(
                    sender,
                    region.id,
                    "game.teams.selected",
                    details = mapOf("a" to resolvedA.id, "b" to resolvedB.id),
                )
                plugin.lang().sendPlain(sender, "command.game-teams-set", mapOf("a" to nameA, "b" to nameB))
            }
            "result" -> {
                if (!has(sender, "regions.game.view")) return true
                // 八种玩法的结果写在同一个 store 里，所以这里不必再按玩法分派。
                val result = plugin.matchStore().lastResult(region.id)
                if (result == null) {
                    plugin.lang().sendPlain(sender, "game.match.result.none", mapOf("id" to region.id))
                    return true
                }
                plugin.lang().sendPlain(
                    sender,
                    "command.game-result",
                    mapOf(
                        "id" to region.id,
                        "outcome" to plugin.lang().label("labels.outcome.${result.outcome.name.lowercase()}", result.outcome.name.lowercase()),
                        "reason" to plugin.lang().label(result.reasonKey, result.reasonKey),
                        "winner" to result.winnerIds
                            .mapNotNull { plugin.server.getPlayer(it)?.name ?: it.toString() }
                            .joinToString(", "),
                        "reward" to plugin.lang().label("labels.reward.${result.rewardState.name.lowercase()}", result.rewardState.name.lowercase()),
                    ),
                )
            }
            "start" -> {
                if (!has(sender, "regions.game.start")) return true
                if (!canManage(sender, region)) return true
                val handled = if (isRace) {
                    plugin.raceModes().startCommand(sender, args[1])
                } else if (isRound) {
                    plugin.roundModes().startCommand(sender, args[1])
                } else {
                    false
                }
                if (!handled) {
                    // 分清两种"开不了"：战斗三兄弟由全员准备自动开赛（没有发令这一步），
                    // free_event 则根本不是比赛。回一条不相干的用法提示对谁都没帮助。
                    val key = if (isCombat) "game.match.start.not-supported" else "game.match.join.not-a-match"
                    plugin.lang().sendPlain(sender, key)
                } else {
                    plugin.audit().record(sender, region.id, "game.start.requested", "manual-command")
                }
            }
            "status" -> {
                if (!has(sender, "regions.game.view")) return true
                val status = when {
                    isRace -> plugin.raceModes().status(args[1])
                    isRound -> plugin.roundModes().status(args[1])
                    else -> plugin.combatModes().status(args[1])
                }
                plugin.lang().sendPlain(
                    sender,
                    "command.game-status",
                    mapOf("id" to args[1], "state" to plugin.gameStatusLine(sender, status)),
                )
            }
            "end", "stop" -> {
                if (!has(sender, "regions.game.end")) return true
                if (!canManage(sender, region)) return true
                if (!has(sender, "regions.region.edit") && !isRace && !isRound) {
                    return true
                }
                // PLAN.md §5.2：强制结束必须先说明影响再确认。命令语法不变（§5.4 定的是
                // `/regions game <id> end`），改成"同一条命令在 30 秒内执行两次"才真的结束，
                // 这样一次误触不会把正在打的比赛结算掉。
                if (!confirmForceEnd(sender, region)) {
                    return true
                }
                val ended = if (isRace) {
                    plugin.raceModes().forceEnd(sender, args[1], "manual-command")
                } else if (isRound) {
                    plugin.roundModes().forceEnd(sender, args[1], "manual-command")
                } else {
                    plugin.combatModes().forceEnd(args[1], "manual-command")
                }
                if (ended) {
                    plugin.audit().record(sender, region.id, "game.end", "manual-command")
                    plugin.lang().sendPlain(sender, "command.game-ended", mapOf("id" to args[1]))
                } else {
                    plugin.lang().sendPlain(sender, "command.game-not-running", mapOf("id" to args[1]))
                }
            }
            else -> plugin.lang().send(sender, "invalid-usage", mapOf("usage" to GAME_USAGE))
        }
        return true
    }

/**
     * 强制结束的两步确认（PLAN.md §5.2）。返回 true 表示本次可以真的结束。
     *
     * 第一次执行只打印"会发生什么"（场地、玩法、参赛人数、revision）并记下时间；
     * 同一位操作者在 [_FORCE_END_CONFIRM_MILLIS] 内对同一场地再执行一次才放行。
     * 只对**正在进行的比赛**要求确认——本来就没有比赛时直接走"没有进行中的游戏"分支。
     */
    private fun confirmForceEnd(sender: CommandSender, region: RegionDefinition): Boolean {
        val status = forceEndStatus(region)
        if (status == null) {
            plugin.lang().sendPlain(sender, "command.game-not-running", mapOf("id" to region.id))
            return false
        }
        val key = "${sender.name}:${region.id}"
        val now = System.currentTimeMillis()
        val previous = forceEndConfirmations[key]
        if (previous != null && now - previous <= _FORCE_END_CONFIRM_MILLIS) {
            forceEndConfirmations.remove(key)
            return true
        }
        forceEndConfirmations[key] = now
        // 顺手清掉过期条目，避免长期运行后积累。
        forceEndConfirmations.entries.removeIf { now - it.value > _FORCE_END_CONFIRM_MILLIS }
        plugin.lang().sendPlain(
            sender,
            "command.game-end-confirm",
            mapOf(
                "id" to region.id,
                "mode" to plugin.lang().label("labels.mode." + (region.mode?.type ?: ""), region.mode?.type ?: "-"),
                "participants" to status.toString(),
                "revision" to region.publishedRevision.toString(),
            ),
        )
        return false
    }

    /** 正在进行中的参赛人数；没有进行中的比赛返回 null。 */
    private fun forceEndStatus(region: RegionDefinition): Int? {
        val status = when {
            plugin.raceModes().isRaceMode(region) -> plugin.raceModes().status(region.id)
            plugin.roundModes().isRoundMode(region) -> plugin.roundModes().status(region.id)
            plugin.combatModes().isCombatMode(region) -> plugin.combatModes().status(region.id)
            else -> return null
        }
        return if (status.phase == org.cubexmc.regions.mode.GamePhase.RUNNING) status.players else null
    }

    /** 工会战选边失败时列出候选：玩家不需要记 Nation ID，直接用名字对应的稳定 ID 重试。 */
    private fun sendTeamSelection(player: Player, region: RegionDefinition, candidates: List<UnionRef>) {
        plugin.lang().send(player, "game.match.join.choose-nation", mapOf("name" to region.name))
        for (candidate in candidates) {
            plugin.lang().sendPlain(
                player,
                "game.match.join.choose-nation-line",
                mapOf("name" to candidate.name, "id" to candidate.id, "command" to "/regions game ${region.id} join ${candidate.id}"),
            )
        }
    }

    private fun reload(sender: CommandSender): Boolean {
        if (!allow(sender, plugin.authority().canUseGlobalAdministration(sender, RELOAD_PERMISSION))) {
            return true
        }
        val report = plugin.reloadRegions()
        plugin.audit().record(sender, "<global>", "plugin.reload")
        if (report.ok()) {
            plugin.lang().send(sender, "reloaded")
        } else {
            // Name the stage: a half-applied reload that reports success is how an operator ends up
            // debugging the wrong file.
            plugin.lang().send(
                sender,
                "reload-failed",
                mapOf("stages" to report.failureSummaries().joinToString("; ")),
            )
        }
        return true
    }

    private fun validate(sender: CommandSender, args: Array<String>): Boolean {
        if (!has(sender, "regions.validate")) {
            return true
        }
        val issues =
            if (args.size >= 2) {
                val region = plugin.publishing().editable(args[1])
                if (region == null) {
                    plugin.lang().send(sender, "not-found", mapOf("id" to args[1]))
                    return true
                }
                if (!canManage(sender, region)) return true
                plugin.publishing().publishingIssues(sender, region)
            } else {
                plugin.authority().visibleRegions(sender, plugin.regions().all())
                    .map { plugin.publishing().editable(it.id) ?: it }
                    .flatMap { plugin.publishing().publishingIssues(sender, it) }
                    .distinctBy { Triple(it.regionId, it.severity, it.message) }
            }
        sendIssues(sender, issues)
        return true
    }

    private fun inspect(sender: CommandSender, args: Array<String>): Boolean {
        if (!allow(sender, plugin.authority().canUseGlobalAdministration(sender, INSPECT_PERMISSION))) {
            return true
        }
        if (args.size < 2) {
            plugin.lang().send(sender, "invalid-usage", mapOf("usage" to "/regions inspect <player>"))
            return true
        }
        val player = Bukkit.getPlayerExact(args[1])
        if (player == null) {
            plugin.lang().send(sender, "player-not-found", mapOf("player" to args[1]))
            return true
        }
        val sessions = plugin.sessions().activeSessions(player.uniqueId)
        plugin.lang().send(sender, "inspect-header", mapOf("player" to player.name))
        if (sessions.isEmpty()) {
            plugin.lang().send(sender, "inspect-empty")
            return true
        }
        for (session in sessions) {
            plugin.lang().send(
                sender,
                "inspect-line",
                mapOf(
                    "region" to session.regionId,
                    "entered" to DATE_FORMAT.format(Instant.ofEpochMilli(session.enteredAtMillis)),
                ),
            )
        }
        return true
    }

    private fun cleanup(sender: CommandSender, args: Array<String>): Boolean {
        if (!allow(sender, plugin.authority().canUseGlobalAdministration(sender, CLEANUP_PERMISSION))) {
            return true
        }
        if (args.size < 2) {
            plugin.lang().send(sender, "invalid-usage", mapOf("usage" to "/regions cleanup <player>"))
            return true
        }
        val player = Bukkit.getPlayerExact(args[1])
        if (player == null) {
            plugin.lang().send(sender, "player-not-found", mapOf("player" to args[1]))
            return true
        }
        val count = plugin.sessions().cleanup(player, "manual-command")
        plugin.trials().stop(player, "manual-cleanup")
        plugin.combatModes().restoreIfPending(player, "manual-cleanup")
        plugin.roundModes().restoreIfPending(player, "manual-cleanup")
        plugin.lang().send(sender, "cleanup-done", mapOf("player" to player.name, "count" to count.toString()))
        plugin.audit().record(sender, "<global>", "session.cleanup", "manual-command", mapOf("player" to player.name, "count" to count.toString()))
        return true
    }

    // doctor 没有自己的细粒度节点(plugin.yml 里也没声明过),所以只有超管能用。
    private fun doctor(sender: CommandSender): Boolean {
        if (!allow(sender, plugin.authority().canUseGlobalAdministration(sender))) {
            return true
        }
        plugin.lang().send(sender, "doctor-header")
        val sourceSummary = plugin.sources().all().joinToString(", ") { source ->
            "${source.type}=${if (source.isAvailable()) "available" else "missing"}"
        }
        plugin.lang().send(sender, "doctor-line", mapOf("message" to "sources: $sourceSummary"))
        val unionSummary = plugin.unions().all().joinToString(", ") { provider ->
            "${provider.type}=${if (provider.isAvailable()) "available" else "missing"}"
        }
        plugin.lang().send(sender, "doctor-line", mapOf("message" to "unions: $unionSummary"))
        plugin.lang().send(sender, "doctor-line", mapOf("message" to "modes: ${plugin.modes().all().joinToString(", ")}"))
        plugin.lang().send(sender, "doctor-line", mapOf("message" to "flags: ${plugin.flags().all().joinToString(", ")}"))
        plugin.lang().send(sender, "doctor-line", mapOf("message" to "effects: ${plugin.effects().allTypes().joinToString(", ")}"))
        plugin.lang().send(sender, "doctor-line", mapOf("message" to "actions: ${plugin.actions().all().joinToString(", ")}"))
        plugin.lang().send(sender, "doctor-line", mapOf("message" to "conditions: ${plugin.conditions().all().joinToString(", ")}"))
        plugin.lang().send(sender, "doctor-line", mapOf("message" to "templates: ${plugin.templates().all().size}"))
        val highRisk = plugin.capabilities().all().count { it.risk == CapabilityRisk.HIGH }
        val summary = CapabilityKind.entries.joinToString(", ") { kind ->
            "${kind.name.lowercase(Locale.ROOT)}=${plugin.capabilities().stableIds(kind).size}"
        }
        plugin.lang().send(sender, "doctor-line", mapOf("message" to "capabilities: $summary, high-risk=$highRisk"))
        return true
    }

    private fun sendIssues(sender: CommandSender, issues: List<ValidationIssue>) {
        if (issues.isEmpty()) {
            plugin.lang().send(sender, "validate-ok")
            return
        }
        plugin.lang().send(sender, "validate-header", mapOf("count" to issues.size.toString()))
        for (issue in issues) {
            plugin.lang().send(
                sender,
                "validate-line",
                mapOf(
                    "id" to issue.regionId,
                    "severity" to plugin.lang().severityLabelFor(sender, issue.severity),
                    "message" to plugin.lang().issueLineFor(sender, issue.code, issue.args, issue.fieldPath, issue.message),
                ),
            )
        }
    }

    /**
     * /regions language [zh_CN|en_US|auto]（PLAN.md §5.4）：只在 `locale-mode: player` 时生效；
     * 选择存玩家 PDC，auto 清除后跟随客户端语言。
     */
    private fun language(sender: CommandSender, args: Array<String>): Boolean {
        if (sender !is Player) {
            plugin.lang().send(sender, "player-only")
            return true
        }
        if (!has(sender, "regions.language.select")) return true
        if (!(plugin.config.getString("locale-mode", "server") ?: "server").equals("player", ignoreCase = true)) {
            plugin.lang().send(sender, "language-disabled")
            return true
        }
        if (args.size < 2) {
            val current = plugin.lang().playerSelectedLocale(sender) ?: "auto"
            plugin.lang().send(sender, "language-current", mapOf("locale" to current))
            return true
        }
        when (args[1].lowercase(Locale.ROOT)) {
            "auto" -> {
                plugin.lang().setPlayerLocale(sender, null)
                plugin.lang().send(sender, "language-cleared")
            }
            "zh_cn" -> {
                plugin.lang().setPlayerLocale(sender, "zh_CN")
                plugin.lang().send(sender, "language-set", mapOf("locale" to "zh_CN"))
            }
            "en_us" -> {
                plugin.lang().setPlayerLocale(sender, "en_US")
                plugin.lang().send(sender, "language-set", mapOf("locale" to "en_US"))
            }
            else -> plugin.lang().send(sender, "invalid-usage", mapOf("usage" to "/regions language <zh_CN|en_US|auto>"))
        }
        return true
    }

    private fun sendHelp(sender: CommandSender) {
        if (canEnterManagementSilent(sender)) {
            plugin.lang().send(sender, "help")
            plugin.lang().send(sender, "help-publishing")
        } else {
            plugin.lang().send(sender, "help-player")
        }
    }

    /**
     * 按用户的写法找场地：场地名、Lands 领地名、列表编号、唯一前缀或 Region ID。
     *
     * Region ID 仍然是内部主键，只是不再要求人背它（PLAN.md §5.4）。
     * 同一块领地上可能叠着多个 Region（校验只禁止**有状态玩法**重叠），
     * 所以命中多个时列出候选，不替用户选。
     */
    private fun findRegion(sender: CommandSender, input: String): RegionDefinition? {
        // 先试精确 ID：旧行为一字不改（权限仍由后续的 canManage 判），
        // 也避免可见性过滤把"没权限"变成误导人的"找不到"。
        plugin.regions().find(input)?.let { return it }
        val visible = plugin.authority().visibleRegions(sender, plugin.regions().all())
        return when (val resolution = RegionLookup.resolve(input, visible)) {
            is RegionLookup.Resolution.Found -> resolution.region
            is RegionLookup.Resolution.Ambiguous -> {
                plugin.lang().send(
                    sender,
                    "region-ambiguous",
                    mapOf(
                        "input" to input,
                        "names" to resolution.matches.joinToString(", ") { RegionLookup.display(it) },
                    ),
                )
                null
            }

            RegionLookup.Resolution.NotFound -> {
                plugin.lang().send(sender, "not-found", mapOf("id" to input))
                null
            }
        }
    }

    private fun requireRegion(sender: CommandSender, args: Array<String>, usage: String): RegionDefinition? {
        if (args.size < 2) {
            plugin.lang().send(sender, "invalid-usage", mapOf("usage" to usage))
            return null
        }
        return findRegion(sender, args[1])
    }

    private fun requireEditableRegion(sender: CommandSender, args: Array<String>, usage: String): RegionDefinition? {
        if (args.size < 2) {
            plugin.lang().send(sender, "invalid-usage", mapOf("usage" to usage))
            return null
        }
        val region = plugin.publishing().editable(args[1])
        if (region == null) plugin.lang().send(sender, "not-found", mapOf("id" to args[1]))
        return region
    }

    private fun parsePairs(args: Array<String>, start: Int): MutableMap<String, String> {
        val values = LinkedHashMap<String, String>()
        for (index in start until args.size) {
            val raw = args[index]
            val split = raw.indexOf('=')
            if (split > 0) {
                values[raw.substring(0, split)] = raw.substring(split + 1)
            } else if (!values.containsKey("value")) {
                values["value"] = raw
            }
        }
        return values
    }

    private fun join(args: Array<String>, start: Int): String =
        args.drop(start).joinToString(" ")

    private fun withOwnerSnapshot(region: RegionDefinition): RegionDefinition {
        val metadata = LinkedHashMap(region.metadata)
        val ownerId = plugin.sources().find(region.source.type)?.ownerId(region.source)
        if (ownerId == null) metadata.remove(org.cubexmc.regions.service.RegionAuthorityService.SOURCE_OWNER_METADATA)
        else metadata[org.cubexmc.regions.service.RegionAuthorityService.SOURCE_OWNER_METADATA] = ownerId.toString()
        return region.copy(metadata = metadata)
    }

    private fun has(sender: CommandSender, permission: String): Boolean {
        if (sender.hasPermission(permission) || plugin.authority().isRuler(sender) || plugin.authority().isSuperAdmin(sender)) {
            return true
        }
        plugin.lang().send(sender, "no-permission")
        return false
    }

    private fun canEnterManagement(sender: CommandSender): Boolean {
        if (canEnterManagementSilent(sender)) {
            return true
        }
        allow(sender, plugin.authority().canEnterManagement(sender))
        return false
    }

    private fun canEnterManagementSilent(sender: CommandSender): Boolean =
        plugin.authority().canEnterManagement(sender).allowed

    private fun canManage(sender: CommandSender, region: RegionDefinition): Boolean =
        allow(sender, plugin.authority().canManage(sender, region))

    private fun allow(sender: CommandSender, decision: AuthorityDecision): Boolean {
        if (decision.allowed) return true
        plugin.lang().send(sender, decision.denial?.messageKey ?: "no-permission")
        return false
    }

    override fun suggest(commandSourceStack: CommandSourceStack, args: Array<String>): Collection<String> =
        suggestions(commandSourceStack.sender, args)

    private fun suggestions(sender: CommandSender, args: Array<String>): List<String> {
        val canManage = canEnterManagementSilent(sender)
        regionRootSuggestions(canManage, args)?.let { return it }
        if (!canManage && !args[0].equals("game", ignoreCase = true)) {
            return emptyList()
        }
        if (args.size == 2 && args[0].equals("game", ignoreCase = true)) {
            // 站在场地里时把动作词排在前面：那时 `<id>` 可以不写。
            val actions = if (currentGameRegionId(sender) != null) GAME_ACTIONS.toList() else emptyList()
            return startsWith(actions + visibleRegionIds(sender), args[1])
        }
        if (args.size == 2 && listOf("validate", "remove", "enable", "disable", "bind", "trial", "preview", "publish", "withdraw", "unpublish", "history", "rollback", "archive", "freeze", "unfreeze", "audit").contains(args[0].lowercase(Locale.ROOT))) {
            return startsWith(visibleRegionIds(sender), args[1])
        }
        if (args.size == 3 && args[0].equals("trial", ignoreCase = true)) {
            return startsWith(listOf("start", "stop"), args[2])
        }
        if (args.size == 2 && args[0].equals("mode", ignoreCase = true)) {
            return startsWith(listOf("set"), args[1])
        }
        if (args.size == 2 && args[0].equals("flag", ignoreCase = true)) {
            return startsWith(listOf("set"), args[1])
        }
        if (args.size == 2 && args[0].equals("effect", ignoreCase = true)) {
            return startsWith(listOf("add"), args[1])
        }
        if (args.size == 3 && (args[0].equals("mode", ignoreCase = true) || args[0].equals("flag", ignoreCase = true) || args[0].equals("effect", ignoreCase = true))) {
            return startsWith(visibleRegionIds(sender), args[2])
        }
        if (args.size == 2 && args[0].equals("language", ignoreCase = true)) {
            return startsWith(listOf("zh_CN", "en_US", "auto"), args[1])
        }
        if (args.size == 3 && args[0].equals("game", ignoreCase = true) &&
            args[1].lowercase(Locale.ROOT) !in GAME_ACTIONS
        ) {
            return startsWith(GAME_ACTIONS.toList(), args[2])
        }
        // 补全给**名字**而不是 ULID：26 位主键既读不出来也输不对，
        // 解析侧仍然收 ULID，所以粘主键的旧习惯不会失效。
        val teamsAt = when {
            args[0].equals("game", ignoreCase = true) && args.getOrNull(2)?.equals("teams", true) == true -> 3
            args[0].equals("game", ignoreCase = true) && args.getOrNull(1)?.equals("teams", true) == true -> 2
            else -> -1
        }
        if (teamsAt > 0 && args.size in teamsAt + 1..teamsAt + 2) {
            val names = UnionLookup.completions(nationCandidates())
            val options = if (editUnionOf(sender) != null) listOf(SELF_UNION_TOKEN) + names else names
            return startsWith(options, args[args.size - 1])
        }
        if (args.size == 3 && args[0].equals("bind", ignoreCase = true)) {
            return startsWith(listOf("cuboid", "lands"), args[2])
        }
        if (args.size == 4 && args[0].equals("flag", ignoreCase = true)) {
            return startsWith(plugin.flags().all(), args[3])
        }
        if (args.size == 4 && args[0].equals("mode", ignoreCase = true)) {
            return startsWith(plugin.modes().all(), args[3])
        }
        if (args.size == 5 && args[0].equals("flag", ignoreCase = true)) {
            return startsWith(listOf("allow", "deny", "pass", "blocklist", "allowlist"), args[4])
        }
        if (args.size == 4 && args[0].equals("effect", ignoreCase = true)) {
            return startsWith(plugin.effects().allTypes(), args[3])
        }
        if (args.size == 2 && (args[0].equals("inspect", ignoreCase = true) || args[0].equals("cleanup", ignoreCase = true))) {
            return startsWith(Bukkit.getOnlinePlayers().map { it.name }, args[1])
        }
        return emptyList()
    }

    private fun startsWith(values: Collection<String>, prefix: String): List<String> {
        val lower = prefix.lowercase(Locale.ROOT)
        return values.filter { it.lowercase(Locale.ROOT).startsWith(lower) }.sorted().take(20)
    }

    /**
     * 省略 `<id>` 时用哪个场地：先看玩家是不是已经在某场比赛里（参赛/观战/待恢复），
     * 否则用脚下的场地。脚下同时压着多个场地时取优先级最高的那个（与进区检测同一排序）。
     * 控制台没有位置，所以控制台必须显式写 ID。
     */
    private fun currentGameRegionId(sender: CommandSender): String? {
        val player = sender as? Player ?: return null
        plugin.combatModes().activeRegionId(player.uniqueId)?.let { return it }
        return plugin.detection().regionsAt(player.location)
            .firstOrNull { plugin.combatModes().isCombatMode(it) || plugin.raceModes().isRaceMode(it) || plugin.roundModes().isRoundMode(it) }
            ?.id
    }

    /**
     * 场地参数的补全：给**名字与领地名**，ID 不再刷屏。
     * 解析侧仍然收 ID，脚本与审计里粘出来的主键照样能用。
     */
    private fun visibleRegionIds(sender: CommandSender): List<String> =
        RegionLookup.completions(plugin.authority().visibleRegions(sender, plugin.regions().all()))

    /**
     * 工会战选队的 Nation 补全。提供方识别不到就返回空列表——
     * 补全宁可什么都不提示，也不能给出编造的 ID。
     */
    private fun nationCandidates(): List<UnionRef> =
        plugin.unions().all()
            .filter { it.isAvailable() && it.type != "fallback" }
            .flatMap { it.allUnions() }
            .distinctBy { it.id }

    /**
     * 把一行输入解析成工会：编号、名字（去色）、唯一前缀或 ULID 都行。
     * 解析不出来就**把候选列出来**，而不是只回一句"失败"——
     * 服主手里未必有 Lands 的 Nation 列表。
     */
    private fun resolveUnion(sender: CommandSender, input: String, candidates: List<UnionRef>): UnionRef? {
        if (input.lowercase(java.util.Locale.ROOT) in SELF_UNION_ALIASES) {
            val mine = editUnionOf(sender)
            if (mine == null) {
                plugin.lang().sendPlain(sender, "command.game-teams-no-edit-land", emptyMap())
                return null
            }
            return mine
        }
        return resolveListed(sender, input, candidates)
    }

    /**
     * 发令者"当下正在管的领地"所属的国家（Lands 的 `/l edit`）。
     * 控制台没有领地，也就没有这个快捷方式。
     */
    private fun editUnionOf(sender: CommandSender): UnionRef? {
        val player = sender as? Player ?: return null
        return plugin.unions().active()?.getEditUnion(player.uniqueId)
    }

    private fun resolveListed(sender: CommandSender, input: String, candidates: List<UnionRef>): UnionRef? =
        when (val resolution = UnionLookup.resolve(input, candidates)) {
            is UnionLookup.Resolution.Found -> resolution.union
            is UnionLookup.Resolution.Ambiguous -> {
                plugin.lang().sendPlain(
                    sender,
                    "command.game-teams-ambiguous",
                    mapOf(
                        "input" to input,
                        "names" to resolution.matches.joinToString(", ") { UnionLookup.plainName(it.name) },
                    ),
                )
                null
            }

            UnionLookup.Resolution.NotFound -> {
                plugin.lang().sendPlain(sender, "command.game-teams-unknown", mapOf("input" to input))
                listUnionCandidates(sender, candidates, editUnionOf(sender))
                null
            }
        }

    private fun listUnionCandidates(sender: CommandSender, candidates: List<UnionRef>, mine: UnionRef? = null) {
        val ordered = UnionLookup.ordered(candidates)
        if (ordered.isEmpty()) {
            plugin.lang().sendPlain(sender, "command.game-teams-none", emptyMap())
            return
        }
        plugin.lang().sendPlain(sender, "command.game-teams-candidates", emptyMap())
        ordered.forEachIndexed { index, union ->
            plugin.lang().sendPlain(
                sender,
                if (union.id == mine?.id) "command.game-teams-candidate-mine" else "command.game-teams-candidate",
                mapOf(
                    "index" to (index + 1).toString(),
                    "name" to UnionLookup.plainName(union.name),
                    "id" to union.id,
                ),
            )
        }
    }

    /** 已选定的工会尽量显示名字；列表里找不到（工会解散/改名）才退回 ULID。 */
    private fun describeUnion(id: String, candidates: List<UnionRef>): String =
        candidates.firstOrNull { it.id == id }?.let { UnionLookup.plainName(it.name) } ?: id

    companion object {
        /** 选队时代表"我当下管的那个领地所属的国家"的写法。 */
        const val SELF_UNION_TOKEN = "me"

        private val SELF_UNION_ALIASES = setOf(SELF_UNION_TOKEN, "edit", "@me", "self")

        /** 玩家侧总开关:没有它就不能参与别人场地的活动。 */
        const val USE_PERMISSION = "regions.use"

        /** 强制结束的二次确认窗口。 */
        private const val _FORCE_END_CONFIRM_MILLIS = 30_000L

        /** `/regions game` 的完整用法串；help 与拒绝提示共用，避免两处漂移。 */
        const val GAME_USAGE = "/regions game [id] <join|leave|ready|unready|spectate|status|result|teams|start|end>"

        /** 写在 `<id>` 位置上时表示"省略了场地"的动作词。 */
        private val GAME_ACTIONS = setOf(
            "join", "leave", "ready", "unready", "spectate", "status", "result", "teams", "start", "end", "stop",
        )

        // 全服级操作的细粒度节点:发了其中一个就能只做那一件事,不必给整个 regions.superadmin。
        const val RELOAD_PERMISSION = "regions.reload"
        const val INSPECT_PERMISSION = "regions.inspect"
        const val CLEANUP_PERMISSION = "regions.cleanup"

        private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT)
            .withZone(ZoneId.systemDefault())
    }
}

internal fun regionRootSuggestions(canManage: Boolean, args: Array<out String>): List<String>? {
    val commands = if (canManage) MANAGEMENT_ROOT_COMMANDS else PLAYER_ROOT_COMMANDS
    return CubexCommandSuggestions.root(args, commands)?.sorted()?.take(20)
}

private val MANAGEMENT_ROOT_COMMANDS = listOf(
    "gui", "list", "create", "remove", "enable", "disable", "bind", "mode", "flag", "effect",
    "trial", "preview", "publish", "withdraw", "history", "rollback", "archive", "freeze",
    "unfreeze", "audit", "game", "reload", "validate", "inspect", "cleanup", "doctor", "help",
)
private val PLAYER_ROOT_COMMANDS = listOf("game", "language", "help")
