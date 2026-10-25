package com.clrg.core.party

import com.clrg.core.json.JObj
import com.clrg.core.json.Json
import com.clrg.core.tracking.Hand
import com.clrg.core.tracking.MovementMode

/** Simple persistent string storage (SharedPreferences on Android, a map in tests). */
interface KeyValueStore {
    fun get(key: String): String?
    fun put(key: String, value: String?)
}

class MemoryStore : KeyValueStore {
    val map = HashMap<String, String>()
    override fun get(key: String): String? = map[key]
    override fun put(key: String, value: String?) { if (value == null) map.remove(key) else map[key] = value }
}

enum class QualityMode(val label: String) { AUTO("Auto"), PERFORMANCE("Performance"), QUALITY("Quality") }

/** All user settings. Persisted as JSON. */
data class Settings(
    var musicVolume: Float = 0.6f,
    var sfxVolume: Float = 0.85f,
    // accessibility
    var highContrast: Boolean = false,
    var reducedEffects: Boolean = false,
    var largeText: Boolean = false,
    var captions: Boolean = true,
    var dwellSeconds: Float = 1.5f,
    var gestureSensitivity: Float = 1f,
    var steeringOnlySlalom: Boolean = false,
    // solo play
    var cpuLevel: String = "MEDIUM",
    // camera / tracking
    var cameraId: String? = null,
    var wideAngle: Boolean = false,
    var mirror: Boolean = true,
    var quality: QualityMode = QualityMode.AUTO,
    // display
    var preferTvAsSecondScreen: Boolean = true,
    var showCameraInset: Boolean = true,
    // developer
    var devOverlay: Boolean = false,
    var skeletonOverlay: Boolean = false,
    var simulatedInput: Boolean = false,
) {
    fun toJson(): String = Json.stringify(
        mapOf(
            "v" to 1, "musicVolume" to musicVolume, "sfxVolume" to sfxVolume, "highContrast" to highContrast,
            "reducedEffects" to reducedEffects, "largeText" to largeText, "captions" to captions, "dwellSeconds" to dwellSeconds,
            "gestureSensitivity" to gestureSensitivity, "steeringOnlySlalom" to steeringOnlySlalom, "cpuLevel" to cpuLevel, "cameraId" to cameraId,
            "wideAngle" to wideAngle, "mirror" to mirror, "quality" to quality.name, "preferTvAsSecondScreen" to preferTvAsSecondScreen,
            "showCameraInset" to showCameraInset, "devOverlay" to devOverlay, "skeletonOverlay" to skeletonOverlay,
        ),
    )

    companion object {
        const val KEY = "settings"

        fun fromJson(text: String?): Settings {
            val o = JObj.parse(text) ?: return Settings()
            val d = Settings()
            return Settings(
                musicVolume = o.float("musicVolume", d.musicVolume).coerceIn(0f, 1f),
                sfxVolume = o.float("sfxVolume", d.sfxVolume).coerceIn(0f, 1f),
                highContrast = o.bool("highContrast", d.highContrast),
                reducedEffects = o.bool("reducedEffects", d.reducedEffects),
                largeText = o.bool("largeText", d.largeText),
                captions = o.bool("captions", d.captions),
                dwellSeconds = o.float("dwellSeconds", d.dwellSeconds).coerceIn(0.8f, 3f),
                gestureSensitivity = o.float("gestureSensitivity", d.gestureSensitivity).coerceIn(0.5f, 2f),
                steeringOnlySlalom = o.bool("steeringOnlySlalom", d.steeringOnlySlalom),
                cpuLevel = o.str("cpuLevel", d.cpuLevel),
                cameraId = (o.map["cameraId"] as? String),
                wideAngle = o.bool("wideAngle", d.wideAngle),
                mirror = o.bool("mirror", d.mirror),
                quality = runCatching { QualityMode.valueOf(o.str("quality", d.quality.name)) }.getOrDefault(d.quality),
                preferTvAsSecondScreen = o.bool("preferTvAsSecondScreen", d.preferTvAsSecondScreen),
                showCameraInset = o.bool("showCameraInset", d.showCameraInset),
                devOverlay = o.bool("devOverlay", d.devOverlay),
                skeletonOverlay = o.bool("skeletonOverlay", d.skeletonOverlay),
                // simulated input is never restored from storage: it must be switched on deliberately each session
                simulatedInput = false,
            )
        }

        fun load(store: KeyValueStore) = fromJson(store.get(KEY))
    }

    fun save(store: KeyValueStore) = store.put(KEY, toJson())
}

/** A family member who can play. */
data class PlayerProfile(
    val id: Int,
    var name: String,
    var colorIndex: Int,
    var hand: Hand? = null,
    var mode: MovementMode = MovementMode.STANDARD,
) {
    fun toMap(): Map<String, Any?> = mapOf("id" to id, "name" to name, "color" to colorIndex, "hand" to hand?.name, "mode" to mode.name)

    companion object {
        fun fromObj(o: JObj): PlayerProfile = PlayerProfile(
            o.int("id"), o.str("name", "Player").take(MAX_NAME), o.int("color"),
            (o.map["hand"] as? String)?.let { runCatching { Hand.valueOf(it) }.getOrNull() },
            runCatching { MovementMode.valueOf(o.str("mode", "STANDARD")) }.getOrDefault(MovementMode.STANDARD),
        )
        const val MAX_NAME = 12
    }
}

/** The family roster (persisted). Always has at least two players. */
class Roster(val players: MutableList<PlayerProfile> = mutableListOf()) {
    fun ensureDefaults() {
        if (players.size < 2) {
            if (players.none { it.id == 1 }) players.add(PlayerProfile(1, "Player 1", 0))
            if (players.none { it.id == 2 }) players.add(PlayerProfile(2, "Player 2", 1))
        }
    }

    fun byId(id: Int) = players.firstOrNull { it.id == id }

    fun add(name: String): PlayerProfile {
        val id = (players.maxOfOrNull { it.id } ?: 0) + 1
        val used = players.map { it.colorIndex }.toSet()
        val color = (0 until 8).firstOrNull { it !in used } ?: (id % 8)
        return PlayerProfile(id, name.take(PlayerProfile.MAX_NAME), color).also { players.add(it) }
    }

    fun remove(id: Int): Boolean {
        if (players.size <= 2) return false
        return players.removeAll { it.id == id }
    }

    fun toJson(): String = Json.stringify(mapOf("players" to players.map { it.toMap() }))

    fun save(store: KeyValueStore) = store.put(KEY, toJson())

    companion object {
        const val KEY = "roster"
        val SUGGESTED_NAMES = listOf("Holly", "Ivy", "Noel", "Jasper", "Robin", "Clementine", "Frost", "Juniper", "Sprout", "Pip", "Mistle", "Twinkle", "Comet", "Bramble", "Nutmeg", "Ginger")

        fun load(store: KeyValueStore): Roster {
            val o = JObj.parse(store.get(KEY))
            val r = Roster(o?.objList("players")?.map { PlayerProfile.fromObj(it) }?.toMutableList() ?: mutableListOf())
            r.ensureDefaults()
            return r
        }
    }
}
