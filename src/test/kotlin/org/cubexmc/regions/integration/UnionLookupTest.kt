package org.cubexmc.regions.integration

import org.cubexmc.regions.model.UnionRef
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 工会输入的解析（2026-09-19 实机反馈：只能粘 26 位 ULID，太难用）。
 *
 * 目标是"好输入"而不是"猜得准"：命中多个一律报歧义并列出候选，绝不替服主挑一个——
 * 选错工会意味着整场工会战打在错误的两方之间，还牵扯 WAGER 收款方。
 */
class UnionLookupTest {

    private val red = UnionRef("01J9X8ABCDEFGHJKMNPQRSTVWX", "&c红色帝国", "lands")
    private val blue = UnionRef("01J9X8BBCDEFGHJKMNPQRSTVWX", "§9Blue Kingdom", "lands")
    private val blueGuard = UnionRef("01J9X8CBCDEFGHJKMNPQRSTVWX", "Blue Guard", "lands")
    private val numeric = UnionRef("01J9X8DBCDEFGHJKMNPQRSTVWX", "1", "lands")
    private val candidates = listOf(blueGuard, red, blue)

    @Test
    fun `color codes never reach the player`() {
        assertEquals("红色帝国", UnionLookup.plainName(red.name))
        assertEquals("Blue Kingdom", UnionLookup.plainName(blue.name))
    }

    @Test
    fun `the raw ulid still works for anyone who pasted it before`() {
        assertEquals(found(red), UnionLookup.resolve(red.id, candidates))
        assertEquals(found(red), UnionLookup.resolve(red.id.lowercase(), candidates))
    }

    @Test
    fun `a plain name matches regardless of colour and case`() {
        assertEquals(found(red), UnionLookup.resolve("红色帝国", candidates))
        assertEquals(found(blue), UnionLookup.resolve("blue kingdom", candidates))
        assertEquals(found(blue), UnionLookup.resolve("Blue_Kingdom", candidates))
    }

    @Test
    fun `a unique prefix is enough`() {
        assertEquals(found(red), UnionLookup.resolve("红色", candidates))
        assertEquals(found(blueGuard), UnionLookup.resolve("blue_g", candidates))
    }

    @Test
    fun `an ambiguous prefix lists the candidates instead of guessing`() {
        val resolution = UnionLookup.resolve("blue", candidates)
        assertTrue(resolution is UnionLookup.Resolution.Ambiguous, resolution.toString())
        val matches = (resolution as UnionLookup.Resolution.Ambiguous).matches.map { it.id }
        assertTrue(matches.containsAll(listOf(blue.id, blueGuard.id)), matches.toString())
    }

    @Test
    fun `numbers pick from the listed order`() {
        val ordered = UnionLookup.ordered(candidates)
        assertEquals(found(ordered[0]), UnionLookup.resolve("1", candidates))
        assertEquals(found(ordered[2]), UnionLookup.resolve("3", candidates))
    }

    @Test
    fun `a nation actually named 1 wins over the index`() {
        val withNumericName = candidates + numeric
        assertEquals(found(numeric), UnionLookup.resolve("1", withNumericName))
    }

    @Test
    fun `out of range numbers and unknown names are simply not found`() {
        assertEquals(UnionLookup.Resolution.NotFound, UnionLookup.resolve("99", candidates))
        assertEquals(UnionLookup.Resolution.NotFound, UnionLookup.resolve("0", candidates))
        assertEquals(UnionLookup.Resolution.NotFound, UnionLookup.resolve("nope", candidates))
        assertEquals(UnionLookup.Resolution.NotFound, UnionLookup.resolve("  ", candidates))
    }

    @Test
    fun `completions are single tokens so command arguments do not split`() {
        val completions = UnionLookup.completions(candidates)
        assertTrue(completions.none { it.contains(' ') }, completions.toString())
        assertTrue(completions.containsAll(listOf("blue_guard", "blue_kingdom", "红色帝国")), completions.toString())
    }

    private fun found(union: UnionRef) = UnionLookup.Resolution.Found(union)
}
