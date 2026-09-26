package org.cubexmc.regions.service

import org.cubexmc.regions.model.RegionDefinition
import org.cubexmc.regions.model.RegionSourceRef
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 用名字指代场地（2026-09-19 实机反馈：「活动 id 要暴露给用户吗？直接用领地名不就好了」）。
 *
 * 结论是：ID 留作内部主键，但用户侧一律说名字。这里锁住解析规则，尤其是
 * **命中多个必须报歧义**——同一块领地上允许叠一个无状态 Region，校验只禁止有状态玩法重叠。
 */
class RegionLookupTest {

    private val arena = region("arena-7f3a91", "决斗场", land = "北境竞技场")
    private val ambience = region("amb-221b0c", "北境氛围", land = "北境竞技场")
    private val race = region("race-8812aa", "环湖赛道", land = "湖畔", area = "赛道")
    private val cuboid = region("box-99", "临时擂台", land = null)
    private val all = listOf(race, arena, cuboid, ambience)

    @Test
    fun `the internal id still resolves for scripts and audit copy-paste`() {
        assertEquals(RegionLookup.Resolution.Found(arena), RegionLookup.resolve("arena-7f3a91", all))
        assertEquals(RegionLookup.Resolution.Found(arena), RegionLookup.resolve("ARENA-7F3A91", all))
    }

    @Test
    fun `a venue name resolves`() {
        assertEquals(RegionLookup.Resolution.Found(arena), RegionLookup.resolve("决斗场", all))
        assertEquals(RegionLookup.Resolution.Found(race), RegionLookup.resolve("环湖赛道", all))
    }

    @Test
    fun `a lands name resolves, including land slash area`() {
        assertEquals(RegionLookup.Resolution.Found(race), RegionLookup.resolve("湖畔/赛道", all))
        assertEquals(RegionLookup.Resolution.Found(race), RegionLookup.resolve("湖畔", all))
    }

    @Test
    fun `one land carrying two regions is reported as ambiguous instead of guessed`() {
        val resolution = RegionLookup.resolve("北境竞技场", all)
        assertTrue(resolution is RegionLookup.Resolution.Ambiguous, resolution.toString())
        assertEquals(
            listOf(ambience.id, arena.id).sorted(),
            (resolution as RegionLookup.Resolution.Ambiguous).matches.map { it.id }.sorted(),
        )
    }

    @Test
    fun `numbers pick from the listed order`() {
        val ordered = RegionLookup.ordered(all)
        assertEquals(RegionLookup.Resolution.Found(ordered[0]), RegionLookup.resolve("1", all))
        assertEquals(RegionLookup.Resolution.Found(ordered[3]), RegionLookup.resolve("4", all))
    }

    @Test
    fun `display pairs the venue name with its land, without repeating itself`() {
        assertEquals("决斗场 (北境竞技场)", RegionLookup.display(arena))
        assertEquals("环湖赛道 (湖畔/赛道)", RegionLookup.display(race))
        assertEquals("临时擂台", RegionLookup.display(cuboid), "cuboid 来源没有领地名可缀")
        val sameName = region("x", "湖畔", land = "湖畔")
        assertEquals("湖畔", RegionLookup.display(sameName), "名字和领地同名时不要显示两遍")
    }

    @Test
    fun `unknown input is simply not found`() {
        assertEquals(RegionLookup.Resolution.NotFound, RegionLookup.resolve("不存在的场地", all))
        assertEquals(RegionLookup.Resolution.NotFound, RegionLookup.resolve("99", all))
        assertEquals(RegionLookup.Resolution.NotFound, RegionLookup.resolve(" ", all))
    }

    @Test
    fun `completions stay single tokens`() {
        val completions = RegionLookup.completions(all)
        assertTrue(completions.none { it.contains(' ') }, completions.toString())
        assertTrue(completions.contains("湖畔/赛道"), completions.toString())
    }

    private fun region(id: String, name: String, land: String?, area: String? = null): RegionDefinition =
        RegionDefinition(
            id = id,
            name = name,
            source = if (land == null) {
                RegionSourceRef("cuboid", mapOf("world" to "world"))
            } else {
                RegionSourceRef("lands", linkedMapOf("land" to land, "area" to (area ?: "default")))
            },
        )
}
