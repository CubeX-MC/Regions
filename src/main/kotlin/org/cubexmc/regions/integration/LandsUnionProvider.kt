package org.cubexmc.regions.integration

import org.cubexmc.regions.RegionsPlugin
import org.cubexmc.regions.model.UnionRef
import org.bukkit.plugin.Plugin
import java.lang.reflect.Method
import java.util.UUID

/**
 * Lands 的工会／国家来源。
 *
 * [getUnions] 走的是官方 API 的完整路线：`OfflinePlayer.getLands()` 表示玩家拥有或加入的**全部**
 * Land，`Land.getNation()` 允许为空，`MemberHolder.getULID()` 提供稳定标识；只有非空 Nation 才计入，
 * 并按 ULID 去重（PLAN.md §7.2）。[getUnion] 保持历史语义不变，供条件判断与历史奖励映射使用。
 *
 * 实际生产 Lands 版本的线程约束与具体反射签名仍须在 M5 的集成任务里实服验证。
 */
class LandsUnionProvider(private val plugin: RegionsPlugin) : UnionProvider {
    override val type: String = "lands"
    private var cachedIntegration: Any? = null
    private var cachedProvider: Plugin? = null
    private var warnedUnavailableApi = false
    private var warnedMissingApi = false

    /** `Nation ULID → Lands 持有对象`，只在 [allUnions] 成功枚举后填充。 */
    private val nationCache: MutableMap<String, Any> = java.util.concurrent.ConcurrentHashMap()

    override fun isAvailable(): Boolean =
        plugin.server.pluginManager.getPlugin("Lands")?.isEnabled == true && integration() != null

    override fun getUnion(playerId: UUID): UnionRef? {
        val land = landsOf(playerId).firstOrNull() ?: return null
        val nation = invoke(land, "getNation")
        val holder = nation ?: land
        return toRef(holder)
    }

    override fun getUnions(playerId: UUID): List<UnionRef> {
        val refs = LinkedHashMap<String, UnionRef>()
        for (land in landsOf(playerId)) {
            // 独立 Land（没有 Nation）不算国家：不能把 land 本身当成 Nation 分队。
            val nation = invoke(land, "getNation") ?: continue
            val ref = toRef(nation) ?: continue
            refs.putIfAbsent(ref.id, ref)
        }
        return refs.values.toList()
    }

    /**
     * 只有"装了 Lands 但 API 解析不了"才算依赖异常；玩家没有 Land / Land 没有 Nation
     * 属于"确实没有国家"，由调用方按各自文案处理。
     */
    override fun unavailableReason(): String? {
        if (plugin.server.pluginManager.getPlugin("Lands")?.isEnabled != true) return REASON_PLUGIN_MISSING
        return if (integration() == null) REASON_API_UNAVAILABLE else null
    }

    /**
     * 枚举服务器上的全部 Nation 供选队补全。不同 Lands 版本的枚举入口不一致
     * （`getNations()` / `getNationHolders()`），拿不到就返回空列表——补全宁可不提示，
     * 也不能编造 Nation ID。顺带把 `ULID → 持有对象` 缓存下来，供敌对关系查询使用。
     */
    override fun allUnions(): List<UnionRef> {
        val holders = nationHolders() ?: return emptyList()
        return holders.mapNotNull { holder ->
            toRef(holder)?.also { ref -> nationCache[ref.id] = holder }
        }.distinctBy { it.id }
    }

    /**
     * 敌对关系查询。解析不到任一方就返回 null 表示"无法验证"；
     * 高级 `enemy-only` 会因此拒绝开赛，而不是当成非敌对放行。
     */
    override fun areNationsEnemy(a: String, b: String): Boolean? {
        val holderA = nationCache[a] ?: return null
        val holderB = nationCache[b] ?: return null
        return invoke(holderA, "isEnemy", holderB) as? Boolean
            ?: invoke(holderA, "isEnemyNation", holderB) as? Boolean
    }

    private fun nationHolders(): List<Any>? {
        val integration = integration() ?: return null
        val pluginInstance = plugin.server.pluginManager.getPlugin("Lands")
        return sequenceOf(
            invoke(integration, "getNations"),
            invoke(integration, "getNationHolders"),
            pluginInstance?.let { invoke(it, "getNations") },
        ).filterIsInstance<Iterable<*>>().firstOrNull()?.filterNotNull()
    }

    override fun areSameUnion(a: UUID, b: UUID): Boolean {
        val unionA = getUnion(a) ?: return false
        val unionB = getUnion(b) ?: return false
        return unionA.id == unionB.id
    }

    override fun areAllied(a: UUID, b: UUID): Boolean {
        val holderA = memberHolder(a) ?: return false
        val holderB = memberHolder(b) ?: return false
        return invoke(holderA, "isAlly", holderB) as? Boolean ?: false
    }

    override fun areEnemies(a: UUID, b: UUID): Boolean {
        val holderA = memberHolder(a) ?: return false
        val holderB = memberHolder(b) ?: return false
        return invoke(holderA, "isEnemy", holderB) as? Boolean ?: false
    }

    override fun placeholder(playerId: UUID, key: String): String? {
        val union = getUnion(playerId) ?: return null
        return when (key.lowercase()) {
            "id" -> union.id
            "name" -> union.name
            "type" -> union.providerType
            else -> null
        }
    }

    private fun toRef(holder: Any): UnionRef? {
        val id = stringValue(holder, "getULID") ?: stringValue(holder, "getName") ?: return null
        val name = stringValue(holder, "getName") ?: id
        return UnionRef(id, name, type)
    }

    private fun memberHolder(playerId: UUID): Any? {
        val land = landsOf(playerId).firstOrNull() ?: return null
        return invoke(land, "getNation") ?: land
    }

    /** 玩家拥有或加入的全部 Land；API 缺席、查询失败都返回空集合，由 [unavailableReason] 区分原因。 */
    private fun landsOf(playerId: UUID): List<Any> {
        val integration = integration() ?: return emptyList()
        val player = invoke(integration, "getLandPlayer", playerId)
            ?: invoke(integration, "getPlayer", playerId)
            ?: return emptyList()
        val lands = invoke(player, "getLands") as? Iterable<*>
            ?: invoke(player, "getTrustedLands") as? Iterable<*>
            ?: return emptyList()
        return lands.filterNotNull()
    }

    private fun integration(): Any? {
        val provider = plugin.server.pluginManager.getPlugin("Lands")?.takeIf(Plugin::isEnabled)
        if (provider == null) {
            cachedProvider = null
            cachedIntegration = null
            return null
        }
        if (cachedProvider === provider) cachedIntegration?.let { return it }
        cachedProvider = null
        cachedIntegration = null
        val type = classOrNull(provider, "me.angeschossen.lands.api.LandsIntegration")
            ?: classOrNull(provider, "me.angeschossen.lands.api.integration.LandsIntegration")
            ?: run {
                warnApi("Lands is installed, but LandsIntegration API class was not found.")
                return null
            }
        val integration = invokeStatic(type, "of", plugin)
            ?: invokeStatic(type, "getInstance")
            ?: invokeStatic(type, "get")
            ?: run {
                warnApi("LandsIntegration API entrypoint was not found.")
                return null
            }
        cachedIntegration = integration
        cachedProvider = provider
        return integration
    }

    private fun invoke(target: Any, methodName: String, vararg args: Any?): Any? =
        invokeMethod(target.javaClass.methods.asIterable(), target, methodName, *args)

    private fun invokeStatic(type: Class<*>, methodName: String, vararg args: Any?): Any? =
        invokeMethod(type.methods.asIterable(), null, methodName, *args)

    private fun invokeMethod(methods: Iterable<Method>, target: Any?, methodName: String, vararg args: Any?): Any? {
        for (method in methods) {
            if (!method.name.equals(methodName, ignoreCase = true) || method.parameterCount != args.size) {
                continue
            }
            return try {
                method.invoke(target, *args)
            } catch (ex: ReflectiveOperationException) {
                null
            } catch (ex: IllegalArgumentException) {
                null
            }
        }
        return null
    }

    private fun stringValue(target: Any, methodName: String): String? =
        invoke(target, methodName)?.toString()

    private fun warnApi(message: String) {
        if (!warnedUnavailableApi) {
            plugin.log().warn(message)
            warnedUnavailableApi = true
        }
    }

    @Suppress("unused")
    private fun warnMissingApi(message: String) {
        if (!warnedMissingApi) {
            plugin.log().warn(message)
            warnedMissingApi = true
        }
    }

    private fun classOrNull(provider: Plugin, name: String): Class<*>? =
        try {
            Class.forName(name, true, provider.javaClass.classLoader)
        } catch (ex: ClassNotFoundException) {
            null
        }

    companion object {
        /** 稳定原因码：`labels.dependency.*` 之外单独区分 Lands 的两种失败。 */
        const val REASON_PLUGIN_MISSING = "union-lands-missing"
        const val REASON_API_UNAVAILABLE = "union-lands-api-unavailable"
    }
}
