package com.clrg.core.games

import com.clrg.core.game.GameEnv
import com.clrg.core.game.GameInfo
import com.clrg.core.game.GameStyle
import com.clrg.core.game.MiniGame

/** The two halves of the console: everyday sports and arcade games, and the Christmas collection. */
enum class GameMode(val title: String, val blurb: String) {
    SPORTS("Sports & Arcade", "Shooting, boxing, archery, tennis and more"),
    CHRISTMAS("Christmas", "Snowball fights, sleighs, reindeer and festive sports"),
}

/** Every minigame, by mode. Some sports appear in both modes with a different look (and their own id). */
object GameRegistry {
    class Entry(val info: GameInfo, val mode: GameMode, val style: GameStyle, private val make: (GameEnv) -> MiniGame) {
        fun create(env: GameEnv): MiniGame { env.style = style; return make(env) }
    }

    private fun xmas(info: GameInfo, make: (GameEnv) -> MiniGame) = Entry(info, GameMode.CHRISTMAS, GameStyle.CHRISTMAS, make)
    private fun sport(info: GameInfo, make: (GameEnv) -> MiniGame) = Entry(info, GameMode.SPORTS, GameStyle.SPORTS, make)

    /** The original ten Christmas minigames (ids unchanged, so saved tournaments keep working). */
    val christmas: List<Entry> = listOf(
        xmas(TennisGame.INFO, ::TennisGame),
        xmas(SnowballGame.INFO, ::SnowballGame),
        xmas(PresentCatchGame.INFO, ::PresentCatchGame),
        xmas(ReindeerRaceGame.INFO, ::ReindeerRaceGame),
        xmas(BowlingGame.INFO, ::BowlingGame),
        xmas(TableTennisGame.INFO, ::TableTennisGame),
        xmas(DanceOffGame.INFO, ::DanceOffGame),
        xmas(SleighSlalomGame.INFO, ::SleighSlalomGame),
        xmas(GoalkeeperGame.INFO, ::GoalkeeperGame),
        xmas(DecorateTreeGame.INFO, ::DecorateTreeGame),
    )

    val sports: List<Entry> = listOf(
        sport(ClayShootingGame.INFO, ::ClayShootingGame),
        sport(BoxingGame.INFO, ::BoxingGame),
        sport(ArcheryGame.INFO, ::ArcheryGame),
        sport(ShootingGalleryGame.INFO, ::ShootingGalleryGame),
        sport(DartsGame.INFO, ::DartsGame),
        sport(GoalkeeperGame.SPORTS_INFO, ::GoalkeeperGame),
        sport(TennisGame.SPORTS_INFO, ::TennisGame),
        sport(TableTennisGame.SPORTS_INFO, ::TableTennisGame),
        sport(BowlingGame.SPORTS_INFO, ::BowlingGame),
    )

    val all: List<Entry> = christmas + sports

    val ids: List<String> get() = all.map { it.info.id }

    fun forMode(mode: GameMode): List<Entry> = all.filter { it.mode == mode }

    fun byId(id: String): Entry? = all.firstOrNull { it.info.id == id }

    /** Short tags shown on a game's card: how it is played and what it can use. */
    fun tags(id: String): List<String> = when (id) {
        "clays" -> listOf("3D", "Finger gun", "Toy gun")
        "boxing" -> listOf("3D", "1 v 1", "Punch & guard")
        "archery" -> listOf("3D", "Draw & release")
        "gallery" -> listOf("Finger gun", "Toy gun")
        "darts" -> listOf("Aim", "Throw")
        "goalie", "goalie_s" -> listOf("Reach", "Reactions")
        "tennis", "tennis_s" -> listOf("Swing", "Toy racket")
        "tabletennis", "tabletennis_s" -> listOf("Swing", "Toy bat")
        "bowling", "bowling_s" -> listOf("Roll", "Aim")
        "snowball" -> listOf("Throw", "Dodge", "Shield")
        "catch" -> listOf("Catch", "Combos")
        "reindeer" -> listOf("Arm pump", "Race")
        "dance" -> listOf("Dance", "Rhythm")
        "slalom" -> listOf("Lean", "Race")
        "tree" -> listOf("Point & hold", "Calm")
        else -> emptyList()
    }
}
