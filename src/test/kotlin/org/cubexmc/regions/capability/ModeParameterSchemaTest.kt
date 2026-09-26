package org.cubexmc.regions.capability

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

/**
 * 参数模型的门禁。
 *
 * 在此之前 8 种玩法共用一份 40 多键的参数袋且 `strict = false`：决斗能设
 * `seeker-ratio`，竞速能设 `kit=DIAMOND_SWORD`（校验通过、发布成功、运行时被
 * 完全忽略）。这些用例就是为了让那种情况**再也编译不过测试**。
 */
class ModeParameterSchemaTest {

    private fun catalog(): CapabilityCatalog =
        CapabilityCatalog().also { BuiltInRegionCapabilities.registerAll(it) }

    private fun issues(mode: String, values: Map<String, String>) =
        catalog().validate(CapabilityKind.MODE, mode, values)

    private fun accepts(mode: String, key: String, value: String): Boolean =
        issues(mode, mapOf(key to value)).none { it.code == "parameter-unknown" }

    @Test
    fun `every registered mode is strict and has its own parameter set`() {
        val catalog = catalog()
        for (mode in ModeParameterSchema.ALL_MODES) {
            val descriptor = catalog.find(CapabilityKind.MODE, mode)
            assertTrue(descriptor != null, "玩法 $mode 没有 descriptor")
            assertTrue(
                descriptor!!.strictParameters,
                "玩法 $mode 不是严格校验：未知参数会被静默放行",
            )
            assertTrue(
                descriptor.parameters.isNotEmpty(),
                "玩法 $mode 没有声明任何参数",
            )
        }
    }

    @Test
    fun `modes no longer share one parameter bag`() {
        val catalog = catalog()
        val keysByMode = ModeParameterSchema.ALL_MODES.associateWith { mode ->
            catalog.find(CapabilityKind.MODE, mode)!!.parameters.map { it.key }.toSet()
        }
        assertEquals(
            keysByMode.size,
            ModeParameterSchema.ALL_MODES.size,
            "玩法清单里有重复项",
        )
        // 决斗与竞速如果参数集合完全一样，说明又退回了"一个大袋子"。
        assertTrue(
            keysByMode.getValue("dual_pvp") != keysByMode.getValue("run_race"),
            "决斗与竞速的参数集合相同，参数袋又被合并了",
        )
        assertTrue(
            keysByMode.getValue("free_event").size < keysByMode.getValue("dual_pvp").size,
            "自由活动不该和决斗接受一样多的参数——它根本没有比赛",
        )
    }

    /**
     * 每一条都是历史上**真实通过过**校验的错误组合。
     * `true` = 该玩法真的读这个键；`false` = 必须在校验阶段被点名。
     */
    @TestFactory
    fun `parameters are accepted only by the modes that actually read them`(): List<DynamicTest> {
        val cases = listOf(
            // 决斗：回合制参数是它的，捉迷藏与竞速的都不是。
            Triple("dual_pvp", "best-of" to "3", true),
            Triple("dual_pvp", "kit" to "IRON_SWORD:1", true),
            Triple("dual_pvp", "seeker-ratio" to "0.2", false),
            Triple("dual_pvp", "checkpoint-vehicles" to "boat", false),
            Triple("dual_pvp", "team-size" to "5", false),

            // 工会战：分队与外交是它的，决斗的 best-of 不是。
            Triple("union_war", "team-size" to "5", true),
            Triple("union_war", "spawn-points-b" to "world,0,64,0", true),
            Triple("union_war", "diplomacy" to "enemy-only", true),
            Triple("union_war", "best-of" to "3", false),
            Triple("union_war", "hide-seconds" to "30", false),

            // 大乱斗：奖励键刻意保留在 schema 里，由校验器给出更具体的拒绝理由。
            Triple("free_for_all", "timeout-seconds" to "600", true),
            Triple("free_for_all", "reward-source" to "contract", true),
            Triple("free_for_all", "best-of" to "3", false),

            // 竞速：装备键现在**真的**生效，所以必须被接受；战斗与捉迷藏的参数不属于它。
            Triple("run_race", "kit" to "IRON_SWORD:1", true),
            Triple("run_race", "armor" to "IRON_HELMET:1", true),
            Triple("run_race", "offhand" to "SHIELD:1", true),
            Triple("run_race", "replace-gear" to "true", true),
            Triple("boat_race", "checkpoint-vehicles" to "boat", true),
            Triple("horse_race", "start-vehicle" to "horse", true),
            Triple("run_race", "best-of" to "3", false),
            Triple("run_race", "seekers" to "1", false),
            Triple("run_race", "spawn-points" to "world,0,64,0", false),

            // 捉迷藏：分角色套件是它的，赛道与出生点不是。
            Triple("hide_and_seek", "seeker-kit" to "IRON_SWORD:1", true),
            Triple("hide_and_seek", "hider-kit" to "COOKED_BEEF:8", true),
            Triple("hide_and_seek", "armor" to "IRON_HELMET:1", true),
            Triple("hide_and_seek", "seeker-ratio" to "0.3", true),
            Triple("hide_and_seek", "checkpoints" to "world,0,64,0", false),
            Triple("hide_and_seek", "spawn-points" to "world,0,64,0", false),

            // 自由活动没有比赛：除了触发动作能引用的返回点，什么都不该收。
            Triple("free_event", "respawn" to "world,0,64,0", true),
            Triple("free_event", "kit" to "IRON_SWORD:1", false),
            Triple("free_event", "min-players" to "2", false),
        )
        return cases.map { (mode, entry, expected) ->
            val (key, value) = entry
            val verb = if (expected) "接受" else "拒绝"
            DynamicTest.dynamicTest("$mode 应$verb $key") {
                assertEquals(
                    expected,
                    accepts(mode, key, value),
                    if (expected) {
                        "$mode 读取 $key，但 schema 没声明——服务会读到一个校验拒绝的键"
                    } else {
                        "$mode 不读 $key，schema 却放行了——这正是静默失败的来源"
                    },
                )
            }
        }
    }

    @Test
    fun `the three historic timeout spellings stay interchangeable for races`() {
        for (key in listOf("timeout-seconds", "max-duration-seconds", "duration-seconds")) {
            assertTrue(accepts("run_race", key, "300"), "竞速应接受历史键 $key")
        }
    }

    @Test
    fun `the return point keeps its historic aliases`() {
        for (key in listOf("respawn", "outside", "spectator")) {
            assertTrue(accepts("dual_pvp", key, "world,0,64,0"), "返回点别名 $key 被拒绝了")
        }
    }

    @Test
    fun `value ranges are still enforced alongside the narrower key sets`() {
        assertFalse(
            issues("dual_pvp", mapOf("best-of" to "5")).isEmpty(),
            "best-of 超出 1..3 应该报错",
        )
        assertFalse(
            issues("union_war", mapOf("team-size" to "99")).isEmpty(),
            "team-size 超出 2..10 应该报错",
        )
        assertFalse(
            issues("run_race", mapOf("vehicle" to "spaceship")).isEmpty(),
            "未知载具应该报错",
        )
    }
}
