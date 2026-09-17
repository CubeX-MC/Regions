package org.cubexmc.regions.config

import org.bukkit.configuration.ConfigurationSection
import org.cubexmc.config.MigrationContext
import org.cubexmc.config.MigrationStep

/**
 * templates.yml 1 → 2（PLAN.md §4.3）：内置模板的显示名、描述与 message/broadcast/title
 * 文本改用语言键（`name-key`/`description-key`/`text-key`/`title-key`/`subtitle-key`）。
 *
 * 只对**精确匹配**随插件历史默认内容的字段自动转换；自定义文案保留原样并给出说明，
 * 服务器自己写的模板不受影响。已发布的历史 revision 不在本文件里，天然保持原文。
 */
internal class TemplatesV1ToV2Step : MigrationStep {

    override fun fromVersion(): Int = 1

    override fun toVersion(): Int = 2

    override fun description(): String = "Regions templates.yml 1->2: built-in template texts become language keys"

    override fun migrate(context: MigrationContext) {
        val root = context.yaml().getConfigurationSection("templates") ?: return
        for (id in root.getKeys(false)) {
            val section = root.getConfigurationSection(id) ?: continue
            val defaults = Defaults.forTemplate(id.lowercase()) ?: continue
            convertScalar(section, id, "name", defaults.name, "name")
            convertScalar(section, id, "description", defaults.description, "description")
            convertActions(section.getConfigurationSection("triggers"), id, context)
        }
    }

    private fun convertScalar(
        section: ConfigurationSection,
        id: String,
        field: String,
        defaultValue: String,
        keySuffix: String,
    ) {
        val value = section.getString(field) ?: return
        if (value == defaultValue) {
            section.set(field, null)
            section.set("$field-key", "templates.$id.$keySuffix")
        }
        // 自定义值：原样保留。逐一告警太吵，名字在迁移报告的文件级说明里已经交代。
    }

    private fun convertActions(triggers: ConfigurationSection?, id: String, context: MigrationContext) {
        if (triggers == null) return
        for (triggerKey in triggers.getKeys(false)) {
            val blocks = triggers.getMapList(triggerKey)
            var changed = false
            val converted = blocks.mapIndexed { blockIndex, block ->
                val updated = LinkedHashMap(block)
                for (branch in listOf("then", "else")) {
                    @Suppress("UNCHECKED_CAST")
                    val actions = (updated[branch] as? List<Map<String, Any?>>)?.toMutableList() ?: continue
                    updated[branch] = actions.mapIndexed { actionIndex, action ->
                        convertAction(action, "$id:$triggerKey:$blockIndex:$branch:$actionIndex", context)?.also { changed = true } ?: action
                    }
                }
                updated
            }
            if (changed) triggers.set(triggerKey, converted)
        }
    }

    /** 返回替换后的动作表；无需替换时返回 null。 */
    private fun convertAction(
        raw: Map<String, Any?>,
        path: String,
        context: MigrationContext,
    ): Map<String, Any?>? {
        val type = raw["type"]?.toString()?.lowercase() ?: return null
        if (type != "message" && type != "broadcast" && type != "title") return null
        val updated = LinkedHashMap(raw)
        var changed = false
        for (field in listOf("title", "subtitle", "text", "message")) {
            val value = updated[field]?.toString() ?: continue
            val key = Defaults.textKeys[value]
            if (key == null) {
                context.warning(
                    "templates.$path.$field",
                    "custom action text was kept as-is / 自定义动作文本保留原样",
                )
                continue
            }
            updated.remove(field)
            updated["${if (field == "message") "text" else field}-key"] = key
            changed = true
        }
        return if (changed) updated else null
    }

    /** 随插件发布的 v1 内置模板默认文案 → 语言键；只有精确等于这些值的字段才会被转换。 */
    private object Defaults {
        data class Builtin(val name: String, val description: String)

        private val builtins = mapOf(
            "mini_kingdom" to Builtin("小人国", "玩家进入后缩小，禁飞、禁隐身、禁 PVP。"),
            "event_plaza" to Builtin("活动广场", "轻量活动区域，默认禁 PVP 和禁飞。"),
            "run_race" to Builtin("跑步赛道", "跑步/跑酷比赛。创建时会问你要起点和终点；检查点可选，之后在 GUI 里加。"),
            "boat_race" to Builtin("划船赛道", "划船比赛。参赛者必须坐在船上才会记录检查点和终点。创建时会问你要起点和终点。"),
            "horse_race" to Builtin("骑马赛道", "骑马比赛。参赛者必须骑马才会记录检查点和终点。创建时会问你要起点和终点。"),
            "dual_pvp" to Builtin("双人决斗场", "2 名玩家进入后全员确认开始，开战时扣押原装备并发放指定装备。"),
            "union_war" to Builtin("工会战场", "多人进入后全员确认开始，使用指定战斗装备。工会关系判定后续接入 UnionProvider。"),
            "hide_and_seek" to Builtin(
                "藏猫猫场地",
                "回合制小游戏。玩家进入后 ready，开局自动分配寻找者和隐藏者。寻找者攻击隐藏者即找到对方，不造成真实伤害。",
            ),
        )

        val textKeys = mapOf(
            "&a小人国" to "templates.mini_kingdom.enter.title",
            "&7离开区域后自动恢复" to "templates.mini_kingdom.enter.subtitle",
            "&6{player} 完成比赛，名次 #{rank}，用时 {race_time_ms}ms。" to "templates.run_race.finish.text",
            "&6{player} 划船到达终点，名次 #{rank}。" to "templates.boat_race.finish.text",
            "&6{player} 骑马到达终点，名次 #{rank}。" to "templates.horse_race.finish.text",
            "&c决斗开始" to "templates.dual_pvp.start.title",
            "&7击败对手后会自动恢复装备" to "templates.dual_pvp.start.subtitle",
            "&e决斗结束，装备已恢复。" to "templates.dual_pvp.end.text",
            "&c工会战开始！代表你的工会活到最后。" to "templates.union_war.start.text",
            "&e工会战结束，装备已恢复。" to "templates.union_war.end.text",
            "&a藏猫猫开始" to "templates.hide_and_seek.start.title",
            "&7寻找者稍后释放" to "templates.hide_and_seek.start.subtitle",
            "&e你的身份已经分配，请查看聊天提示。" to "templates.hide_and_seek.assigned.text",
            "&6{player} 被找到了。" to "templates.hide_and_seek.found.text",
            "&e小游戏结束，临时状态已恢复。" to "templates.hide_and_seek.end.text",
        )

        fun forTemplate(id: String): Builtin? = builtins[id]
    }
}
