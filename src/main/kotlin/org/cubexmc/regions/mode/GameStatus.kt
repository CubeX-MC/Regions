package org.cubexmc.regions.mode

/**
 * 一场比赛的稳定阶段。显示名走 `labels.state.*`，玩家界面不再直接读这个枚举名。
 */
enum class GamePhase { IDLE, WAITING, RUNNING }

/**
 * 一块场地的比赛状态快照：Service 只返回语义数据，不拼接给玩家看的句子。
 *
 * 玩家可见文本由 [gameStatusLine]（ModeMessages.kt）按语言文件渲染；
 * [extra] 的键是稳定计数器标识（finished/seekers/hiders/found/released/unions），
 * 显示名走 `labels.counter.*`。
 */
class GameStatus(
    val regionId: String,
    val modeType: String,
    val phase: GamePhase,
    val players: Int = 0,
    val ready: Int = 0,
    val extra: Map<String, Int> = emptyMap(),
)
