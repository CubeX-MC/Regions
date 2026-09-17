package org.cubexmc.regions.gui

import org.cubexmc.regions.model.ModeConfig
import org.cubexmc.regions.model.RegionDefinition
import org.cubexmc.regions.model.RegionSourceRef
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.random.Random

class CreationWizardTest {

    @Test
    fun `auto ids match the region id rule and avoid taken ids`() {
        val seeded = Random(42)
        val taken = setOf("arena-abc123")
        val id = AutoRegionId.generate(taken, seeded)
        assertTrue(id.matches(Regex("[a-z0-9_-]{2,48}")), id)
        assertTrue(id.startsWith("arena-"), id)
        assertNotEquals("arena-abc123", id)
    }

    @Test
    fun `drafts are per operator and overwriting replaces the mode choice`() {
        val drafts = WizardDrafts()
        val alice = UUID.randomUUID()
        val bob = UUID.randomUUID()

        assertNull(drafts.get(alice))
        drafts.start(alice, "dual_pvp")
        drafts.start(bob, "run_race")
        assertEquals("dual_pvp", drafts.get(alice)?.modeType)
        assertEquals("run_race", drafts.get(bob)?.modeType)

        drafts.start(alice, "union_war")
        assertEquals("union_war", drafts.get(alice)?.modeType)

        drafts.clear(alice)
        assertNull(drafts.get(alice))
        assertEquals("run_race", drafts.get(bob)?.modeType)
    }

    @Test
    fun `picking a mode starts at the area stage and remembers the draft it created`() {
        val drafts = WizardDrafts()
        val operator = UUID.randomUUID()

        val started = drafts.start(operator, "dual_pvp")
        assertEquals(WizardStage.AREA, started.stage)
        assertNull(started.regionId)

        // 阶段 2 完成：记住草稿 ID 与期望 revision，进入阶段 3。
        drafts.enterSettings(operator, "arena-abc123", 4)
        assertEquals(WizardStage.SETTINGS, drafts.get(operator)?.stage)
        assertEquals("arena-abc123", drafts.get(operator)?.regionId)
        assertEquals(4L, drafts.get(operator)?.revision)

        // 阶段 3 每次保存后版本前进，下一次写入才不会被并发守卫拒绝。
        drafts.syncRevision(operator, 5)
        assertEquals(5L, drafts.get(operator)?.revision)
    }

    @Test
    fun `each mode only asks for the fields it actually needs`() {
        val duel = WizardRequiredFields.of(region("dual_pvp"))
        assertTrue(duel.fields.contains(WizardField.SPAWNS))
        // 决斗不需要乙方出生点，也不该在阶段 3 出现这一格。
        assertTrue(!duel.fields.contains(WizardField.TEAM_SPAWNS))
        // 没有任何点位/装备/返回点时，必填项如实报缺。
        assertTrue(duel.missing.contains(WizardField.RESPAWN))
        assertTrue(duel.missing.contains(WizardField.SPAWNS))
        assertTrue(duel.missing.contains(WizardField.KIT))

        val war = WizardRequiredFields.of(region("union_war"))
        assertTrue(war.fields.contains(WizardField.TEAM_SPAWNS))
        assertTrue(war.fields.contains(WizardField.ROSTER))

        // free_event 没有必填项：阶段 3 不拦人。
        val freeEvent = WizardRequiredFields.of(region("free_event"))
        assertTrue(freeEvent.fields.isEmpty())
        assertTrue(freeEvent.missing.isEmpty())
    }

    @Test
    fun `a fully configured combat venue reports nothing missing`() {
        val configured = region(
            "dual_pvp",
            mapOf(
                "respawn" to "world,0,64,0",
                "spawn-points" to "world,0,64,0;world,10,64,0",
                "kit" to "IRON_SWORD:1",
            ),
        )

        val required = WizardRequiredFields.of(configured)

        assertTrue(required.missing.isEmpty(), "still missing: ${required.missing}")
    }

@Test
    fun `the wizard only offers templates for the mode picked in stage one`() {
        val templates = listOf(template("pvp", "dual_pvp"), template("race", "run_race"))

        // 选了玩法：只给这个玩法的模板，避免"选工会战套上竞速模板"。
        assertEquals(
            listOf("pvp"),
            WizardTemplates.matching(templates, "dual_pvp", TemplatePurpose.CREATE).map { it.id },
        )
        // 没有这一步（给已有场地换模板）时不筛选。
        assertEquals(
            listOf("pvp", "race"),
            WizardTemplates.matching(templates, "dual_pvp", TemplatePurpose.APPLY).map { it.id },
        )
        // 该玩法一个模板都没有：返回空，调用方据此走"按默认值创建"。
        assertTrue(WizardTemplates.matching(templates, "free_for_all", TemplatePurpose.CREATE).isEmpty())
    }

    private fun template(id: String, modeType: String) = org.cubexmc.regions.service.RegionTemplate(
        id = id,
        name = id,
        description = "",
        parameters = emptyMap(),
        mode = ModeConfig(modeType),
    )

    private fun region(type: String, values: Map<String, String> = emptyMap()) = RegionDefinition(
        id = "arena",
        name = "Arena",
        source = RegionSourceRef("cuboid"),
        mode = ModeConfig(type, values),
    )
}
