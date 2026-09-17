package org.cubexmc.regions.config

import org.bukkit.configuration.file.YamlConfiguration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * M1.2 错误结构化（Regions/PLAN.md §4.2）：每个稳定错误码在两个内置 locale 下都有
 * `errors.<code>` 显示文案。
 *
 * 代码清单**从源码扫描得到**，不是手工维护的数组：手工清单只能证明"我列的那些还在"，
 * 漏掉新加的码时测试依旧全绿（`action-text-*` 与 effect/lifecycle/trial 的 17 个码就是这么漏的）。
 * 扫描覆盖 `failCoded("…")`、`error(…, "…")`、`warning(…, "…")` 与直接构造 `ValidationIssue`
 * 四类落点；发现码没有双语键就失败。
 */
class ErrorCatalogTest {

    /** 允许不出现在玩家界面上的内部码（必须逐条写出理由）。 */
    private val internalOnly = emptyMap<String, String>()

    @Test
    fun `every error code in the sources has a display line in both locales`() {
        val zh = load("/lang/zh_CN.yml")
        val en = load("/lang/en_US.yml")
        val codes = scanErrorCodes()
        assertTrue(codes.size > 60, "只扫描到 ${codes.size} 个错误码，源码扫描大概失效了")

        val missing = codes.filter { code ->
            code !in internalOnly && (!zh.contains("errors.$code") || !en.contains("errors.$code"))
        }

        assertEquals(emptyList<String>(), missing, "这些错误码缺双语 errors.* 文案")
    }

    @Test
    fun `placeholder sets match across locales for every scanned code`() {
        val zh = load("/lang/zh_CN.yml")
        val en = load("/lang/en_US.yml")
        for (code in scanErrorCodes()) {
            if (code in internalOnly || !zh.contains("errors.$code")) continue
            assertEquals(
                placeholders(zh.getString("errors.$code")),
                placeholders(en.getString("errors.$code")),
                "placeholder sets differ for errors.$code",
            )
        }
    }

    @Test
    fun `locales do not carry error lines that no code can produce`() {
        val zh = load("/lang/zh_CN.yml")
        val produced = scanErrorCodes()
        val orphans = zh.getConfigurationSection("errors")?.getKeys(false).orEmpty()
            .filterNot { it in produced }

        // 只报告，不失败：历史 revision 里可能仍有旧码，但清单必须能看出漂移。
        assertTrue(
            orphans.size <= 12,
            "errors.* 里积压了 ${orphans.size} 个没有生产者的键，请清理：$orphans",
        )
    }

    /**
     * 玩家界面不得直接使用 `ServiceResult.reason`：那是**英文日志诊断**，
     * 有错误码时必须经 `LanguageManager.resultReason` 渲染（PLAN.md §4.2）。
     * 这里扫描 GUI 与命令层里 `"reason" to <x>.reason` 这种直传写法；
     * `event.reason`（审计行的稳定操作码，本身走 `labels.reason.*`）在允许名单里。
     */
    @Test
    fun `player facing layers never pass a raw diagnostic reason through`() {
        val root = sourceRoot()
        val pattern = Regex(""""reason"\s*to\s+([\w.]+)\.reason\b""")
        val allowed = setOf("event", "lease")
        val offenders = ArrayList<String>()

        root.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".kt") }
            .filter { it.parentFile.name == "gui" || it.parentFile.name == "command" }
            .forEach { file ->
                pattern.findAll(file.readText()).forEach { match ->
                    if (match.groupValues[1] !in allowed) {
                        offenders += "${file.name}: ${match.value}"
                    }
                }
            }

        assertEquals(emptyList<String>(), offenders, "这些位置会把英文诊断直接显示给玩家")
    }

    /** 扫描 main 源码里所有稳定的错误码构造点。 */
    private fun scanErrorCodes(): Set<String> {
        val root = sourceRoot()
        val patterns = listOf(
            Regex("""failCoded\(\s*"([a-z0-9-]+)""""),
            Regex("""\berror\(\s*[^,()]+,\s*"([a-z0-9-]+)""""),
            Regex("""\bwarning\(\s*[^,()]+,\s*"([a-z0-9-]+)""""),
            Regex("""ValidationIssue\(\s*[^,()]+,\s*ValidationSeverity\.\w+,\s*"([a-z0-9-]+)""""),
        )
        val codes = LinkedHashSet<String>()
        root.walkTopDown().filter { it.isFile && it.name.endsWith(".kt") }.forEach { file ->
            val text = file.readText()
            for (pattern in patterns) {
                pattern.findAll(text).forEach { codes += it.groupValues[1] }
            }
        }
        return codes
    }

    /**
     * 源码目录。Gradle 的测试工作目录默认是插件项目目录，但也接受从仓库根运行的情况；
     * 找不到就直接失败，不允许静默跳过（跳过等于这条门禁不存在）。
     */
    private fun sourceRoot(): File =
        listOf("src/main/kotlin", "Regions/src/main/kotlin")
            .map { File(it) }
            .firstOrNull { it.isDirectory }
            ?: error("Cannot locate src/main/kotlin; run the tests from the plugin or repository root.")

    private fun placeholders(value: String?): Set<String> =
        Regex("<([a-z-]+)>").findAll(value ?: "").mapTo(LinkedHashSet()) { it.groupValues[1] }

    private fun load(resource: String): YamlConfiguration {
        val stream = requireNotNull(javaClass.getResourceAsStream(resource)) { "missing resource $resource" }
        return java.io.InputStreamReader(stream, Charsets.UTF_8).use { YamlConfiguration.loadConfiguration(it) }
    }
}
