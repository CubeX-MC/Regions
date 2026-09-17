package org.cubexmc.regions.service

import org.bukkit.command.CommandSender
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration
import org.cubexmc.core.Reloadable
import org.cubexmc.regions.RegionsPlugin
import org.cubexmc.regions.model.ActionBlockConfig
import org.cubexmc.regions.model.ActionConfig
import org.cubexmc.regions.model.ConditionConfig
import org.cubexmc.regions.model.EffectConfig
import org.cubexmc.regions.model.EffectCombination
import org.cubexmc.regions.model.EffectScope
import org.cubexmc.regions.model.FlagConfig
import org.cubexmc.regions.model.ModeConfig
import org.cubexmc.regions.model.RegionDefinition
import org.cubexmc.regions.model.RegionTrigger
import org.cubexmc.regions.model.TriggerExecution
import java.io.File
import java.util.Locale

enum class TemplateParameterType { STRING, INTEGER, DOUBLE, BOOLEAN, LOCATION }

data class TemplateParameter(
    val id: String,
    val type: TemplateParameterType = TemplateParameterType.STRING,
    val required: Boolean = false,
    val defaultValue: String? = null,
    val min: Double? = null,
    val max: Double? = null,
    val options: Set<String> = emptySet(),
) {
    fun validate(value: String?): String? {
        if (value.isNullOrBlank()) return if (required && defaultValue == null) "$id is required" else null
        val numeric = when (type) {
            TemplateParameterType.INTEGER -> value.toIntOrNull()?.toDouble()
                ?: return "$id must be an integer"
            TemplateParameterType.DOUBLE -> value.toDoubleOrNull()
                ?: return "$id must be a number"
            TemplateParameterType.BOOLEAN -> if (value.toBooleanStrictOrNull() == null) return "$id must be true or false" else null
            // 和 RegionValidationService.validateLocation 用同一套格式，模板才不会产出一份
            // 自己校验得过、发布时又被判 ERROR 的坐标。
            TemplateParameterType.LOCATION -> if (!isLocation(value)) return "$id must use world,x,y,z" else null
            TemplateParameterType.STRING -> null
        }
        if (numeric != null && min != null && numeric < min) return "$id must be at least $min"
        if (numeric != null && max != null && numeric > max) return "$id must be at most $max"
        if (options.isNotEmpty() && options.none { it.equals(value, ignoreCase = true) }) {
            return "$id must be one of: ${options.joinToString(", ")}"
        }
        return null
    }

    private companion object {
        fun isLocation(raw: String): Boolean {
            val parts = raw.split(',')
            return parts.size >= 4 &&
                parts[0].isNotBlank() &&
                (1..3).all { parts[it].trim().toDoubleOrNull() != null }
        }
    }
}

data class RegionTemplate(
    val id: String,
    val name: String,
    val description: String,
    /** 语言键形式的显示名/描述（`templates.<id>.*`）；与字面量互斥，键优先用于显示。 */
    val nameKey: String? = null,
    val descriptionKey: String? = null,
    val parameters: Map<String, TemplateParameter> = emptyMap(),
    val mode: ModeConfig? = null,
    val flags: Map<String, FlagConfig> = emptyMap(),
    val effects: List<EffectConfig> = emptyList(),
    val triggers: Map<RegionTrigger, List<ActionBlockConfig>> = emptyMap(),
)

data class TemplateApplyResult(
    val region: RegionDefinition? = null,
    val errors: List<String> = emptyList(),
) {
    val success: Boolean get() = region != null && errors.isEmpty()
}

/**
 * 模板目录。
 *
 * [builtIns] 返回 jar 内自带的 `templates.yml`（缺席表示不做合并）。服主文件里**没有**的内置模板
 * 会在内存里补进来：升级安装拿不到新增玩法模板的话，GUI 就没有创建那个玩法的入口，而这在
 * `saveIfMissing` 语义下永远不会自愈。合并**只加不改**——已存在的 id（无论是内置还是自定义）
 * 原样保留，文件本身也**不被重写**（重写会丢注释、丢服主自己的排版）。
 */
class RegionTemplateService(
    private val file: File,
    private val builtIns: () -> java.io.InputStream? = { null },
) : Reloadable {
    private val templates = LinkedHashMap<String, RegionTemplate>()

    /** 上一次 [load] 从 jar 补进来的模板 id，供调用方记录日志。 */
    private var mergedBuiltIns: List<String> = emptyList()

    override fun reload() {
        load()
    }

    fun load(): List<String> {
        templates.clear()
        val yaml = YamlConfiguration.loadConfiguration(file)
        mergedBuiltIns = mergeBuiltIns(yaml)
        val root = yaml.getConfigurationSection("templates") ?: return mergedBuiltIns
        for (id in root.getKeys(false)) {
            val section = root.getConfigurationSection(id) ?: continue
            parseTemplate(id.lowercase(Locale.ROOT), section)?.let { templates[it.id] = it }
        }
        return mergedBuiltIns
    }

    /**
     * 只在内存里补齐缺失的内置模板：[yaml] 中已存在的 `templates.<id>` 一律不动，
     * jar 里有而文件里没有的才追加进这份内存配置。
     */
    private fun mergeBuiltIns(yaml: YamlConfiguration): List<String> {
        val stream = runCatching { builtIns() }.getOrNull() ?: return emptyList()
        val shipped = stream.use { YamlConfiguration.loadConfiguration(it.reader(Charsets.UTF_8)) }
        val shippedRoot = shipped.getConfigurationSection("templates") ?: return emptyList()
        val ownerRoot = yaml.getConfigurationSection("templates") ?: yaml.createSection("templates")
        val added = ArrayList<String>()
        for (id in shippedRoot.getKeys(false)) {
            if (ownerRoot.contains(id)) continue
            val section = shippedRoot.getConfigurationSection(id) ?: continue
            val target = ownerRoot.createSection(id)
            for (key in section.getKeys(true)) {
                if (section.isConfigurationSection(key)) continue
                target.set(key, section.get(key))
            }
            added += id
        }
        return added
    }

    fun all(): List<RegionTemplate> = templates.values.toList()

    fun find(id: String): RegionTemplate? = templates[id.lowercase(Locale.ROOT)]

    fun apply(templateId: String, base: RegionDefinition, supplied: Map<String, String> = emptyMap()): TemplateApplyResult {
        val template = find(templateId) ?: return TemplateApplyResult(errors = listOf("Unknown template: $templateId"))
        val unknown = supplied.keys.filterNot { template.parameters.containsKey(it) }
        val errors = unknown.map { "Unknown template parameter: $it" }.toMutableList()
        val resolved = LinkedHashMap<String, String>()
        for ((id, definition) in template.parameters) {
            val value = supplied[id] ?: definition.defaultValue
            definition.validate(value)?.let { errors.add(it) }
            if (value != null) resolved[id] = value
        }
        if (errors.isNotEmpty()) return TemplateApplyResult(errors = errors)
        fun value(raw: String): String = PARAMETER_PATTERN.replace(raw) { match ->
            resolved[match.groupValues[1]] ?: match.value
        }
        fun values(raw: Map<String, String>): Map<String, String> = raw.mapValues { value(it.value) }
        fun action(raw: ActionConfig): ActionConfig = raw.copy(values = values(raw.values))
        fun condition(raw: ConditionConfig): ConditionConfig = raw.copy(values = values(raw.values))
        fun block(raw: ActionBlockConfig): ActionBlockConfig = raw.copy(
            conditions = raw.conditions.map { condition(it) },
            thenActions = raw.thenActions.map { action(it) },
            elseActions = raw.elseActions.map { action(it) },
        )
        val metadata = LinkedHashMap(base.metadata)
        metadata["template-id"] = template.id
        return TemplateApplyResult(region = base.copy(
            mode = template.mode?.let { it.copy(values = values(it.values)) },
            flags = template.flags.mapValues { (_, flag) -> flag.copy(values = values(flag.values)) },
            effects = template.effects.map { it.copy(values = values(it.values)) },
            triggers = template.triggers.mapValues { (_, blocks) -> blocks.map { block(it) } },
            metadata = metadata,
        ))
    }

    private fun parseTemplate(id: String, section: ConfigurationSection): RegionTemplate? {
        // 键形式与字面量形式互斥（PLAN.md §4.3）；同时出现视为模板配置错误，整个模板不加载。
        val nameKey = section.getString("name-key")
        val name = section.getString("name")
        if (nameKey != null && name != null) return null
        if (nameKey == null && name == null) return null
        val descriptionKey = section.getString("description-key")
        val description = section.getString("description", "") ?: ""
        if (descriptionKey != null && description.isNotBlank()) return null
        val modeSection = section.getConfigurationSection("mode")
        val mode = modeSection?.getString("type")?.let { type ->
            ModeConfig(type, sectionValues(modeSection, setOf("type")))
        }
        val flags = LinkedHashMap<String, FlagConfig>()
        section.getConfigurationSection("flags")?.let { root ->
            for (key in root.getKeys(false)) {
                val flag = root.getConfigurationSection(key) ?: continue
                flags[key] = FlagConfig(key, flag.getString("value", "pass") ?: "pass", sectionValues(flag, setOf("value")))
            }
        }
        val effects = section.getMapList("effects").mapNotNull { raw ->
            val type = raw["type"]?.toString() ?: return@mapNotNull null
            EffectConfig(
                type,
                parseScope(raw["scope"]?.toString()),
                rawValues(raw, setOf("type", "scope", "combination")),
                parseCombination(raw["combination"]?.toString()),
            )
        }
        val triggers = LinkedHashMap<RegionTrigger, List<ActionBlockConfig>>()
        section.getConfigurationSection("triggers")?.let { root ->
            for (key in root.getKeys(false)) {
                val trigger = RegionTrigger.fromKey(key) ?: continue
                triggers[trigger] = root.getMapList(key).map { parseBlock(it) }
            }
        }
        return RegionTemplate(
            id = id,
            name = name ?: "",
            description = description,
            nameKey = nameKey,
            descriptionKey = descriptionKey,
            parameters = parseParameters(section.getConfigurationSection("parameters")),
            mode = mode,
            flags = flags,
            effects = effects,
            triggers = triggers,
        )
    }

    private fun parseParameters(root: ConfigurationSection?): Map<String, TemplateParameter> {
        if (root == null) return emptyMap()
        val result = LinkedHashMap<String, TemplateParameter>()
        for (id in root.getKeys(false)) {
            val section = root.getConfigurationSection(id) ?: continue
            val type = runCatching {
                TemplateParameterType.valueOf((section.getString("type", "string") ?: "string").uppercase(Locale.ROOT))
            }.getOrDefault(TemplateParameterType.STRING)
            result[id] = TemplateParameter(
                id = id,
                type = type,
                required = section.getBoolean("required", false),
                defaultValue = section.getString("default"),
                min = section.getString("min")?.toDoubleOrNull(),
                max = section.getString("max")?.toDoubleOrNull(),
                options = section.getStringList("options").toSet(),
            )
        }
        return result
    }

    private fun parseBlock(raw: Map<*, *>): ActionBlockConfig = ActionBlockConfig(
        name = raw["name"]?.toString(),
        conditions = listMaps(raw["if"] ?: raw["conditions"]).map { condition ->
            ConditionConfig(
                type = condition["type"]?.toString() ?: "unknown",
                values = rawValues(condition, setOf("type", "not", "negated")),
                negated = condition["not"]?.toString()?.toBooleanStrictOrNull()
                    ?: condition["negated"]?.toString()?.toBooleanStrictOrNull()
                    ?: false,
            )
        },
        thenActions = listMaps(raw["then"]).map { parseAction(it) },
        elseActions = listMaps(raw["else"]).map { parseAction(it) },
        execution = parseTriggerExecution(raw["execution"]?.toString()),
    )

    private fun parseAction(raw: Map<*, *>): ActionConfig =
        ActionConfig(raw["type"]?.toString() ?: "unknown", rawValues(raw, setOf("type")))

    private fun listMaps(value: Any?): List<Map<*, *>> =
        (value as? List<*>)?.mapNotNull { it as? Map<*, *> } ?: emptyList()

    private fun sectionValues(section: ConfigurationSection, excluded: Set<String>): Map<String, String> =
        section.getKeys(false).filterNot { excluded.contains(it) }.associateWith { section.get(it)?.toString().orEmpty() }

    private fun rawValues(raw: Map<*, *>, excluded: Set<String>): Map<String, String> =
        raw.entries.filterNot { excluded.contains(it.key.toString()) }
            .associate { it.key.toString() to it.value.toString() }

    private fun parseScope(raw: String?): EffectScope = when (raw?.lowercase(Locale.ROOT)) {
        "timed" -> EffectScope.TIMED
        "until_mode_end", "until-mode-end" -> EffectScope.UNTIL_MODE_END
        else -> EffectScope.WHILE_INSIDE
    }

    private fun parseCombination(raw: String?): EffectCombination = when (raw?.lowercase(Locale.ROOT)) {
        "exclusive" -> EffectCombination.EXCLUSIVE
        "stack" -> EffectCombination.STACK
        "merge_by_type", "merge-by-type" -> EffectCombination.MERGE_BY_TYPE
        else -> EffectCombination.HIGHEST_PRIORITY
    }

    private fun parseTriggerExecution(raw: String?): TriggerExecution = when (raw?.lowercase(Locale.ROOT)) {
        "primary", "primary_region", "primary-region" -> TriggerExecution.PRIMARY_REGION
        else -> TriggerExecution.ALL_ACTIVE
    }

    companion object {
        private val PARAMETER_PATTERN = Regex("\\$\\{([a-zA-Z0-9_-]+)}")
    }
}

/** 模板显示名：`name-key` 经语言文件解析，键缺失或未配置时回退字面量。 */
fun RegionsPlugin.templateDisplayName(template: RegionTemplate): String =
    templateDisplayName(template, null)

/**
 * [templateDisplayName] rendered in [viewer]'s locale（PLAN.md §4.2）：模板名是语言键，
 * 菜单必须按正在看这块按钮的玩家解析，否则 `locale-mode: player` 下中英玩家会看到对方的语言。
 */
fun RegionsPlugin.templateDisplayName(template: RegionTemplate, viewer: CommandSender?): String =
    template.nameKey?.let { key -> lang().labelFor(viewer, key, template.name) } ?: template.name

/** 模板显示描述：规则同 [templateDisplayName]。 */
fun RegionsPlugin.templateDisplayDescription(template: RegionTemplate): String =
    templateDisplayDescription(template, null)

/** [templateDisplayDescription] rendered in [viewer]'s locale. */
fun RegionsPlugin.templateDisplayDescription(template: RegionTemplate, viewer: CommandSender?): String =
    template.descriptionKey?.let { key -> lang().labelFor(viewer, key, template.description) } ?: template.description
