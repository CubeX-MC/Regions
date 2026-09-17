package org.cubexmc.regions.match

import kotlin.math.sqrt

/**
 * 一个点位（出生点、出场点、观战点）的纯文本表示。
 *
 * 与 Regions 现有的 `world,x,y,z[,yaw,pitch]` 保持一致；列表用 `;` 分隔，
 * 这样同一个字段既能写在 `regions.yml` 里，也能由 GUI 的"站在这里设点"写入。
 */
data class SpawnPoint(
    val world: String,
    val x: Double,
    val y: Double,
    val z: Double,
    val yaw: Float = 0f,
    val pitch: Float = 0f,
) {
    fun distanceTo(other: SpawnPoint): Double {
        if (!world.equals(other.world, ignoreCase = true)) return Double.MAX_VALUE
        val dx = x - other.x
        val dy = y - other.y
        val dz = z - other.z
        return sqrt(dx * dx + dy * dy + dz * dz)
    }
}

/** 出生点/点位字段的解析与校验；不依赖 Bukkit 服务器实例，可单测。 */
object MatchSpawns {

    /** 大乱斗出生点建议的最小间距（格）；不足时校验指向"补充出生点"（PLAN.md §7.3）。 */
    const val RECOMMENDED_SPACING = 6.0

    fun parseList(raw: String?): List<SpawnPoint> =
        raw?.split(';')
            ?.mapNotNull { parse(it) }
            .orEmpty()

    fun parse(raw: String?): SpawnPoint? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        val parts = trimmed.split(',')
        if (parts.size < 4) return null
        val world = parts[0].trim()
        if (world.isEmpty()) return null
        val x = parts[1].trim().toDoubleOrNull() ?: return null
        val y = parts[2].trim().toDoubleOrNull() ?: return null
        val z = parts[3].trim().toDoubleOrNull() ?: return null
        val yaw = parts.getOrNull(4)?.trim()?.toFloatOrNull() ?: 0f
        val pitch = parts.getOrNull(5)?.trim()?.toFloatOrNull() ?: 0f
        return SpawnPoint(world, x, y, z, yaw, pitch)
    }

    /** 返回无法解析的原始条目，供校验报错定位到具体下标。 */
    fun invalidEntries(raw: String?): List<String> =
        raw?.split(';')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() && parse(it) == null }
            .orEmpty()

    /** 间距不足的点位下标对（同一世界内）。 */
    fun spacingViolations(points: List<SpawnPoint>, minimum: Double = RECOMMENDED_SPACING): List<Pair<Int, Int>> {
        val violations = ArrayList<Pair<Int, Int>>()
        for (i in points.indices) {
            for (j in i + 1 until points.size) {
                if (points[i].distanceTo(points[j]) < minimum) violations += i to j
            }
        }
        return violations
    }

    /** 不同世界或重复坐标的点位集合按"有效不重复点位"计数。 */
    fun distinctCount(points: List<SpawnPoint>): Int =
        points.distinctBy { "${it.world.lowercase()}:${it.x}:${it.y}:${it.z}" }.size
}
