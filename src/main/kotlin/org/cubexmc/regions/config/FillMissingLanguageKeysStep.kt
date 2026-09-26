package org.cubexmc.regions.config

import org.bukkit.configuration.file.YamlConfiguration
import org.cubexmc.config.MigrationContext
import org.cubexmc.config.MigrationStep

/**
 * "把缺少的叶子键按同语言内置文本补齐"的通用步骤。
 *
 * 语言文件每加一批新键，既有安装的版本号都还停在旧值，迁移框架据此认为"无需处理"，
 * 于是新键在真机上解析不出来（v7→v8 就是这么被实服发现的）。所以每次加键都要 +1 版本
 * 并挂一条这样的步骤。
 *
 * 规则：
 * - **只补缺键**：服主写过的值一律不动，未知键与自定义键一概保留；
 * - 值取自**同语言**的 jar 内默认文件，中文服补中文；
 * - 幂等：重复执行不会改动任何已存在的键。
 *
 * 备份、原子写与失败回滚由 `MigrationRunner` 负责，这里只改内存中的 [YamlConfiguration]。
 * `from`/`to` 一律写**字面量**：跟着"当前版本"常量走会让某一版的新键永远合不进来。
 */
internal abstract class FillMissingLanguageKeysStep(
    private val locale: String,
    private val from: Int,
    private val to: Int,
) : MigrationStep {

    final override fun fromVersion(): Int = from

    final override fun toVersion(): Int = to

    override fun description(): String =
        "Regions lang/$locale $from->$to: fill in every leaf key added after v$from from the bundled defaults"

    final override fun migrate(context: MigrationContext) {
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
            "lang/$locale $from->$to added ${added.size} missing key(s) from the built-in defaults; " +
                "existing values and unknown keys were left untouched.",
        )
    }

    /** jar 内同语言的默认文本；缺席（例如第三方语言）时不补键。 */
    private object BuiltInLanguageDefaults {
        fun forLocale(locale: String): YamlConfiguration? {
            if (locale !in SUPPORTED) return null
            val path = "lang/$locale.yml"
            val stream = FillMissingLanguageKeysStep::class.java.classLoader.getResourceAsStream(path) ?: return null
            return stream.use { YamlConfiguration.loadConfiguration(it.reader(Charsets.UTF_8)) }
        }

        private val SUPPORTED = setOf("zh_CN", "en_US")
    }
}

/**
 * lang 8 → 9：工会战选队的新文案（`command.game-teams-candidates` 等）。
 *
 * 由来：实机上只能输入 26 位 ULID 选工会，太难用；现在编号／名字／名字前缀都收，
 * 解析不出来时会把候选列出来——这些提示都是新键（2026-09-19 实机反馈）。
 */
internal class LangV8ToV9Step(locale: String) : FillMissingLanguageKeysStep(locale, 8, 9)

/**
 * lang 9 → 10：七种玩法补齐到与决斗同级时新增的文案。
 *
 * 竞速与捉迷藏拿到了显式报名册与结构化结果，于是多了"本场出局"（`game.race.removed`）、
 * "这个玩法没有发令步骤"（`game.match.start.not-supported`）以及五条新的结束原因
 * （`game.match.reason.all-finished` / `no-finisher` / `all-found` / `seekers-gone` /
 * `hiders-survived`）。既有安装的版本号停在 9，不加这一步这些键在真机上解析不出来。
 */
internal class LangV9ToV10Step(locale: String) : FillMissingLanguageKeysStep(locale, 9, 10)

internal class LangV10ToV11Step(locale: String) : FillMissingLanguageKeysStep(locale, 10, 11)
