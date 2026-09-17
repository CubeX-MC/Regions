package org.cubexmc.regions.config

import org.bukkit.configuration.file.YamlConfiguration
import org.cubexmc.config.MigrationContext
import org.cubexmc.config.MigrationStep

/**
 * lang 6 → 7：模板确认 GUI 一轮（2026-08-24）新增了 `gui.detail.apply-template`、
 * `gui.template.back-to-detail`、`gui.template.confirm.*`，并把模板条目里最后的"创建"提示
 * 从 `gui.template.entry.lore` 拆成独立的 `create-hint`，同时补上 `apply-hint`。
 *
 * 只对能识别出历史默认提示的 lore 列表自动拆分；完全自定义的列表保持原样并给出版本说明，
 * 新键按叶子路径逐个 `setIfMissing`，服主自定义值和未知键一概不动。
 * 备份、原子写与失败回滚由 `MigrationRunner` 负责，这里只改内存中的 [YamlConfiguration]。
 */
internal class LangV6ToV7Step(private val locale: String) : MigrationStep {

    override fun fromVersion(): Int = 6

    override fun toVersion(): Int = 7

    override fun description(): String = "Regions lang/$locale 6->7: add template confirm GUI keys and split entry create hint"

    override fun migrate(context: MigrationContext) {
        val defaults = Defaults.forLocale(locale)
        if (defaults == null) {
            context.warning(null, "No built-in v6 defaults for locale '$locale'; no keys were added.")
            return
        }
        val yaml = context.yaml()

        setIfMissing(yaml, "gui.detail.apply-template.name", defaults.applyTemplateName)
        setIfMissing(yaml, "gui.detail.apply-template.lore", defaults.applyTemplateLore)

        val lorePath = ENTRY + ".lore"
        if (yaml.isList(lorePath)) {
            val lore = yaml.getStringList(lorePath)
            when {
                lore.lastOrNull() == defaults.createHint && lore.size > 1 ->
                    yaml.set(lorePath, lore.dropLast(1))
                lore.isNotEmpty() -> context.warning(
                    lorePath,
                    "custom entry lore was kept as-is; move the old create hint into " +
                        "$ENTRY.create-hint / 自定义 lore 已保留，请把旧的创建提示手动移入 create-hint",
                )
            }
        }
        setIfMissing(yaml, "$ENTRY.create-hint", defaults.createHint)
        setIfMissing(yaml, "$ENTRY.apply-hint", defaults.applyHint)

        setIfMissing(yaml, "gui.template.back-to-detail", defaults.backToDetail)
        setIfMissing(yaml, "gui.template.confirm.title", defaults.confirmTitle)
        setIfMissing(yaml, "gui.template.confirm.summary.name", defaults.confirmSummaryName)
        setIfMissing(yaml, "gui.template.confirm.summary.lore", defaults.confirmSummaryLore)
        setIfMissing(yaml, "gui.template.confirm.warning.name", defaults.confirmWarningName)
        setIfMissing(yaml, "gui.template.confirm.warning.lore", defaults.confirmWarningLore)
        setIfMissing(yaml, "gui.template.confirm.apply.name", defaults.confirmApplyName)
        setIfMissing(yaml, "gui.template.confirm.apply.lore", defaults.confirmApplyLore)
    }

    private fun setIfMissing(yaml: YamlConfiguration, path: String, value: Any?) {
        if (!yaml.isSet(path)) {
            yaml.set(path, value)
        }
    }

    private class Defaults(
        val createHint: String,
        val applyHint: String,
        val backToDetail: String,
        val applyTemplateName: String,
        val applyTemplateLore: List<String>,
        val confirmTitle: String,
        val confirmSummaryName: String,
        val confirmSummaryLore: List<String>,
        val confirmWarningName: String,
        val confirmWarningLore: List<String>,
        val confirmApplyName: String,
        val confirmApplyLore: List<String>,
    ) {
        companion object {
            private val zhCn = Defaults(
                createHint = "<green>点击验证并创建场地。",
                applyHint = "<yellow>点击检查并替换当前配置。",
                backToDetail = "<red>返回领地详情",
                applyTemplateName = "<yellow>应用模板",
                applyTemplateLore = listOf(
                    "<gray>为这个领地重新选择一套完整场地预设。",
                    "<yellow>确认后会整体替换 Mode、Flags、Effects 和 Triggers。",
                ),
                confirmTitle = "<dark_green>应用模板: <id>",
                confirmSummaryName = "<aqua>新预设: <name>",
                confirmSummaryLore = listOf(
                    "<gray>Mode: <mode>",
                    "<gray>Flags: <flags>",
                    "<gray>Effects: <effects>",
                    "<gray>Triggers: <triggers>",
                ),
                confirmWarningName = "<red>替换当前玩法配置",
                confirmWarningLore = listOf(
                    "<red>当前 Mode、Flags、Effects 和 Triggers 会被清空。",
                    "<gray>领地 ID、来源、主人、优先级和历史版本保持不变。",
                ),
                confirmApplyName = "<green>确认并保存为草稿",
                confirmApplyLore = listOf(
                    "<gray>在预览并发布这个草稿前，线上场地不会改变。",
                ),
            )

            private val enUs = Defaults(
                createHint = "<green>Click to validate and create the venue.",
                applyHint = "<yellow>Click to review replacing the current configuration.",
                backToDetail = "<red>Back to region details",
                applyTemplateName = "<yellow>Apply a template",
                applyTemplateLore = listOf(
                    "<gray>Choose a complete venue preset for this region.",
                    "<yellow>Mode, Flags, Effects and Triggers will be replaced after confirmation.",
                ),
                confirmTitle = "<dark_green>Apply template: <id>",
                confirmSummaryName = "<aqua>New preset: <name>",
                confirmSummaryLore = listOf(
                    "<gray>Mode: <mode>",
                    "<gray>Flags: <flags>",
                    "<gray>Effects: <effects>",
                    "<gray>Triggers: <triggers>",
                ),
                confirmWarningName = "<red>Replace current gameplay configuration",
                confirmWarningLore = listOf(
                    "<red>The current Mode, Flags, Effects and Triggers will be cleared.",
                    "<gray>Region identity, source, owner, priority and history stay unchanged.",
                ),
                confirmApplyName = "<green>Confirm and save as draft",
                confirmApplyLore = listOf(
                    "<gray>The live region is unchanged until you preview and publish this draft.",
                ),
            )

            fun forLocale(locale: String): Defaults? = when (locale) {
                "zh_CN" -> zhCn
                "en_US" -> enUs
                else -> null
            }
        }
    }

    private companion object {
        const val ENTRY = "gui.template.entry"
    }
}
