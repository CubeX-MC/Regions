package org.cubexmc.regions.service

import org.cubexmc.regions.mode.GamePhase
import org.cubexmc.regions.model.ModeConfig
import org.cubexmc.regions.model.RegionDefinition
import org.cubexmc.regions.model.RegionLifecycle
import org.cubexmc.regions.model.RegionSourceRef
import org.cubexmc.regions.model.ValidationIssue
import org.cubexmc.regions.model.ValidationSeverity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 「现在能不能开一场，不能的话缺什么」的唯一判定（2026-09-19 用户确认合并校验层）。
 *
 * 合并之前，活动大厅的灰卡原因、创建向导的必填项、发布页的红条各算各的，
 * 同一块场地可以在三处给出互相矛盾的说法。这里锁住的是：**同一份事实 → 同一份结论**，
 * 以及展示顺序（配置 → 依赖 → 生命周期 → 比赛 → 人数）。
 */
class VenueReadinessTest {

    @Test
    fun `a fully configured published duel is ready`() {
        assertTrue(VenueReadiness.evaluate(duel()).ready)
    }

    @Test
    fun `missing required fields are reported per field`() {
        val bare = duel(values = mapOf("min-players" to "2"))
        val report = VenueReadiness.evaluate(bare)

        assertFalse(report.ready)
        assertEquals(
            listOf("missing-respawn", "missing-spawns", "missing-kit"),
            report.of(ReadinessStage.CONFIG).map { it.code },
        )
    }

    @Test
    fun `self-supplied gear counts as a filled kit`() {
        // replace-gear: false = 自带装备，这时不该再要求配 kit。
        val byo = duel(values = duelValues() - "kit" + ("replace-gear" to "false"))
        assertTrue(VenueReadiness.evaluate(byo).ready, VenueReadiness.evaluate(byo).blockers.toString())
    }

    @Test
    fun `union war additionally needs the second team's spawns`() {
        val union = duel(mode = "union_war", values = duelValues() - "spawn-points-b")
        val report = VenueReadiness.evaluate(union)
        assertEquals(listOf("missing-team-spawns"), report.of(ReadinessStage.CONFIG).map { it.code })
    }

    @Test
    fun `validation errors show up as blockers, warnings do not`() {
        val issues = listOf(
            ValidationIssue("arena", ValidationSeverity.ERROR, "spawn-points-count"),
            ValidationIssue("arena", ValidationSeverity.WARNING, "overlap-flag-priority"),
        )
        val report = VenueReadiness.evaluate(duel(), configIssues = issues)

        assertEquals(1, report.of(ReadinessStage.VALIDATION).size)
        assertEquals("spawn-points-count", report.of(ReadinessStage.VALIDATION).single().args["code"])
    }

    @Test
    fun `dependency, lifecycle, match and roster blockers are ordered by stage`() {
        val report = VenueReadiness.evaluate(
            duel(values = duelValues() + ("max-players" to "4"), lifecycle = RegionLifecycle.FROZEN),
            sourceAvailable = false,
            restoring = true,
            phase = GamePhase.RUNNING,
            players = 4,
        )

        assertEquals(
            listOf(
                ReadinessStage.DEPENDENCY,
                ReadinessStage.LIFECYCLE,
                ReadinessStage.MATCH,
                ReadinessStage.MATCH,
                ReadinessStage.ROSTER,
            ),
            report.blockers.map { it.stage },
        )
        assertEquals("source-unavailable", report.primary?.code, "最靠前的原因才是卡片上该显示的那条")
    }

    @Test
    fun `a draft is only unpublished when the caller asks for a published venue`() {
        val draft = duel(lifecycle = RegionLifecycle.DRAFT)
        assertEquals(listOf("unpublished"), VenueReadiness.evaluate(draft).of(ReadinessStage.LIFECYCLE).map { it.code })
        // 创建向导问的是"配置填完没有"，草稿本身不算问题。
        assertTrue(VenueReadiness.evaluate(draft, requirePublished = false).ready)
    }

    @Test
    fun `a full venue is reported with its cap`() {
        val report = VenueReadiness.evaluate(duel(values = duelValues() + ("max-players" to "4")), players = 4)
        assertEquals("full", report.primary?.code)
        assertEquals("4", report.primary?.args?.get("max"))
    }

    private fun duelValues(): Map<String, String> = mapOf(
        "respawn" to "world,0,64,0",
        "spawn-points" to "world,10,64,10;world,20,64,20",
        "spawn-points-b" to "world,30,64,30",
        "kit" to "IRON_SWORD",
    )

    private fun duel(
        mode: String = "dual_pvp",
        values: Map<String, String> = duelValues(),
        lifecycle: RegionLifecycle = RegionLifecycle.PUBLISHED,
    ) = RegionDefinition(
        id = "arena",
        name = "决斗场",
        source = RegionSourceRef("lands", mapOf("land" to "北境", "area" to "default")),
        lifecycle = lifecycle,
        mode = ModeConfig(mode, values),
    )
}
