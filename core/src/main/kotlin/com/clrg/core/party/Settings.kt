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
    var dwellSeconds: Float = 1.2f,
    var gestureSensitivity: Float = 1f,
    var steeringOnlySlalom: Boolean = false,
    // solo play
    var cpuLevel: String = "MEDIUM",
    // camera / tracking
    var cameraId: String? = null,
    var wideAngle: Boolean = false,
    /** Default for cameras not yet checked: show the picture like a mirror (your right hand on the right). */
    var mirror: Boolean = true,
    /**
     * Per camera id: whether that camera's picture must be mirrored so movements go the right way. Phones differ (some
     * deliver the front camera already mirrored), so this is found by the left/right check or set by hand.
     */
    val mirrorByCamera: MutableMap<String, Boolean> = mutableMapOf(),
    /** Camera ids whose picture arrives upside down (turned the right way up automatically). */
    val upsideDownCameras: MutableSet<String> = mutableSetOf(),
    /** Camera ids whose left/right direction has been checked with a player. */
    val directionChecked: MutableSet<String> = mutableSetOf(),
    var quality: QualityMode = QualityMode.AUTO,
    /** Let the camera run at 60 fps when the phone keeps up (smoother, quicker tracking; a little more heat). */
    var highFpsCamera: Boolean = true,
    /**
     * Delay between the game drawing a frame and the players seeing it, plus their usual reaction habit (ms): the
     * phone display and the TV. Measured by the timing test; timing games allow for it.
     */
    var displayDelayMs: Int = 60,
    /** True once the timing test has measured [displayDelayMs] (otherwise it is a typical default). */
    var timingMeasured: Boolean = false,
    // display
    var preferTvAsSecondScreen: Boolean = true,
    var showCameraInset: Boolean = true,
    // developer
    var devOverlay: Boolean = false,
    /** Learned held objects (stick / toy racket) by player profile id: their encoded colour models. */
    val heldObjects: MutableMap<Int, String> = mutableMapOf(),
    /** Use the learned held objects in games (racket, bat, club follow the object). */
    var useHeldObjects: Boolean = true,
    /**
     * Sharper fast movements: a short camera exposure (less motion blur on swinging arms, at the cost of a slightly
     * grainier picture) on phones that allow it.
     */
    var sharpMotion: Boolean = true,
    var skeletonOverlay: Boolean = false,
    var simulatedInput: Boolean = false,
    /** Which half of the console the menus show: "SPORTS" or "CHRISTMAS". */
    var mode: String = "SPORTS",
) {
    fun toJson(): String = Json.stringify(
        mapOf(
            "v" to 2, "musicVolume" to musicVolume, "sfxVolume" to sfxVolume, "highContrast" to highContrast,
            "reducedEffects" to reducedEffects, "largeText" to largeText, "captions" to captions, "dwellSeconds" to dwellSeconds,
            "gestureSensitivity" to gestureSensitivity, "steeringOnlySlalom" to steeringOnlySlalom, "cpuLevel" to cpuLevel, "cameraId" to cameraId,
            "wideAngle" to wideAngle, "mirror" to mirror,
            "mirrorByCamera" to mirrorByCamera.entries.joinToString(",") { "${it.key}:${if (it.value) 1 else 0}" },
            "upsideDownCameras" to upsideDownCameras.joinToString(","), "directionChecked" to directionChecked.joinToString(","), "quality" to quality.name, "highFpsCamera" to highFpsCamera,
            "displayDelayMs" to displayDelayMs, "timingMeasured" to timingMeasured, "preferTvAsSecondScreen" to preferTvAsSecondScreen,
            "showCameraInset" to showCameraInset, "devOverlay" to devOverlay, "skeletonOverlay" to skeletonOverlay,
            "heldObjects" to heldObjects.entries.joinToString("|") { "${it.key}=${it.value}" }, "useHeldObjects" to useHeldObjects,
            "sharpMotion" to sharpMotion, "mode" to mode,
        ),
    )

    companion object {
        const val KEY = "settings"
        const val MIN_DELAY_MS = -100
        const val MAX_DELAY_MS = 400

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
                // version 1 saved the old 1.5 s default; the steadier pointer of version 2 selects well in 1.2 s
                dwellSeconds = o.float("dwellSeconds", d.dwellSeconds).let { if (o.int("v", 1) < 2 && it == 1.5f) d.dwellSeconds else it }.coerceIn(0.8f, 3f),
                gestureSensitivity = o.float("gestureSensitivity", d.gestureSensitivity).coerceIn(0.5f, 2f),
                steeringOnlySlalom = o.bool("steeringOnlySlalom", d.steeringOnlySlalom),
                cpuLevel = o.str("cpuLevel", d.cpuLevel),
                cameraId = (o.map["cameraId"] as? String),
                wideAngle = o.bool("wideAngle", d.wideAngle),
                mirror = o.bool("mirror", d.mirror),
                mirrorByCamera = o.str("mirrorByCamera").split(",").filter { it.contains(":") }.associate { it.substringBeforeLast(":") to (it.substringAfterLast(":") == "1") }.toMutableMap(),
                upsideDownCameras = o.str("upsideDownCameras").split(",").filter { it.isNotEmpty() }.toMutableSet(),
                directionChecked = o.str("directionChecked").split(",").filter { it.isNotEmpty() }.toMutableSet(),
                quality = runCatching { QualityMode.valueOf(o.str("quality", d.quality.name)) }.getOrDefault(d.quality),
                highFpsCamera = o.bool("highFpsCamera", d.highFpsCamera),
                displayDelayMs = o.int("displayDelayMs", d.displayDelayMs).coerceIn(MIN_DELAY_MS, MAX_DELAY_MS),
                timingMeasured = o.bool("timingMeasured", d.timingMeasured),
                preferTvAsSecondScreen = o.bool("preferTvAsSecondScreen", d.preferTvAsSecondScreen),
                showCameraInset = o.bool("showCameraInset", d.showCameraInset),
                devOverlay = o.bool("devOverlay", d.devOverlay),
                heldObjects = o.str("heldObjects").split("|").mapNotNull { e -> val i = e.indexOf('='); if (i <= 0) null else e.substring(0, i).toIntOrNull()?.let { it to e.substring(i + 1) } }.toMap().toMutableMap(),
                useHeldObjects = o.bool("useHeldObjects", d.useHeldObjects),
                sharpMotion = o.bool("sharpMotion", d.sharpMotion),
                skeletonOverlay = o.bool("skeletonOverlay", d.skeletonOverlay),
                mode = o.str("mode", d.mode).takeIf { it == "SPORTS" || it == "CHRISTMAS" } ?: d.mode,
                // simulated input is never restored from storage: it must be switched on deliberately each session
                simulatedInput = false,
            )
        }

        fun load(store: KeyValueStore) = fromJson(store.get(KEY))
    }

    fun save(store: KeyValueStore) = store.put(KEY, toJson())

    /** Whether the picture from camera [id] is mirrored (the per-camera result if known, else the default). */
    fun mirrorFor(id: String?): Boolean = id?.let { mirrorByCamera[it] } ?: mirror

    /** Whether camera [id]'s picture needs turning the right way up. */
    fun upsideDown(id: String?): Boolean = id != null && id in upsideDownCameras
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
