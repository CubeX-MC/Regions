package org.cubexmc.regions.config

import org.bukkit.configuration.file.YamlConfiguration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.io.InputStreamReader

/**
 * 代码引用的每个语言键都必须在两份语言文件里存在。
 *
 * 缺一个键的表现不是报错，而是界面上原样显示 `gui.xxx.yyy` 这种路径——真机上很容易看漏
 * （2026-09-19 实机反馈：「界面里还有 xx.xx 格式的东西」）。[LanguageFileTest] 只保证两份
 * 语言文件彼此对齐，对"代码要的键根本没人写"无能为力，所以这条单独立。
 *
 * 只扫**字面量**：运行时拼出来的键（`errors.<code>`、`labels.field.<path>` 等）由
 * [ErrorCatalogTest]、[TermLabelsTest] 各自覆盖，且那些调用点都带兜底文案。
 */
class LanguageKeyUsageTest {

    private val zh = load("/lang/zh_CN.yml")
    private val en = load("/lang/en_US.yml")

    @Test
    fun `every language key used in code exists in both locales`() {
        val used = collectUsedKeys()
        assertTrue(used.size > 150, "扫描没找到足够多的键，正则大概是失效了：${used.size}")

        val missing = used
            .filterNot { (key, _) -> zh.isSet(key) && en.isSet(key) }
            .map { (key, sources) ->
                val where = if (zh.isSet(key)) "en_US" else if (en.isSet(key)) "zh_CN" else "both locales"
                "$key (missing from $where; used in ${sources.sorted().joinToString()})"
            }
            .sorted()

        assertEquals(emptyList<String>(), missing, "代码引用了语言文件里没有的键")
    }

    /**
     * 同一层里不允许重复键。
     *
     * SnakeYAML 对重复键**不报错**，默默地后者覆盖前者——大乱斗的玩法卡片曾因此
     * 显示成捉迷藏的说明（`free_for_all` 下写了两个 `lore:`）。这类错误只在真机上看得出来。
     */
    @Test
    fun `no shipped yaml declares the same key twice in one section`() {
        for (resource in listOf("/lang/zh_CN.yml", "/lang/en_US.yml", "/templates.yml", "/config.yml")) {
            val text = requireNotNull(javaClass.getResourceAsStream(resource)) { "missing " + resource }
                .use { it.reader(Charsets.UTF_8).readText() }
            val seen = HashMap<String, MutableSet<String>>()
            val duplicates = ArrayList<String>()
            val stack = ArrayList<Pair<Int, String>>()
            for (raw in text.lines()) {
                val line = raw.trimEnd()
                if (line.isBlank() || line.trimStart().startsWith("#") || line.trimStart().startsWith("- ")) continue
                val match = KEY_LINE.find(line) ?: continue
                val indent = match.groupValues[1].length
                val key = match.groupValues[2]
                while (stack.isNotEmpty() && stack.last().first >= indent) stack.removeAt(stack.size - 1)
                val parent = stack.joinToString(".") { it.second }
                val full = if (parent.isEmpty()) key else parent + "." + key
                if (!seen.getOrPut(parent) { linkedSetOf() }.add(key)) duplicates += resource + ": " + full
                stack += indent to key
            }
            assertEquals(emptyList<String>(), duplicates, resource + " 里有重复键，后者会静默覆盖前者")
        }
    }

    /** 扫描 `src/main/kotlin` 里所有 i18n 调用点的字面量键。 */
    private fun collectUsedKeys(): Map<String, MutableSet<String>> {
        val root = File("src/main/kotlin")
        assertTrue(root.isDirectory, "找不到源码目录：${root.absolutePath}")
        val used = LinkedHashMap<String, MutableSet<String>>()
        root.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
            val text = file.readText()
            CALL_SITE.findAll(text).forEach { match ->
                val key = match.groupValues[1]
                used.getOrPut(key) { linkedSetOf() }.add(file.name)
            }
        }
        return used
    }

    private fun load(path: String): YamlConfiguration {
        val stream = requireNotNull(javaClass.getResourceAsStream(path)) { "missing $path" }
        return stream.use { YamlConfiguration.loadConfiguration(InputStreamReader(it, Charsets.UTF_8)) }
    }

    private companion object {
        /**
         * i18n 的入口函数后面第一个"键形状"的字面量。
         * 覆盖 `LanguageManager`、`GuiText` 与 `ModeMessages` 的全部对外方法。
         */
        /** `  key:` 或 `  "key":` 形状的行。 */
        val KEY_LINE = Regex("""^( *)"?([A-Za-z0-9_.\- ]+)"?:.*$""")

        val CALL_SITE = Regex(
            """(?:sendPlain|sendGame|sendGameLocalized|\.send|\.message|\.messageFor|\.messageList|""" +
                """\.component|\.componentFor|\.componentList|\.lore|\.label|\.text|\.item|GameArg\.key)""" +
                """\s*\([^)]{0,220}?"([a-z][a-z0-9-]*(?:\.[a-z0-9-]+)+)"""",
            RegexOption.DOT_MATCHES_ALL,
        )
    }
}
