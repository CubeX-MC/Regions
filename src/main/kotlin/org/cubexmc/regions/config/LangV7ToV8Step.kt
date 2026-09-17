package org.cubexmc.regions.config

import org.bukkit.configuration.file.YamlConfiguration
import org.cubexmc.config.MigrationContext
import org.cubexmc.config.MigrationStep

/**
 * lang 7 → 8：把**缺少的叶子键按同语言内置文本补齐**。
 *
 * v7 之后语言文件新增了大量内容：`labels.*`（能力／状态／字段／严重级译名）、`errors.*`
 * （稳定错误码文案）、`gui.game.*`、`gui.wizard.settings.*`、`game.match.*`、
 * `labels.participant/outcome/reward` 等。既有安装的文件版本号已经是 7，迁移框架据此认为
 * "无需处理"，于是这些键在真机上解析不出来——`labels.state.idle` 会原样显示成键名，
 * `errors.*` 退化成英文诊断句（2026-09-13 实服验证发现）。
 *
 * 补齐规则（与 PLAN.md §8.1 一致）：
 * - **只补缺键**：服主已经写过的值一律不动，未知键与自定义键一概保留；
 * - 值取自**同语言**的 jar 内默认文件，所以中文服补的是中文；
 * - 幂等：重复执行不会改动任何已存在的键。
 *
 * 备份、原子写与失败回滚仍由 `MigrationRunner` 负责，这里只改内存中的 [YamlConfiguration]。
 */
internal class LangV7ToV8Step(private val locale: String) : MigrationStep {

    override fun fromVersion(): Int = 7

    override fun toVersion(): Int = 8

    override fun description(): String = "Regions lang/$locale 7->8: fill in every leaf key added after v7 from the bundled defaults"

    override fun migrate(context: MigrationContext) {
        val defaults = BuiltInLanguageDefaults.forLocale(locale)
        if (defaults == null) {
            context.warning(null, "No built-in defaults for locale '$locale'; no keys were added.")
            return
        }
        val yaml = context.yaml()
        val added = ArrayList<String>()
        for (key in defaults.getKeys(true)) {
            if (defaults.isConfigurationSection(key)) continue
            if (yaml.isSet(key)) continue
            yaml.set(key, defaults.get(key))
            added += key
        }
        // 版本键由 MigrationRunner 依 MigrationPlan 写，这里不碰。
        context.warning(
            null,
            "lang/$locale 7->8 added ${added.size} missing key(s) from the built-in defaults; " +
                "existing values and unknown keys were left untouched.",
        )
    }

    /** jar 内同语言的默认文本；缺席（例如第三方语言）时不补键。 */
    private object BuiltInLanguageDefaults {
        fun forLocale(locale: String): YamlConfiguration? {
            if (locale !in SUPPORTED) return null
            val path = "lang/$locale.yml"
            val stream = LangV7ToV8Step::class.java.classLoader.getResourceAsStream(path) ?: return null
            return stream.use { YamlConfiguration.loadConfiguration(it.reader(Charsets.UTF_8)) }
        }

        private val SUPPORTED = setOf("zh_CN", "en_US")
    }
}
