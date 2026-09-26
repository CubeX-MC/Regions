package org.cubexmc.regions.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RegionBaselineTest {

    @Test
    fun `every baseline file gets a migration plan`() {
        val plans = RegionBaseline.plans()

        assertEquals(RegionBaseline.files.size, plans.size)
        assertEquals(
            RegionBaseline.files.map { it.path }.toSet(),
            plans.map { it.resourcePath() }.toSet(),
        )
    }

    @Test
    fun `each plan carries that file's own version key and target version`() {
        val byPath = RegionBaseline.plans().associateBy { it.resourcePath() }

        for (baseline in RegionBaseline.files) {
            val plan = byPath.getValue(baseline.path)
            assertEquals(baseline.versionKey, plan.versionKey(), baseline.path)
            assertEquals(baseline.version, plan.targetVersion(), baseline.path)
        }
    }

    @Test
    fun `a file with no version key is treated as already at the baseline`() {
        // 首个公开版本就是起点:没有版本键的文件是刚生成的默认文件,不该被当成"更旧的格式"。
        for (plan in RegionBaseline.plans()) {
            assertEquals(plan.targetVersion(), plan.missingVersion(), plan.resourcePath())
        }
    }

    @Test
    fun `baseline plans carry exactly the migration steps for their format changes`() {
        // 旧断言是"所有迁移步骤必须为空"，在语言 6→7、模板 1→2 落地时按计划替换：
        // 每个改过格式的文件必须有对应步骤，且步骤自声明版本区间。
        for (plan in RegionBaseline.plans()) {
            when {
                plan.resourcePath().startsWith("lang/") -> {
                    // 语言文件是一条单向链：6→7（模板确认 GUI 补键）→ 8（补齐 v7 之后新增的
                    // labels/errors 等叶子键）→ 9（工会选队的新文案）→ 10（七种玩法补齐到
                    // 与决斗同级时新增的报名／结果／结束原因文案）。
                    // 断言整条链，避免以后有人只加版本号不加步骤。
                    val steps = plan.steps()
                    assertEquals(
                        listOf(6 to 7, 7 to 8, 8 to 9, 9 to 10, 10 to 11),
                        steps.map { it.fromVersion() to it.toVersion() },
                        plan.resourcePath(),
                    )
                    assertTrue(steps[0] is LangV6ToV7Step, plan.resourcePath())
                    assertTrue(steps[1] is LangV7ToV8Step, plan.resourcePath())
                    assertTrue(steps[2] is LangV8ToV9Step, plan.resourcePath())
                    assertTrue(steps[3] is LangV9ToV10Step, plan.resourcePath())
                    assertTrue(steps[4] is LangV10ToV11Step, plan.resourcePath())
                }
                plan.resourcePath() == "templates.yml" -> {
                    val step = plan.steps().single()
                    assertEquals(1, step.fromVersion(), plan.resourcePath())
                    assertEquals(2, step.toVersion(), plan.resourcePath())
                    assertTrue(step is TemplatesV1ToV2Step, plan.resourcePath())
                }
                else -> assertTrue(plan.steps().isEmpty(), plan.resourcePath())
            }
        }
    }

    @Test
    fun `the two language files share a version key but are migrated separately`() {
        val langPlans = RegionBaseline.plans().filter { it.resourcePath().startsWith("lang/") }

        assertEquals(2, langPlans.size)
        assertTrue(langPlans.all { it.versionKey() == "lang-version" })
    }
}
