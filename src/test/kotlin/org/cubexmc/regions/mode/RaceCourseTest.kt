package org.cubexmc.regions.mode

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 赛道纯逻辑的穷举测试。
 *
 * 载具约束有二十多个历史别名、检查点可以逐点指定、超时有三个同义键——
 * 这些靠读代码保证不了，而它们一旦错就是"比赛判不了终点"这种级别的问题。
 */
class RaceCourseTest {

    @Test
    fun `on-foot aliases all mean the same thing`() {
        for (alias in listOf("none", "on_foot", "on-foot", "no_vehicle", "no-vehicle", "foot")) {
            assertTrue(RaceCourse.matches(null, alias), "$alias 应当允许步行")
            assertFalse(RaceCourse.matches("OAK_BOAT", alias), "$alias 不该允许坐船")
        }
    }

    @Test
    fun `ignore aliases never block anyone`() {
        for (alias in listOf("pass", "ignore", "any_state", "any-state")) {
            assertTrue(RaceCourse.matches(null, alias))
            assertTrue(RaceCourse.matches("HORSE", alias))
        }
    }

    @Test
    fun `any-vehicle requires a vehicle but does not care which`() {
        for (alias in listOf("any", "vehicle", "any_vehicle", "any-vehicle")) {
            assertFalse(RaceCourse.matches(null, alias))
            assertTrue(RaceCourse.matches("MINECART", alias))
        }
    }

    /**
     * 原版把船和马拆成了一堆类型名。服主写 `vehicle: boat` 想要的是"一条船"，
     * 不是"恰好是 OAK_BOAT"——精确匹配会让所有非橡木船的选手永远过不了终点。
     */
    @Test
    fun `boat and horse match every vanilla variant`() {
        for (boat in listOf("OAK_BOAT", "ACACIA_BOAT", "BAMBOO_RAFT", "ACACIA_CHEST_BOAT")) {
            assertTrue(RaceCourse.matches(boat, "boat"), "$boat 应当算作船")
        }
        for (horse in listOf("HORSE", "ZOMBIE_HORSE", "SKELETON_HORSE")) {
            assertTrue(RaceCourse.matches(horse, "horse"), "$horse 应当算作马")
        }
        assertFalse(RaceCourse.matches("PIG", "horse"))
        assertTrue(RaceCourse.matches("TRADER_LLAMA", "llama"))
    }

    @Test
    fun `specific constraints outside the alias table match by exact type`() {
        assertTrue(RaceCourse.matches("STRIDER", "strider"))
        assertFalse(RaceCourse.matches("STRIDER", "camel"))
        assertFalse(RaceCourse.matches(null, "strider"))
    }

    @Test
    fun `mode defaults apply when nothing is configured`() {
        assertEquals("none", RaceCourse.defaultConstraint("run_race"))
        assertEquals("boat", RaceCourse.defaultConstraint("boat_race"))
        assertEquals("horse", RaceCourse.defaultConstraint("horse_race"))
        assertEquals("pass", RaceCourse.defaultConstraint("free_event"))
    }

    @Test
    fun `stage constraint resolution follows per-point then stage then global then default`() {
        val values = mapOf(
            "vehicle" to "any",
            "start-vehicle" to "none",
            "checkpoint-vehicles" to "boat;horse",
        )
        assertEquals(
            "none",
            RaceCourse.constraintFor(values, "boat_race", RaceCourse.Stage.START),
            "阶段键应当压过全局键",
        )
        assertEquals(
            "boat",
            RaceCourse.constraintFor(values, "boat_race", RaceCourse.Stage.CHECKPOINT, 0),
        )
        assertEquals(
            "horse",
            RaceCourse.constraintFor(values, "boat_race", RaceCourse.Stage.CHECKPOINT, 1),
        )
        assertEquals(
            "any",
            RaceCourse.constraintFor(values, "boat_race", RaceCourse.Stage.CHECKPOINT, 2),
            "逐点清单用完后回落到全局键",
        )
        assertEquals(
            "any",
            RaceCourse.constraintFor(values, "boat_race", RaceCourse.Stage.FINISH),
        )
        assertEquals(
            "boat",
            RaceCourse.constraintFor(emptyMap(), "boat_race", RaceCourse.Stage.FINISH),
            "什么都没配时用玩法默认值",
        )
    }

    @Test
    fun `checkpoint vehicle lists accept both separators`() {
        assertEquals(listOf("boat", "horse"), RaceCourse.parseConstraintList("boat;horse"))
        assertEquals(listOf("boat", "horse"), RaceCourse.parseConstraintList("boat,horse"))
        assertEquals(emptyList<String>(), RaceCourse.parseConstraintList(""))
        assertEquals(emptyList<String>(), RaceCourse.parseConstraintList(null))
    }

    @Test
    fun `the three timeout spellings are equivalent and zero means unlimited`() {
        assertEquals(120L, RaceCourse.timeoutSeconds(mapOf("timeout-seconds" to "120")))
        assertEquals(120L, RaceCourse.timeoutSeconds(mapOf("max-duration-seconds" to "120")))
        assertEquals(120L, RaceCourse.timeoutSeconds(mapOf("duration-seconds" to "120")))
        assertEquals(
            RaceCourse.DEFAULT_TIMEOUT_SECONDS,
            RaceCourse.timeoutSeconds(emptyMap()),
        )
        assertEquals(0L, RaceCourse.timeoutSeconds(mapOf("timeout-seconds" to "0")))
        // 负数不该变成"立刻超时"。
        assertEquals(0L, RaceCourse.timeoutSeconds(mapOf("timeout-seconds" to "-5")))
    }

    @Test
    fun `radius falls back from the specific key to the shared one`() {
        val values = mapOf("radius" to "3.0", "finish-radius" to "1.5")
        assertEquals(1.5, RaceCourse.radius(values, "finish-radius"))
        assertEquals(3.0, RaceCourse.radius(values, "start-radius"))
        assertEquals(RaceCourse.DEFAULT_RADIUS, RaceCourse.radius(emptyMap(), "start-radius"))
    }

    @Test
    fun `vote threshold never drops below one player`() {
        assertEquals(1, RaceCourse.requiredVotes(mapOf("vote-start-percent" to "0.0"), 4))
        assertEquals(2, RaceCourse.requiredVotes(mapOf("vote-start-percent" to "0.5"), 4))
        assertEquals(4, RaceCourse.requiredVotes(emptyMap(), 4))
    }

    @Test
    fun `known constraints render from the language file and custom ones stay literal`() {
        assertEquals("gui.vehicle.boat", RaceCourse.constraintLabelKey("boat"))
        assertEquals("gui.vehicle.on-foot", RaceCourse.constraintLabelKey("no-vehicle"))
        // 服主自己写的载具类型不是内部术语，不该被当成语言键去解析。
        assertEquals(null, RaceCourse.constraintLabelKey("my_custom_mount"))
    }
}
