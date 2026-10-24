package com.clrg.core.party

import com.clrg.core.json.JObj
import com.clrg.core.json.Json
import com.clrg.core.math.Rng

enum class TournamentLength(val label: String, val description: String) {
    QUICK("Quick", "Everyone plays twice"),
    FULL("Full", "Everyone plays everyone once"),
    DOUBLE("Marathon", "Everyone plays everyone twice"),
}

class TournamentMatch(
    val p1: Int,
    val p2: Int,
    val gameId: String,
    var played: Boolean = false,
    var skipped: Boolean = false,
    /** Winner's player id, 0 for a tie, -1 when not played. */
    var winnerId: Int = -1,
    var score1: Int = 0,
    var score2: Int = 0,
) {
    fun toMap() = mapOf("p1" to p1, "p2" to p2, "game" to gameId, "played" to played, "skipped" to skipped, "winner" to winnerId, "s1" to score1, "s2" to score2)
    fun involves(id: Int) = p1 == id || p2 == id

    companion object {
        fun fromObj(o: JObj) = TournamentMatch(o.int("p1"), o.int("p2"), o.str("game"), o.bool("played"), o.bool("skipped"), o.int("winner", -1), o.int("s1"), o.int("s2"))
    }
}

data class Standing(val playerId: Int, val played: Int, val wins: Int, val ties: Int, val losses: Int) {
    val points: Int get() = wins * Tournament.WIN_POINTS + ties * Tournament.TIE_POINTS
}

/**
 * Party tournament for 2-8 players. Two people play each match; pairs rotate (round-robin "circle" method) so
 * everybody meets everybody and nobody plays twice in a row when it can be avoided. Scores persist on the device so a
 * tournament survives the app being closed.
 */
class Tournament(val participants: List<Int>, val matches: MutableList<TournamentMatch>, var currentIndex: Int = 0, val length: TournamentLength = TournamentLength.FULL) {

    val isComplete: Boolean get() = currentIndex >= matches.size
    val current: TournamentMatch? get() = matches.getOrNull(currentIndex)
    val playedCount: Int get() = matches.count { it.played }

    fun record(winnerId: Int, score1: Int, score2: Int) {
        val m = current ?: return
        m.played = true
        m.winnerId = winnerId
        m.score1 = score1
        m.score2 = score2
        currentIndex++
    }

    fun skipCurrent() {
        val m = current ?: return
        m.skipped = true
        currentIndex++
    }

    fun standings(): List<Standing> = participants.map { id ->
        val ms = matches.filter { it.played && it.involves(id) }
        Standing(id, ms.size, ms.count { it.winnerId == id }, ms.count { it.winnerId == 0 }, ms.count { it.winnerId != id && it.winnerId > 0 })
    }.sortedWith(compareByDescending<Standing> { it.points }.thenByDescending { it.wins }.thenBy { it.playerId })

    /** Ids of the leaders (more than one when tied on points and wins). */
    fun leaders(): List<Int> {
        val s = standings()
        if (s.isEmpty()) return emptyList()
        return s.filter { it.points == s[0].points && it.wins == s[0].wins }.map { it.playerId }
    }

    fun toJson(): String = Json.stringify(mapOf("participants" to participants, "matches" to matches.map { it.toMap() }, "current" to currentIndex, "length" to length.name))

    fun save(store: KeyValueStore) = store.put(KEY, toJson())

    companion object {
        const val KEY = "tournament"
        const val WIN_POINTS = 3
        const val TIE_POINTS = 1

        fun load(store: KeyValueStore): Tournament? {
            val o = JObj.parse(store.get(KEY)) ?: return null
            val parts = o.list("participants").mapNotNull { (it as? Number)?.toInt() }
            if (parts.size < 2) return null
            val ms = o.objList("matches").map { TournamentMatch.fromObj(it) }.toMutableList()
            val len = runCatching { TournamentLength.valueOf(o.str("length", "FULL")) }.getOrDefault(TournamentLength.FULL)
            return Tournament(parts, ms, o.int("current").coerceIn(0, ms.size), len)
        }

        fun clear(store: KeyValueStore) = store.put(KEY, null)

        /** Round-robin rounds via the circle method; each round is a list of disjoint pairs (byes dropped). */
        fun roundRobin(ids: List<Int>): List<List<Pair<Int, Int>>> {
            val list = ids.toMutableList<Int?>()
            if (list.size % 2 == 1) list.add(null)
            val n = list.size
            val rounds = ArrayList<List<Pair<Int, Int>>>()
            val arr = list.toMutableList()
            for (r in 0 until n - 1) {
                val pairs = ArrayList<Pair<Int, Int>>()
                for (i in 0 until n / 2) {
                    val a = arr[i]
                    val b = arr[n - 1 - i]
                    if (a != null && b != null) pairs.add(if (r % 2 == 0) a to b else b to a)
                }
                rounds.add(pairs)
                // rotate all but the first
                val last = arr.removeAt(n - 1)
                arr.add(1, last)
            }
            return rounds
        }

        fun create(participants: List<Int>, length: TournamentLength, gameIds: List<String>, seed: Long): Tournament {
            require(participants.size >= 2) { "need at least two players" }
            require(gameIds.isNotEmpty())
            val rng = Rng(seed)
            var rounds = roundRobin(participants)
            if (length == TournamentLength.DOUBLE) rounds = rounds + rounds.map { r -> r.map { (a, b) -> b to a } }
            val pairs = ArrayList<Pair<Int, Int>>()
            for (round in rounds) {
                val ordered = round.toMutableList()
                // avoid the same person playing twice in a row across round boundaries
                val prev = pairs.lastOrNull()
                if (prev != null && ordered.size > 1) {
                    val idx = ordered.indexOfFirst { it.first != prev.first && it.first != prev.second && it.second != prev.first && it.second != prev.second }
                    if (idx > 0) { val p = ordered.removeAt(idx); ordered.add(0, p) }
                }
                pairs.addAll(ordered)
            }
            val chosen: List<Pair<Int, Int>> = if (length == TournamentLength.QUICK) {
                val count = HashMap<Int, Int>()
                val out = ArrayList<Pair<Int, Int>>()
                for (p in pairs) {
                    if (participants.all { (count[it] ?: 0) >= 2 }) break
                    if ((count[p.first] ?: 0) >= 2 && (count[p.second] ?: 0) >= 2) continue
                    out.add(p)
                    count[p.first] = (count[p.first] ?: 0) + 1
                    count[p.second] = (count[p.second] ?: 0) + 1
                }
                out
            } else pairs
            // games: shuffled cycles so every game is used before any repeats
            val games = ArrayList<String>()
            while (games.size < chosen.size) games.addAll(gameIds.toMutableList().also { rng.shuffle(it) })
            val matches = chosen.mapIndexed { i, (a, b) -> TournamentMatch(a, b, games[i]) }.toMutableList()
            return Tournament(participants, matches, 0, length)
        }
    }
}
