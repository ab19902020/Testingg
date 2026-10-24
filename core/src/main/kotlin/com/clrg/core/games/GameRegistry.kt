package com.clrg.core.games

import com.clrg.core.game.GameEnv
import com.clrg.core.game.GameInfo
import com.clrg.core.game.MiniGame

/** The agreed initial collection: ten complete two-player minigames. */
object GameRegistry {
    class Entry(val info: GameInfo, val create: (GameEnv) -> MiniGame)

    val all: List<Entry> = listOf(
        Entry(TennisGame.INFO, ::TennisGame),
        Entry(SnowballGame.INFO, ::SnowballGame),
        Entry(PresentCatchGame.INFO, ::PresentCatchGame),
        Entry(ReindeerRaceGame.INFO, ::ReindeerRaceGame),
        Entry(BowlingGame.INFO, ::BowlingGame),
        Entry(TableTennisGame.INFO, ::TableTennisGame),
        Entry(DanceOffGame.INFO, ::DanceOffGame),
        Entry(SleighSlalomGame.INFO, ::SleighSlalomGame),
        Entry(GoalkeeperGame.INFO, ::GoalkeeperGame),
        Entry(DecorateTreeGame.INFO, ::DecorateTreeGame),
    )

    val ids: List<String> get() = all.map { it.info.id }

    fun byId(id: String): Entry? = all.firstOrNull { it.info.id == id }
}
