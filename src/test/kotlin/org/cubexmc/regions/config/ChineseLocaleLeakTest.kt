package org.cubexmc.regions.config

import org.bukkit.configuration.file.YamlConfiguration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * M1.5 的中文界面英文泄漏检查（PLAN.md §4.4）。
 *
 * 前两条用例是硬门禁：中文资源里不允许出现未翻译的内部术语（Mode / Flag / Effect / Trigger /
 * Action），也不允许渲染出未解析的键路径。第三条只覆盖**玩家直接看到的段落**，并且允许
 * 括号里保留稳定 ID、允许命令行语法——"含 ASCII 就报错"的一刀切会把复制命令和输入示例
 * 一起判死，那正是 §4.4 明确要求避免的。
 */
class ChineseLocaleLeakTest {

    private val zh = load("/lang/zh_CN.yml")

    /**
     * 允许出现的例外：键 → 理由。新增例外必须写出理由，避免这份名单变成"把失败删掉"的清单。
     */
    private val allowedKeys = mapOf(
        "help" to "命令用法必须以 ASCII 原样展示",
        "help-publishing" to "命令用法必须以 ASCII 原样展示",
        "help-player" to "命令用法必须以 ASCII 原样展示",
        "gui.prompt.flag" to "输入示例：flag 值 key=value",
        "gui.prompt.effect" to "输入示例：effect 与 combination 取值",
        "gui.prompt.mode-value" to "输入示例：key=value",
        "gui.prompt.judge" to "输入示例：玩家名或 clear",
        "gui.prompt.trigger" to "输入示例：trigger 与 action 语法",
        "gui.prompt.cuboid" to "输入示例：world,minX,... 坐标串",
        "gui.prompt.create-id" to "输入示例：ASCII 场地 ID",
        "gui.flag.advanced.lore" to "高级页输入示例",
        "gui.trigger.advanced.lore" to "高级页输入示例",
        "gui.trigger.preset.enter.lore" to "高级页展示 trigger -> action 的稳定映射",
        "gui.trigger.preset.start.lore" to "高级页展示 trigger -> action 的稳定映射",
        "gui.trigger.preset.finish.lore" to "高级页展示 trigger -> action 的稳定映射",
        "gui.history.rollback-hint" to "复制命令示例",
        "gui.source.cuboid.lore" to "Cuboid 坐标字段名属于技术详情",
        "gui.source.cuboid-format" to "Cuboid 坐标字段名属于技术详情",
        "gui.prompt.cuboid" to "输入示例：world,minX,... 坐标串",
        "gui.prompt.template-location" to "输入示例：模板点位坐标串",
        "gui.prompt.create-id" to "输入示例：ASCII 场地 ID",
        "gui.prompt.mode-value" to "输入示例：key=value",
        "gui.prompt.judge" to "输入示例：玩家名或 clear",
        "gui.prompt.trigger" to "输入示例：trigger 与 action 语法",
        "gui.mode.key-value-required" to "输入示例：key=value",
        "gui.mode.advanced.lore" to "高级页输入示例",
        "gui.mode.judges.lore" to "输入示例：玩家名或 clear",
        "errors.parameter-boolean" to "输入校验必须写出被接受的字面量 true/false",
        "errors.parameter-enum" to "输入校验必须列出被接受的取值",
        "game.match.diplomacy.not-enemies" to "高级选项的稳定 ID 以括号保留",
        "game.match.diplomacy.unverifiable" to "高级选项的稳定 ID 以括号保留",
        "labels.common.on" to "布尔显示名允许并列写出 true/false",
        "labels.common.off" to "布尔显示名允许并列写出 true/false",
    )

    /** 内部术语：中文界面必须显示译名（玩法 / 场地规则 / 临时效果 / 触发条件与执行动作）。 */
    private val bannedTerms = listOf(
        "Mode", "Flag", "Flags", "Effect", "Effects", "Trigger", "Triggers", "Action", "Actions",
    )

    /** 稳定枚举值：玩家段落里不允许裸出现（括号内的稳定 ID 已经在 [allowedKeys] 里逐条放行）。 */
    private val bannedTokens = listOf(
        "true", "false", "allow", "deny", "pass", "vote", "judge", "idle", "draft", "published",
        "frozen", "archived", "active", "dual_pvp", "union_war", "free_for_all", "free_event",
        "run_race", "boat_race", "horse_race", "hide_and_seek", "allowlist", "blocklist",
        "on_enter", "on_leave", "on_death", "on_kill", "on_respawn", "on_interact", "on_command",
        "on_timer", "on_mode_start", "on_mode_end", "on_role_assigned", "on_found", "on_checkpoint",
        "on_finish", "while_inside", "until_mode_end", "highest_priority", "merge_by_type",
    )

    @Test
    fun `chinese locale never shows internal capability terminology`() {
        val offenders = ArrayList<String>()
        forEachValue { key, value ->
            if (key in allowedKeys) return@forEachValue
            val stripped = plain(value)
            for (term in bannedTerms) {
                if (STANDALONE.containsMatchIn(term) && Regex("(?<![A-Za-z])$term(?![A-Za-z])").containsMatchIn(stripped)) {
                    offenders += "$key still says '$term': $value"
                }
            }
        }

        assertEquals(emptyList<String>(), offenders, "中文资源里仍有未翻译的内部术语")
    }

    @Test
    fun `chinese locale never renders an unresolved key path`() {
        val offenders = ArrayList<String>()
        forEachValue { key, value ->
            val stripped = plain(value).trim()
            if (KEY_PATH.matches(stripped)) {
                offenders += "$key renders as a key path: $value"
            }
        }

        assertEquals(emptyList<String>(), offenders, "中文资源里有会被原样显示的键路径")
    }

    @Test
    fun `player facing chinese text does not leak bare enum values`() {
        val offenders = ArrayList<String>()
        val sections = listOf("game.", "gui.lobby.", "gui.game.", "gui.wizard.", "errors.")
        forEachValue { key, value ->
            if (key in allowedKeys || sections.none { key.startsWith(it) }) return@forEachValue
            val stripped = plain(value)
            for (token in bannedTokens) {
                if (Regex("(?<![A-Za-z0-9_-])${Regex.escape(token)}(?![A-Za-z0-9_-])").containsMatchIn(stripped)) {
                    offenders += "$key leaks '$token': $value"
                }
            }
        }

        assertEquals(emptyList<String>(), offenders, "玩家段落里出现了裸枚举值")
    }

    @Test
    fun `every allowlisted exception still exists so the list cannot rot`() {
        val keys = zh.getKeys(true).toSet()

        for (key in allowedKeys.keys) {
            assertTrue(keys.contains(key), "允许名单里的 $key 已经不存在，请删掉这条例外")
        }
    }

    /** 去掉 MiniMessage 标签、转义的字面尖括号与十六进制颜色，只留玩家实际读到的文字。 */
    private fun plain(value: String): String =
        value
            .replace(ESCAPED_LITERAL, "")
            .replace(TAG, "")
            .replace(HEX_COLOR, "")

    private fun forEachValue(action: (String, String) -> Unit) {
        for (key in zh.getKeys(true)) {
            if (zh.isConfigurationSection(key)) continue
            val value = if (zh.isList(key)) zh.getStringList(key).joinToString("\n") else zh.getString(key) ?: continue
            action(key, value)
        }
    }

    private fun load(resource: String): YamlConfiguration {
        val stream = requireNotNull(javaClass.getResourceAsStream(resource)) { "missing resource $resource" }
        return java.io.InputStreamReader(stream, Charsets.UTF_8).use { YamlConfiguration.loadConfiguration(it) }
    }

    private companion object {
        val TAG = Regex("<[^>]+>")
        val ESCAPED_LITERAL = Regex("""\\<[^>]*>?""")
        val HEX_COLOR = Regex("#[0-9a-fA-F]{6}")
        val KEY_PATH = Regex("(gui|game|command|labels|errors|templates)(\\.[a-z0-9_-]+)+")
        val STANDALONE = Regex(".")
    }
}
