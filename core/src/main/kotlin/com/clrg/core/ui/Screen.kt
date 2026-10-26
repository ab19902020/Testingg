package com.clrg.core.ui

import com.clrg.core.app.GameApp
import com.clrg.core.audio.Ambience
import com.clrg.core.audio.MusicTrack
import com.clrg.core.audio.Room
import com.clrg.core.render.Gfx

/** A full-screen page of the TV interface. */
abstract class Screen(val app: GameApp) {
    /** Music for this screen (null = keep whatever is playing). */
    open val music: MusicTrack? = MusicTrack.MENU
    /** Background ambience (null = keep whatever is playing). Menus: a quiet fire in Christmas mode, none otherwise. */
    open val ambience: Ambience? get() = if (app.christmasMode) Ambience.FIRESIDE else Ambience.NONE
    /** Acoustic space the effects are heard in (null = keep). */
    open val room: Room? = Room.STUDIO
    /** Whether hands-free dwell cursors may operate this screen. */
    open val allowCursors: Boolean = true
    /**
     * Hands-free pointing here needs Player 1 to have waved to take control (menus). Screens where the person playing
     * points as part of their turn (a golfer choosing a club) turn this off.
     */
    open val needsWave: Boolean = true
    /** Player 2 gets a pointer too. Off for menus: only Player 1 (the red player) steers them. */
    open val secondPointer: Boolean = false
    /** Whether this screen needs the camera (shows camera problems prominently). */
    open val needsCamera: Boolean = false

    open fun onEnter() {}
    open fun onExit() {}

    /** Update + draw in one pass (immediate-mode UI). */
    abstract fun frame(g: Gfx, ui: Ui, dt: Float)

    /** Back / Escape. Return true when handled. */
    open fun onBack(): Boolean { app.pop(); return true }
}
