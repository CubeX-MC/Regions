package org.cubexmc.regions.gui

import java.util.UUID
import kotlin.random.Random
import java.util.concurrent.ConcurrentHashMap

/** 向导阶段（PLAN.md §5.2 的 4 个阶段；发布页是既有的检查发布页）。 */
enum class WizardStage { MODE, AREA, SETTINGS, PUBLISH }

/**
 * 一个操作者进行中的创建向导状态（PLAN.md §5.2：「向导状态以操作者 UUID、draft ID、
 * 预期 revision 和步骤保存；关闭 GUI 可继续」）。
 *
 * [revision] 是阶段之间的期望草稿版本：阶段 3 保存时带上它，别人在这期间改过草稿就会被
 * [org.cubexmc.regions.service.RegionPublishingService.saveDraft] 拒绝，而不是覆盖掉对方的修改。
 */
data class WizardDraft(
    val modeType: String,
    val stage: WizardStage = WizardStage.MODE,
    val regionId: String? = null,
    val revision: Long = 0L,
)

/** 按操作者保存的向导状态；重新点击玩法卡会从阶段 1 重开。 */
class WizardDrafts {
    private val states = ConcurrentHashMap<UUID, WizardDraft>()

    fun start(playerId: UUID, modeType: String): WizardDraft =
        WizardDraft(modeType, WizardStage.AREA).also { states[playerId] = it }

    /** 阶段 2 完成：记住新建出来的草稿 ID 与版本，进入阶段 3。 */
    fun enterSettings(playerId: UUID, regionId: String, revision: Long): WizardDraft? {
        val current = states[playerId] ?: return null
        return current.copy(stage = WizardStage.SETTINGS, regionId = regionId, revision = revision)
            .also { states[playerId] = it }
    }

    /** 阶段 3 保存成功：把期望版本推进到新草稿的 revision。 */
    fun syncRevision(playerId: UUID, revision: Long) {
        states.computeIfPresent(playerId) { _, draft -> draft.copy(revision = revision) }
    }

    fun get(playerId: UUID): WizardDraft? = states[playerId]

    fun clear(playerId: UUID) {
        states.remove(playerId)
    }
}

/**
 * 自动生成合法、唯一、稳定的场地 ID（PLAN.md §5.2 阶段 2：`arena-xxxxxx`，
 * 命中 [org.cubexmc.regions.service.RegionValidationService] 的 ID 规则，无需玩家手写 ASCII）。
 */
object AutoRegionId {
    private const val CHARS = "0123456789abcdefghijklmnopqrstuvwxyz"
    private val SUFFIX_LENGTH = 6
    private val random = Random.Default

    fun generate(taken: Set<String>, random: Random = this.random): String {
        repeat(64) {
            val suffix = buildString {
                repeat(SUFFIX_LENGTH) { append(CHARS[random.nextInt(CHARS.length)]) }
            }
            val candidate = "arena-$suffix"
            if (candidate !in taken) return candidate
        }
        throw IllegalStateException("Could not allocate a unique region id; the id space is exhausted.")
    }
}

/** M2.3：校验错误码 → 发布页"立即修复"跳转目标；无 GUI 修复入口的码返回 null。 */
internal enum class PublishFixTarget {
    MODE,
    RULES,
    EFFECTS,
    TRIGGERS,
    SOURCE,
    ;

    companion object {
        private val MODE_EXACT = setOf(
            "max-players-below-min",
            "min-players-below",
            "min-unions-above-players",
            "race-timeout-invalid",
            "checkpoint-vehicles-mismatch",
            "round-seconds-not-after-hide",
            "seekers-above-min-players",
            "item-list-too-many",
            "item-entry-invalid",
            "location-required",
            "location-invalid",
            "world-unloaded",
        )

        fun from(code: String): PublishFixTarget? = when {
            code.startsWith("source-") -> SOURCE
            code.startsWith("flag-") -> RULES
            code.startsWith("effect-") || code.startsWith("potion-") -> EFFECTS
            code.startsWith("trigger-") || code.startsWith("condition-") ||
                code.startsWith("action-") || code.startsWith("sound-") -> TRIGGERS
            code.startsWith("mode-") || code.startsWith("parameter-") || code in MODE_EXACT -> MODE
            else -> null
        }
    }
}

/**
 * 阶段 1 选了玩法之后，阶段 2 的模板列表就只应该给出这个玩法的模板。
 *
 * 此前列表是全部模板，于是"选了工会战却套上竞速模板"完全可能，也会让没有模板的新玩法
 * 看起来毫无创建入口。
 */
internal object WizardTemplates {

    fun matching(
        templates: List<org.cubexmc.regions.service.RegionTemplate>,
        modeType: String?,
        purpose: TemplatePurpose,
    ): List<org.cubexmc.regions.service.RegionTemplate> {
        if (modeType == null || purpose != TemplatePurpose.CREATE) return templates
        return templates.filter { it.mode?.type.equals(modeType, ignoreCase = true) }
    }
}
