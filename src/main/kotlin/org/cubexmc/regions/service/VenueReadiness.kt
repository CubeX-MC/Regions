package org.cubexmc.regions.service

import org.cubexmc.regions.match.MatchSpawns
import org.cubexmc.regions.mode.GamePhase
import org.cubexmc.regions.model.RegionDefinition
import org.cubexmc.regions.model.RegionLifecycle
import org.cubexmc.regions.model.ValidationIssue
import org.cubexmc.regions.model.ValidationSeverity
import java.util.Locale

/** 一条"现在还开不了"的原因。[code] 同时是语言键后缀（`readiness.<code>`）。 */
data class ReadinessBlocker(
    val code: String,
    val args: Map<String, String> = emptyMap(),
    val stage: ReadinessStage,
)

/**
 * 阻塞发生在哪一层。顺序即展示优先级：配置没填完就没必要谈依赖，依赖不在就没必要谈人数。
 */
enum class ReadinessStage {
    /** 玩法必填项没填（创建向导要展示的就是这一层）。 */
    CONFIG,

    /** 配置本身校验不过（与发布页的红条目同源）。 */
    VALIDATION,

    /** 外部依赖不可用：来源插件、工会来源。 */
    DEPENDENCY,

    /** 场地状态：草稿未发布、被停用、冻结。 */
    LIFECYCLE,

    /** 上一局还在恢复，或这一局已经开打。 */
    MATCH,

    /** 人数不够或已满。 */
    ROSTER,
}

data class ReadinessReport(val blockers: List<ReadinessBlocker>) {
    val ready: Boolean get() = blockers.isEmpty()

    /** 卡片、按钮这些只有一行的地方显示最靠前的那条。 */
    val primary: ReadinessBlocker? get() = blockers.firstOrNull()

    fun of(stage: ReadinessStage): List<ReadinessBlocker> = blockers.filter { it.stage == stage }

    /**
     * 按层取阻塞项——**事实只算一遍，但各个页面关心的层不同**。
     *
     * 创建向导问的是"配置填完了吗"（[ReadinessStage.CONFIG]）；
     * 活动大厅问的是"现在能不能报名"，它不该因为向导意义上的必填项没填就把已发布的场地变灰——
     * 那是发布校验的职责（[ReadinessStage.VALIDATION]）。
     */
    fun blockedBy(vararg stages: ReadinessStage): List<ReadinessBlocker> =
        blockers.filter { stages.contains(it.stage) }

    fun primaryOf(vararg stages: ReadinessStage): ReadinessBlocker? = blockedBy(*stages).firstOrNull()

    companion object {
        /** 运行时能不能开一场：大厅与报名页用这几层。 */
        val RUNTIME_STAGES = arrayOf(
            ReadinessStage.VALIDATION,
            ReadinessStage.DEPENDENCY,
            ReadinessStage.LIFECYCLE,
            ReadinessStage.MATCH,
            ReadinessStage.ROSTER,
        )
    }
}

/**
 * 「这块场地现在能不能开一场，不能的话缺什么」——**唯一出处**。
 *
 * 在此之前这个问题有三份各自为政的答案：活动大厅的灰卡原因、创建向导的必填项、
 * 发布页的校验条目。同一个场地在三处可能给出互相矛盾的说法，玩家看到"可报名"、
 * 场地主却在发布页看到红条，这种事只能靠人肉对账发现。
 *
 * 纯函数：所有外部事实（依赖可用性、比赛状态、校验结果）都由调用方查好传进来，
 * 因此三个页面共用同一份判定，也能脱离服务器单测。
 */
object VenueReadiness {

    /** 玩法必填项：没有它就开不了赛（与创建向导阶段 3 展示的是同一份）。 */
    fun requiredFields(region: RegionDefinition): List<RequiredField> {
        val values = region.mode?.values.orEmpty()
        return fieldsFor(region.mode?.type).map { field ->
            RequiredField(field, filled = field.isFilled(values))
        }
    }

    fun evaluate(
        region: RegionDefinition,
        configIssues: List<ValidationIssue> = emptyList(),
        sourceAvailable: Boolean = true,
        sourceLabel: String = region.source.type,
        unionsAvailable: Boolean = true,
        restoring: Boolean = false,
        phase: GamePhase = GamePhase.IDLE,
        players: Int = 0,
        requirePublished: Boolean = true,
    ): ReadinessReport {
        val blockers = ArrayList<ReadinessBlocker>()

        for (field in requiredFields(region)) {
            if (!field.filled) {
                blockers += ReadinessBlocker("missing-" + field.field.code, stage = ReadinessStage.CONFIG)
            }
        }

        configIssues.asSequence()
            .filter { it.severity == ValidationSeverity.ERROR }
            .forEach { issue ->
                blockers += ReadinessBlocker(
                    "invalid",
                    mapOf("code" to issue.code) + issue.args,
                    ReadinessStage.VALIDATION,
                )
            }

        if (!sourceAvailable) {
            blockers += ReadinessBlocker("source-unavailable", mapOf("source" to sourceLabel), ReadinessStage.DEPENDENCY)
        }
        if (region.mode?.type.equals("union_war", ignoreCase = true) && !unionsAvailable) {
            blockers += ReadinessBlocker("unions-unavailable", stage = ReadinessStage.DEPENDENCY)
        }

        if (!region.enabled) {
            blockers += ReadinessBlocker("disabled", stage = ReadinessStage.LIFECYCLE)
        }
        when (region.lifecycle) {
            RegionLifecycle.ARCHIVED -> blockers += ReadinessBlocker("archived", stage = ReadinessStage.LIFECYCLE)
            RegionLifecycle.FROZEN -> blockers += ReadinessBlocker("frozen", stage = ReadinessStage.LIFECYCLE)
            RegionLifecycle.DRAFT ->
                if (requirePublished) blockers += ReadinessBlocker("unpublished", stage = ReadinessStage.LIFECYCLE)

            RegionLifecycle.PUBLISHED -> Unit
        }
        if (requirePublished && region.publishedRevision == null && region.lifecycle == RegionLifecycle.PUBLISHED) {
            blockers += ReadinessBlocker("unpublished", stage = ReadinessStage.LIFECYCLE)
        }

        if (restoring) {
            blockers += ReadinessBlocker("restoring", stage = ReadinessStage.MATCH)
        }
        if (phase == GamePhase.RUNNING) {
            blockers += ReadinessBlocker("running", stage = ReadinessStage.MATCH)
        }

        val maxPlayers = region.mode?.values?.get("max-players")?.toIntOrNull() ?: 0
        if (maxPlayers > 0 && players >= maxPlayers) {
            blockers += ReadinessBlocker("full", mapOf("max" to maxPlayers.toString()), ReadinessStage.ROSTER)
        }

        return ReadinessReport(blockers.sortedBy { it.stage.ordinal })
    }

    private fun fieldsFor(modeType: String?): List<ReadinessField> =
        when (modeType?.lowercase(Locale.ROOT)) {
            "dual_pvp" -> listOf(ReadinessField.RESPAWN, ReadinessField.SPAWNS, ReadinessField.KIT)
            "union_war" -> listOf(ReadinessField.RESPAWN, ReadinessField.SPAWNS, ReadinessField.TEAM_SPAWNS, ReadinessField.KIT)
            "free_for_all" -> listOf(ReadinessField.RESPAWN, ReadinessField.SPAWNS, ReadinessField.KIT)
            "hide_and_seek" -> listOf(ReadinessField.RESPAWN)
            "run_race", "boat_race", "horse_race" -> listOf(ReadinessField.START, ReadinessField.FINISH)
            else -> emptyList()
        }

    data class RequiredField(val field: ReadinessField, val filled: Boolean)
}

/** 必填项类别；[code] 同时用于语言键（`labels.field.<code>` 与 `readiness.missing-<code>`）。 */
enum class ReadinessField(val code: String) {
    RESPAWN("respawn"),
    SPAWNS("spawns"),
    TEAM_SPAWNS("team-spawns"),
    KIT("kit"),
    START("start"),
    FINISH("finish"),
    ;

    fun isFilled(values: Map<String, String>): Boolean = when (this) {
        RESPAWN -> !values["respawn"].isNullOrBlank() || !values["outside"].isNullOrBlank()
        SPAWNS -> MatchSpawns.parseList(values["spawn-points"]).isNotEmpty()
        TEAM_SPAWNS -> MatchSpawns.parseList(values["spawn-points-b"]).isNotEmpty()
        KIT -> !values["kit"].isNullOrBlank() || !values["armor"].isNullOrBlank() ||
            values["replace-gear"]?.toBooleanStrictOrNull() == false
        START -> !values["start"].isNullOrBlank()
        FINISH -> !values["finish"].isNullOrBlank()
    }
}
