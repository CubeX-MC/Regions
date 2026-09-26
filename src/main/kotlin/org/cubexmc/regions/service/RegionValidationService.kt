package org.cubexmc.regions.service

import org.bukkit.Material
import org.bukkit.Bukkit
import org.bukkit.NamespacedKey
import org.bukkit.Registry
import org.cubexmc.regions.capability.CapabilityCatalog
import org.cubexmc.regions.capability.CapabilityKind
import org.cubexmc.regions.effect.ScopedEffectService
import org.cubexmc.regions.flag.RegionFlagRegistry
import org.cubexmc.regions.integration.RegionSourceRegistry
import org.cubexmc.regions.match.LastPlayerStandingRules
import org.cubexmc.regions.match.MatchSpawns
import org.cubexmc.regions.match.NationBattleRules
import org.cubexmc.regions.mode.RegionModeRegistry
import org.cubexmc.regions.mode.ModeKit
import org.cubexmc.regions.model.RegionDefinition
import org.cubexmc.regions.model.ValidationIssue
import org.cubexmc.regions.model.ValidationSeverity
import org.cubexmc.regions.reward.RewardFundingValidator
import java.util.Locale

class RegionValidationService(
    private val sources: RegionSourceRegistry,
    private val modes: RegionModeRegistry,
    private val flags: RegionFlagRegistry,
    private val effects: ScopedEffectService,
    private val actions: RegionActionRegistry,
    private val conditions: RegionConditionRegistry,
    private val capabilities: CapabilityCatalog,
    private val overlaps: RegionOverlapResolver = RegionOverlapResolver(),
    private val rewardFunding: RewardFundingValidator? = null,
) {
    fun validateAll(regions: Collection<RegionDefinition>): List<ValidationIssue> {
        val definitions = regions.toList()
        val issues = definitions.flatMapTo(ArrayList()) { validate(it) }
        for (candidate in definitions) {
            issues.addAll(overlaps.validateCandidate(candidate, definitions) { region ->
                sources.find(region.source.type)?.geometry(region.source)
            })
        }
        return issues.distinctBy { Triple(it.regionId, it.severity, it.code to it.args) }
    }

    fun validate(region: RegionDefinition): List<ValidationIssue> {
        val issues = ArrayList<ValidationIssue>()
        if (region.id.isBlank()) {
            issues.add(error(region.id, "region-id-blank", message = "Region id cannot be blank."))
        } else if (!REGION_ID.matches(region.id)) {
            issues.add(error(
                region.id,
                "region-id-invalid",
                args = mapOf("id" to region.id),
                message = "Region id must contain 2-48 lowercase letters, numbers, underscores, or hyphens.",
            ))
        }
        if (region.name.isBlank()) {
            issues.add(error(region.id, "region-name-blank", message = "Region name cannot be blank."))
        }

        val source = sources.find(region.source.type)
        if (source == null) {
            issues.add(error(
                region.id,
                "source-unknown",
                args = mapOf("type" to region.source.type),
                message = "Unknown region source '${region.source.type}'.",
            ))
        } else if (!source.isAvailable()) {
            issues.add(error(
                region.id,
                "source-unavailable",
                args = mapOf("type" to region.source.type),
                message = "Region source '${region.source.type}' is not currently available. Install or enable its required integration.",
            ))
        } else if (source.resolve(region.source) == null) {
            issues.add(error(
                region.id,
                "source-unresolved",
                args = mapOf("source" to region.source.describe()),
                message = "Region source '${region.source.describe()}' could not be resolved. Rebind it to an existing owned area.",
            ))
        }
        addCapabilityIssues(issues, region.id, CapabilityKind.SOURCE, region.source.type, region.source.values)

        val mode = region.mode
        if (mode != null && !modes.isRegistered(mode.type)) {
            issues.add(error(region.id, "mode-unknown", args = mapOf("type" to mode.type), message = "Unknown mode '${mode.type}'."))
        }
        if (mode != null) {
            addCapabilityIssues(issues, region.id, CapabilityKind.MODE, mode.type, mode.values)
            addModeRuleIssues(issues, region)
        }

        for (flag in region.flags.values) {
            if (!flags.isRegistered(flag.key)) {
                issues.add(error(region.id, "flag-unknown", args = mapOf("key" to flag.key), message = "Unknown or unavailable flag '${flag.key}'."))
            }
            addCapabilityIssues(
                issues,
                region.id,
                CapabilityKind.FLAG,
                flag.key,
                linkedMapOf("value" to flag.value).apply { putAll(flag.values) },
            )
        }

        for (effect in region.effects) {
            if (!effects.isRegistered(effect.type)) {
                issues.add(error(region.id, "effect-unknown", args = mapOf("type" to effect.type), message = "Unknown effect '${effect.type}'."))
            }
            addCapabilityIssues(issues, region.id, CapabilityKind.EFFECT, effect.type, effect.values)
            validateEffectRuntime(issues, region.id, effect.type, effect.values)
        }

        for ((trigger, blocks) in region.triggers) {
            if (capabilities.find(CapabilityKind.TRIGGER, trigger.key) == null) {
                issues.add(error(
                    region.id,
                    "trigger-no-runtime",
                    args = mapOf("trigger" to trigger.key),
                    message = "Trigger '${trigger.key}' has no runtime and would never fire.",
                ))
            }
            for (block in blocks) {
                for (condition in block.conditions) {
                    if (!conditions.isRegistered(condition.type)) {
                        issues.add(error(
                            region.id,
                            "condition-unknown",
                            args = mapOf("type" to condition.type, "trigger" to trigger.key),
                            message = "Unknown condition '${condition.type}' in trigger '${trigger.key}'.",
                        ))
                    }
                    addCapabilityIssues(issues, region.id, CapabilityKind.CONDITION, condition.type, condition.values, trigger.key)
                }
                for (action in block.thenActions + block.elseActions) {
                    if (!actions.isRegistered(action.type)) {
                        issues.add(error(
                            region.id,
                            "action-unknown",
                            args = mapOf("type" to action.type, "trigger" to trigger.key),
                            message = "Unknown action '${action.type}' in trigger '${trigger.key}'.",
                        ))
                    }
                    addCapabilityIssues(issues, region.id, CapabilityKind.ACTION, action.type, action.values, trigger.key)
                    validateActionRuntime(issues, region.id, trigger.key, action.type, action.values)
                }
            }
        }
        return issues
    }

    private fun error(
        regionId: String,
        code: String,
        args: Map<String, String> = emptyMap(),
        fieldPath: String? = null,
        message: String,
    ): ValidationIssue = ValidationIssue(regionId.ifBlank { "<unknown>" }, ValidationSeverity.ERROR, code, args, fieldPath, message)

    private fun warning(
        regionId: String,
        code: String,
        args: Map<String, String> = emptyMap(),
        fieldPath: String? = null,
        message: String,
    ): ValidationIssue = ValidationIssue(regionId.ifBlank { "<unknown>" }, ValidationSeverity.WARNING, code, args, fieldPath, message)

    private fun addCapabilityIssues(
        issues: MutableList<ValidationIssue>,
        regionId: String,
        kind: CapabilityKind,
        id: String,
        values: Map<String, String>,
        context: String? = null,
    ) {
        for (issue in capabilities.validate(kind, id, values)) {
            val args = if (context == null) issue.args else issue.args + mapOf("trigger" to context)
            val message = if (context == null) issue.message else "${issue.message} in trigger '$context'."
            issues.add(ValidationIssue(regionId.ifBlank { "<unknown>" }, if (issue.error) ValidationSeverity.ERROR else ValidationSeverity.WARNING, issue.code, args, issue.fieldPath, message))
        }
    }

    private fun addModeRuleIssues(issues: MutableList<ValidationIssue>, region: RegionDefinition) {
        val mode = region.mode ?: return
        val values = mode.values
        if (!values["reward-source"].isNullOrBlank() || !values["reward-contract"].isNullOrBlank()) {
            val funding = rewardFunding?.check(region)
                ?: org.cubexmc.regions.reward.FundingResult.fail("PROVIDER_UNAVAILABLE")
            if (!funding.successful) {
                issues.add(error(
                    region.id,
                    "funding-unavailable",
                    args = mapOf("code" to funding.code),
                    message = "Contract reward funding is unavailable (${funding.code}): ${funding.detail}",
                ))
            }
        }
        val minPlayers = values["min-players"]?.toIntOrNull()
        val maxPlayers = values["max-players"]?.toIntOrNull()
        if (minPlayers != null && maxPlayers != null && maxPlayers > 0 && maxPlayers < minPlayers) {
            issues.add(error(
                region.id,
                "max-players-below-min",
                args = mapOf("min" to minPlayers.toString(), "max" to maxPlayers.toString()),
                fieldPath = "mode.values.max-players",
                message = "Mode max-players must be 0 (unlimited) or >= min-players.",
            ))
        }
        when (mode.type.lowercase(Locale.ROOT)) {
            "dual_pvp", "union_war", "free_for_all" -> {
                if (minPlayers != null && minPlayers < 2) {
                    issues.add(error(
                        region.id,
                        "min-players-below",
                        args = mapOf("type" to mode.type),
                        fieldPath = "mode.values.min-players",
                        message = "${mode.type} requires min-players >= 2.",
                    ))
                }
                validateItemList(issues, region.id, "kit", values["kit"])
                validateItemList(issues, region.id, "armor", values["armor"])
                validateItemList(issues, region.id, "offhand", values["offhand"], maxEntries = 1)
                validateLocation(issues, region.id, "respawn", values["respawn"] ?: values["outside"], required = true)
                validateCombatTiming(issues, region.id, mode.type, values)
                validateSpawns(issues, region.id, mode.type, values, minPlayers, maxPlayers)
                when (mode.type.lowercase(Locale.ROOT)) {
                    "dual_pvp" -> {
                        val bestOf = values["best-of"]?.toIntOrNull()
                        if (bestOf != null && bestOf != 1 && bestOf != 3) {
                            issues.add(error(
                                region.id,
                                "best-of-invalid",
                                fieldPath = "mode.values.best-of",
                                message = "dual_pvp best-of must be 1 (single round) or 3.",
                            ))
                        }
                    }

                    "union_war" -> {
                        val teamSize = values["team-size"]?.toIntOrNull()
                        if (teamSize != null && teamSize !in 2..10) {
                            issues.add(error(
                                region.id,
                                "team-size-invalid",
                                fieldPath = "mode.values.team-size",
                                message = "union_war team-size must be between 2 and 10.",
                            ))
                        }
                        val minUnions = values["min-unions"]?.toIntOrNull()
                        if (minUnions != null && minPlayers != null && minUnions > minPlayers) {
                            issues.add(error(
                                region.id,
                                "min-unions-above-players",
                                args = mapOf("min-unions" to minUnions.toString(), "min-players" to minPlayers.toString()),
                                fieldPath = "mode.values.min-unions",
                                message = "union_war min-unions cannot exceed min-players.",
                            ))
                        }
                    }

                    "free_for_all" -> {
                        if (maxPlayers != null && maxPlayers > 16) {
                            issues.add(error(
                                region.id,
                                "free-for-all-capacity",
                                args = mapOf("max" to maxPlayers.toString(), "limit" to "16"),
                                fieldPath = "mode.values.max-players",
                                message = "free_for_all supports at most 16 players in this release.",
                            ))
                        }
                        if (!values["reward-source"].isNullOrBlank() || !values["reward-contract"].isNullOrBlank()) {
                            issues.add(error(
                                region.id,
                                "free-for-all-reward-unsupported",
                                fieldPath = "mode.values.reward-source",
                                message = "free_for_all has no reward integration; remove reward-source/reward-contract.",
                            ))
                        }
                    }
                }
            }
            "run_race", "boat_race", "horse_race" -> {
                validateModeGear(issues, region.id, values)
                val timeout = values["timeout-seconds"]
                    ?: values["max-duration-seconds"]
                    ?: values["duration-seconds"]
                if (timeout != null && (timeout.toLongOrNull() ?: 0L) <= 0L) {
                    issues.add(error(
                        region.id,
                        "race-timeout-invalid",
                        fieldPath = "mode.values.timeout-seconds",
                        message = "Race timeout must be a positive number of seconds.",
                    ))
                }
                if (values["require-start"]?.toBooleanStrictOrNull() != false) {
                    validateLocation(issues, region.id, "start", values["start"], required = true)
                } else {
                    validateLocation(issues, region.id, "start", values["start"], required = false)
                }
                validateLocation(issues, region.id, "finish", values["finish"], required = true)
                val checkpoints = values["checkpoints"]
                    ?.split(';')
                    ?.filter { it.isNotBlank() }
                    .orEmpty()
                checkpoints
                    ?.forEachIndexed { index, checkpoint ->
                        validateLocation(issues, region.id, "checkpoint ${index + 1}", checkpoint, required = true)
                    }
                val checkpointVehicles = values["checkpoint-vehicles"]
                    ?.split(';')
                    ?.filter { it.isNotBlank() }
                    .orEmpty()
                if (checkpointVehicles.isNotEmpty() && checkpointVehicles.size != checkpoints.size) {
                    issues.add(error(
                        region.id,
                        "checkpoint-vehicles-mismatch",
                        fieldPath = "mode.values.checkpoint-vehicles",
                        message = "checkpoint-vehicles must contain one entry for each checkpoint.",
                    ))
                }
            }
            "hide_and_seek" -> {
                val hideSeconds = values["hide-seconds"]?.toLongOrNull()
                val roundSeconds = values["round-seconds"]?.toLongOrNull()
                if (hideSeconds != null && roundSeconds != null && roundSeconds > 0 && roundSeconds <= hideSeconds) {
                    issues.add(error(
                        region.id,
                        "round-seconds-not-after-hide",
                        fieldPath = "mode.values.round-seconds",
                        message = "hide_and_seek round-seconds must be greater than hide-seconds.",
                    ))
                }
                val seekers = values["seekers"]?.toIntOrNull()
                if (seekers != null && minPlayers != null && seekers >= minPlayers) {
                    issues.add(error(
                        region.id,
                        "seekers-above-min-players",
                        fieldPath = "mode.values.seekers",
                        message = "hide_and_seek seekers must be lower than min-players.",
                    ))
                }
                validateItemList(issues, region.id, "seeker-kit", values["seeker-kit"])
                validateItemList(issues, region.id, "hider-kit", values["hider-kit"])
                validateModeGear(issues, region.id, values, "seeker-kit", "hider-kit")
            }
        }
    }

    /**
     * 竞速与捉迷藏的装备配置。
     *
     * 这两类玩法此前**不读**任何装备键：`kit=DIAMOND_SWORD` 能通过校验、能发布，
     * 然后什么都不会发生。现在键真的生效了，校验也必须跟上，否则只是把
     * "静默无效"换成了"开赛时才炸"。
     *
     * 接管装备就必须有返回点：入场前快照要靠它把人送回场外，没有返回点的话
     * 被淘汰的选手会留在赛道里，恢复也无处可去。
     */
    private fun validateModeGear(
        issues: MutableList<ValidationIssue>,
        regionId: String,
        values: Map<String, String>,
        vararg extraKitKeys: String,
    ) {
        validateItemList(issues, regionId, "kit", values["kit"])
        validateItemList(issues, regionId, "armor", values["armor"], maxEntries = 4)
        validateItemList(issues, regionId, "offhand", values["offhand"], maxEntries = 1)
        val replacesGear = ModeKit.shouldReplaceGear(values, *extraKitKeys)
        validateLocation(
            issues,
            regionId,
            "respawn",
            values["respawn"] ?: values["outside"] ?: values["spectator"],
            required = replacesGear,
        )
    }

    /**
     * 比赛时长与阶段参数（PLAN.md §6.2/§7）：回合/整场时限必须是正整数秒，
     * 阶段时长由 capability schema 约束范围，这里只补"必须为正"的语义检查。
     */
    private fun validateCombatTiming(
        issues: MutableList<ValidationIssue>,
        regionId: String,
        modeType: String,
        values: Map<String, String>,
    ) {
        val timeoutKey = if (modeType.equals("dual_pvp", ignoreCase = true)) "round-seconds" else "timeout-seconds"
        val raw = values[timeoutKey]
        if (raw != null && (raw.toLongOrNull() ?: 0L) <= 0L) {
            issues.add(error(
                regionId,
                "combat-timeout-invalid",
                args = mapOf("field" to timeoutKey),
                fieldPath = "mode.values.$timeoutKey",
                message = "$modeType $timeoutKey must be a positive number of seconds.",
            ))
        }
    }

    /**
     * 出生点校验（PLAN.md §7.1/§7.2/§7.3）：位置必须可解析、数量必须够用且不重复，
     * 间距只是建议（不足时提示补充出生点，不阻断发布）。
     */
    private fun validateSpawns(
        issues: MutableList<ValidationIssue>,
        regionId: String,
        modeType: String,
        values: Map<String, String>,
        minPlayers: Int?,
        maxPlayers: Int?,
    ) {
        val raw = values["spawn-points"]
        for (entry in MatchSpawns.invalidEntries(raw) + MatchSpawns.invalidEntries(values["spawn-points-b"])) {
            issues.add(error(
                regionId,
                "spawn-point-invalid",
                args = mapOf("entry" to entry),
                fieldPath = "mode.values.spawn-points",
                message = "Spawn 'entry' must use world,x,y,z[,yaw,pitch].",
            ))
        }
        when (modeType.lowercase(Locale.ROOT)) {
            "dual_pvp" -> {
                val points = MatchSpawns.parseList(raw)
                if (points.size != 2) {
                    issues.add(error(
                        regionId,
                        "spawn-points-count",
                        args = mapOf("required" to "2", "current" to points.size.toString()),
                        fieldPath = "mode.values.spawn-points",
                        message = "dual_pvp needs exactly two spawn points (A and B).",
                    ))
                }
            }

            "union_war" -> {
                val teamSize = values["team-size"]?.toIntOrNull()
                    ?: minPlayers?.div(2)?.takeIf { it > 0 }
                    ?: NationBattleRules.DEFAULT_TEAM_SIZE
                val first = MatchSpawns.parseList(raw)
                val second = MatchSpawns.parseList(values["spawn-points-b"])
                // 每队至少要有一个独立集合点才能开赛；少于每队人数只是提示补点，不阻断发布。
                for ((label, points) in listOf("spawn-points" to first, "spawn-points-b" to second)) {
                    val distinct = MatchSpawns.distinctCount(points)
                    if (distinct == 0) {
                        issues.add(error(
                            regionId,
                            "spawn-points-count",
                            args = mapOf("required" to "1", "current" to "0"),
                            fieldPath = "mode.values.$label",
                            message = "union_war needs a spawn point set for each side.",
                        ))
                    } else if (distinct < teamSize) {
                        issues.add(warning(
                            regionId,
                            "spawn-points-count",
                            args = mapOf("required" to teamSize.toString(), "current" to distinct.toString()),
                            fieldPath = "mode.values.$label",
                            message = "union_war has fewer distinct spawn points than players per side; add more when you can.",
                        ))
                    }
                }
            }

            "free_for_all" -> {
                val required = maxPlayers ?: LastPlayerStandingRules.DEFAULT_MAX
                val points = MatchSpawns.parseList(raw)
                val distinct = MatchSpawns.distinctCount(points)
                if (required in 1..LastPlayerStandingRules.DEFAULT_MAX && distinct < required) {
                    issues.add(error(
                        regionId,
                        "spawn-points-count",
                        args = mapOf("required" to required.toString(), "current" to distinct.toString()),
                        fieldPath = "mode.values.spawn-points",
                        message = "free_for_all needs one distinct spawn point per possible player.",
                    ))
                }
                val violations = MatchSpawns.spacingViolations(points)
                if (violations.isNotEmpty()) {
                    issues.add(warning(
                        regionId,
                        "spawn-points-spacing",
                        args = mapOf("minimum" to MatchSpawns.RECOMMENDED_SPACING.toInt().toString()),
                        fieldPath = "mode.values.spawn-points",
                        message = "Some free_for_all spawn points are closer than the recommended spacing.",
                    ))
                }
            }
        }
    }

    private fun validateActionRuntime(
        issues: MutableList<ValidationIssue>,
        regionId: String,
        trigger: String,
        type: String,
        values: Map<String, String>,
    ) {
        when (type.lowercase(Locale.ROOT)) {
            "message", "broadcast", "title" -> validateTextAction(issues, regionId, trigger, type, values)
            "effect_apply" -> {
                val effectType = values["effect"] ?: values["effect-type"] ?: return
                if (!effects.isRegistered(effectType)) {
                    issues.add(error(
                        regionId,
                        "effect-unknown",
                        args = mapOf("type" to effectType, "trigger" to trigger),
                        message = "Unknown effect '$effectType' used by effect_apply in trigger '$trigger'.",
                    ))
                    return
                }
                val effectValues = LinkedHashMap(values).apply {
                    remove("effect")
                    remove("effect-type")
                    remove("scope")
                }
                addCapabilityIssues(
                    issues,
                    regionId,
                    CapabilityKind.EFFECT,
                    effectType,
                    effectValues,
                    "$trigger/effect_apply",
                )
                validateEffectRuntime(issues, regionId, effectType, effectValues)
            }
            "teleport" -> validateLocation(
                issues,
                regionId,
                "teleport action in trigger '$trigger'",
                values["location"] ?: values["value"] ?: values["to"],
                required = true,
                requireLoadedWorld = true,
            )
            "give_item", "take_item" -> validateItemList(
                issues,
                regionId,
                "$type action in trigger '$trigger'",
                values["item"] ?: values["value"],
                maxEntries = 1,
            )
            "sound" -> {
                val sound = values["sound"] ?: values["name"] ?: return
                val normalized = sound.lowercase(Locale.ROOT)
                val key = runCatching {
                    NamespacedKey.fromString(normalized)
                        ?: NamespacedKey.minecraft(normalized.replace('_', '.'))
                }.getOrNull()
                if (key == null) {
                    issues.add(error(
                        regionId,
                        "sound-invalid",
                        args = mapOf("sound" to sound, "trigger" to trigger),
                        message = "Invalid sound key '$sound' in trigger '$trigger'.",
                    ))
                    return
                }
                val serverAvailable = runCatching { Bukkit.getServer() }.getOrNull() != null
                if (serverAvailable && Registry.SOUND_EVENT.get(key) == null) {
                    issues.add(error(
                        regionId,
                        "sound-unknown",
                        args = mapOf("sound" to sound, "trigger" to trigger),
                        message = "Unknown sound '$sound' in trigger '$trigger'.",
                    ))
                }
            }
        }
    }

    /**
     * 文本类动作（PLAN.md §4.3）：键形式与字面量形式互斥；message/broadcast 必须提供
     * text 或 text-key 之一，title 的两个显示字段至少给 title 或 title-key。
     */
    private fun validateTextAction(
        issues: MutableList<ValidationIssue>,
        regionId: String,
        trigger: String,
        type: String,
        values: Map<String, String>,
    ) {
        for ((literal, key) in listOf("text" to "text-key", "title" to "title-key", "subtitle" to "subtitle-key")) {
            val hasLiteral = values[literal] != null || (literal == "text" && values["message"] != null)
            if (hasLiteral && values[key] != null) {
                issues.add(error(
                    regionId,
                    "action-text-conflict",
                    args = mapOf("type" to type, "field" to literal, "trigger" to trigger),
                    message = "Action '$type' in trigger '$trigger' sets both '$literal' and '$key'; they are mutually exclusive.",
                ))
            }
        }
        if (type.equals("title", ignoreCase = true)) return
        if (values["text"] == null && values["message"] == null && values["text-key"] == null) {
            issues.add(error(
                regionId,
                "action-text-required",
                args = mapOf("type" to type, "trigger" to trigger),
                message = "Action '$type' in trigger '$trigger' requires 'text' or 'text-key'.",
            ))
        }
    }

    private fun validateEffectRuntime(
        issues: MutableList<ValidationIssue>,
        regionId: String,
        type: String,
        values: Map<String, String>,
    ) {
        if (!type.equals("potion", ignoreCase = true)) return
        val raw = values["effect"] ?: values["name"] ?: return
        val normalized = raw.lowercase(Locale.ROOT)
        val key = runCatching {
            NamespacedKey.fromString(normalized) ?: NamespacedKey.minecraft(normalized)
        }.getOrNull()
        if (key == null) {
            issues.add(error(
                regionId,
                "potion-key-invalid",
                args = mapOf("effect" to raw),
                message = "Invalid potion effect key '$raw'.",
            ))
            return
        }
        val serverAvailable = runCatching { Bukkit.getServer() }.getOrNull() != null
        if (serverAvailable && Registry.MOB_EFFECT.get(key) == null) {
            issues.add(error(
                regionId,
                "potion-unknown",
                args = mapOf("effect" to raw),
                message = "Unknown potion effect '$raw'.",
            ))
        }
    }

    private fun validateItemList(
        issues: MutableList<ValidationIssue>,
        regionId: String,
        label: String,
        raw: String?,
        maxEntries: Int = Int.MAX_VALUE,
    ) {
        if (raw.isNullOrBlank()) return
        val entries = raw.split(',', ';').filter { it.isNotBlank() }
        if (entries.size > maxEntries) {
            issues.add(error(
                regionId,
                "item-list-too-many",
                args = mapOf("field" to label, "max" to maxEntries.toString()),
                fieldPath = "mode.values.$label",
                message = "Mode $label accepts at most $maxEntries item entry.",
            ))
        }
        for (entry in entries) {
            val parts = entry.trim().split(':')
            val material = parts.firstOrNull().orEmpty()
            val amount = parts.getOrNull(1)?.toIntOrNull() ?: 1
            if (
                !ITEM_ID.matches(material) ||
                Material.matchMaterial(material.uppercase(Locale.ROOT)) == null ||
                amount !in 1..64 ||
                parts.size > 2
            ) {
                issues.add(error(
                    regionId,
                    "item-entry-invalid",
                    args = mapOf("field" to label, "entry" to entry),
                    fieldPath = "mode.values.$label",
                    message = "Mode $label item '$entry' must use MATERIAL[:amount] with amount 1-64.",
                ))
            }
        }
    }

    private fun validateLocation(
        issues: MutableList<ValidationIssue>,
        regionId: String,
        label: String,
        raw: String?,
        required: Boolean,
        requireLoadedWorld: Boolean = false,
    ) {
        if (raw.isNullOrBlank()) {
            if (required) {
                issues.add(error(
                    regionId,
                    "location-required",
                    args = mapOf("field" to label),
                    message = "Mode requires a $label location.",
                ))
            }
            return
        }
        val parts = raw.split(',')
        val valid = parts.size >= 4 &&
            parts[0].isNotBlank() &&
            parts[1].trim().toDoubleOrNull() != null &&
            parts[2].trim().toDoubleOrNull() != null &&
            parts[3].trim().toDoubleOrNull() != null
        if (!valid) {
            issues.add(error(
                regionId,
                "location-invalid",
                args = mapOf("field" to label),
                message = "Mode $label must use world,x,y,z[,yaw,pitch].",
            ))
        } else if (requireLoadedWorld) {
            val server = runCatching { Bukkit.getServer() }.getOrNull()
            if (server != null && server.getWorld(parts[0].trim()) == null) {
                issues.add(error(
                    regionId,
                    "world-unloaded",
                    args = mapOf("field" to label, "world" to parts[0].trim()),
                    message = "Mode $label references unloaded world '${parts[0].trim()}'.",
                ))
            }
        }
    }

    private companion object {
        val REGION_ID = Regex("[a-z0-9_-]{2,48}")
        val ITEM_ID = Regex("[A-Za-z0-9_]+")
    }
}
