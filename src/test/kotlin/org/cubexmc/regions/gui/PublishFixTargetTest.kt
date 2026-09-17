package org.cubexmc.regions.gui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** M2.3：校验错误码 → "立即修复"目标页的映射（发布页跳转依据）。 */
class PublishFixTargetTest {

    @Test
    fun `source issues jump to the source page`() {
        assertEquals(PublishFixTarget.SOURCE, PublishFixTarget.from("source-unknown"))
        assertEquals(PublishFixTarget.SOURCE, PublishFixTarget.from("source-unresolved"))
    }

    @Test
    fun `mode value issues jump to the mode menu`() {
        for (code in listOf(
            "mode-unknown", "max-players-below-min", "min-players-below", "min-unions-above-players",
            "race-timeout-invalid", "checkpoint-vehicles-mismatch", "round-seconds-not-after-hide",
            "seekers-above-min-players", "item-list-too-many", "item-entry-invalid",
            "location-required", "location-invalid", "world-unloaded", "parameter-min",
        )) {
            assertEquals(PublishFixTarget.MODE, PublishFixTarget.from(code), code)
        }
    }

    @Test
    fun `rule effect and trigger issues jump to their pages`() {
        assertEquals(PublishFixTarget.RULES, PublishFixTarget.from("flag-unknown"))
        assertEquals(PublishFixTarget.EFFECTS, PublishFixTarget.from("effect-unknown"))
        assertEquals(PublishFixTarget.EFFECTS, PublishFixTarget.from("potion-unknown"))
        assertEquals(PublishFixTarget.TRIGGERS, PublishFixTarget.from("trigger-no-runtime"))
        assertEquals(PublishFixTarget.TRIGGERS, PublishFixTarget.from("condition-unknown"))
        assertEquals(PublishFixTarget.TRIGGERS, PublishFixTarget.from("action-unknown"))
        assertEquals(PublishFixTarget.TRIGGERS, PublishFixTarget.from("sound-invalid"))
    }

    @Test
    fun `issues without an in gui fix have no target`() {
        for (code in listOf(
            "region-id-blank", "funding-unavailable", "dependency-unavailable",
            "overlap-stateful-mode", "console-command-superadmin-only", "union-war-fallback-provider",
        )) {
            assertNull(PublishFixTarget.from(code), code)
        }
    }
}
