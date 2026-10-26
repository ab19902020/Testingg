package com.clrg.core.games

import com.clrg.core.audio.MusicTrack
import com.clrg.core.audio.Sfx
import com.clrg.core.game.GameEnv
import com.clrg.core.game.GameInfo
import com.clrg.core.game.Hud
import com.clrg.core.game.MatchResult
import com.clrg.core.game.MiniGame
import com.clrg.core.game.PlayerInput
import com.clrg.core.gesture.AvatarPose
import com.clrg.core.math.Rect
import com.clrg.core.math.clamp01
import com.clrg.core.math.lerp
import com.clrg.core.render.Colors
import com.clrg.core.render.Draw
import com.clrg.core.render.Expression
import com.clrg.core.render.Gfx
import com.clrg.core.render.Scenery
import com.clrg.core.render.Particles
import com.clrg.core.tracking.MovementMode
import kotlin.math.hypot
import kotlin.math.sin

/**
 * 9. Christmas Goalkeepers - each player guards their own goal. A kicker elf shoots the same sequence at both
 * goals; the target spot lights up on the goal as the kick starts; reach a hand there to save. Three 20-second
 * rounds, each a little faster. Shots stay inside the player's calibrated reach (no diving or deep crouching).
 */
class GoalkeeperGame(env: GameEnv) : MiniGame(env) {
    override val info: GameInfo get() = if (env.sporty) SPORTS_INFO else INFO

    class Shot(val time: Double, val tx: Float, val ty: Float, val flight: Float, val round: Int)
    class LiveShot(val shot: Shot, var resolved: Boolean = false, var saved: Boolean = false, var t: Float = 0f, var kicked: Boolean = false)

    companion object {
        const val ROUNDS = 3
        const val ROUND_SEC = 20.0
        const val BREAK_SEC = 3.0
        const val WINDUP = 0.7f
        val INFO = GameInfo(
            id = "goalie", title = "Christmas Goalkeepers", tagline = "Reach out and stop the shots!",
            howTo = listOf(
                "A kicker elf shoots at your goal. The target spot lights up on the goal first.",
                "Reach a hand to that spot before the ball arrives to make a SAVE.",
                "Both goals get exactly the same shots. Three short rounds, each a bit faster.",
                "Most saves wins. Chain saves together for a streak!",
            ),
            controls = listOf("Reach with either hand" to "Save", "Watch the glowing target" to "Know where it's going"),
            gentleNotes = "Shots are placed inside each player's calibrated reach; seated players get no low shots.",
            music = MusicTrack.GAME, scoreUnit = "saves", goal = "Most saves",
        )
        val SPORTS_INFO by lazy { GameInfo(
            id = "goalie_s", title = "Penalty Save", tagline = "Reach out and stop the penalties!",
            howTo = listOf(
                "A striker shoots at your goal. The target spot lights up on the goal first.",
                "Reach a hand to that spot before the ball arrives to make a SAVE.",
                "Both goals get exactly the same shots. Three short rounds, each a bit faster.",
                "Most saves wins. Chain saves together for a streak!",
            ),
            controls = INFO.controls, gentleNotes = INFO.gentleNotes,
            music = MusicTrack.GAME, scoreUnit = "saves", goal = "Most saves",
        ) }
    }

    val saves = IntArray(2)
    val goals = IntArray(2)
    val streak = IntArray(2)
    val bestStreak = IntArray(2)
    private val shots = ArrayList<Shot>()
    /** The shot sequence (same for both goals). */
    val shotList: List<Shot> get() = shots
    private val live = Array(2) { ArrayList<LiveShot>() }
    private val next = IntArray(2)
    private var screenW = 1920f
    private val saveFlash = FloatArray(2) { 9f }
    private val goalFlash = FloatArray(2) { 9f }
    val totalTime = ROUNDS * ROUND_SEC + (ROUNDS - 1) * BREAK_SEC

    private fun saveRadius(k: Int) = if (env.players[k].mode == MovementMode.STANDARD) 0.3f else 0.36f

    override fun start() {
        val rng = env.rng(9)
        for (round in 0 until ROUNDS) {
            val start = round * (ROUND_SEC + BREAK_SEC) + 1.0
            var t = start
            val flight = lerp(1.25f, 0.8f, round / 2f)
            val gap = lerp(2.3f, 1.6f, round / 2f)
            var lastX = 0f
            while (t < start + ROUND_SEC - 2.0) {
                var tx = rng.range(-0.95f, 0.95f)
                if (kotlin.math.abs(tx - lastX) < 0.4f) tx = if (lastX > 0) rng.range(-0.95f, -0.2f) else rng.range(0.2f, 0.95f)
                lastX = tx
                val ty = rng.range(-0.45f, 0.95f)
                shots.add(Shot(t, tx, ty, flight, round))
                t += gap * rng.range(0.85f, 1.15f)
            }
        }
    }

    fun round(): Int = (playTime / (ROUND_SEC + BREAK_SEC)).toInt().coerceAtMost(ROUNDS - 1)
    fun inBreak(): Boolean = (playTime % (ROUND_SEC + BREAK_SEC)) > ROUND_SEC && round() < ROUNDS - 1

    /** Seated players' shots are lifted above lap height, keeping the same sequence. */
    private fun targetY(k: Int, s: Shot) = if (env.players[k].mode == MovementMode.SEATED) lerp(-0.15f, 0.9f, (s.ty + 0.45f) / 1.4f) else s.ty

    override fun update(dt: Float, inputs: Array<PlayerInput>) {
        for (k in 0..1) {
            saveFlash[k] += dt; goalFlash[k] += dt
            while (next[k] < shots.size && shots[next[k]].time <= playTime) live[k].add(LiveShot(shots[next[k]++]))
            val inp = inputs[k]
            val it = live[k].iterator()
            while (it.hasNext()) {
                val ls = it.next()
                ls.t += dt
                if (!ls.kicked && ls.t >= WINDUP) { ls.kicked = true; env.sfx(Sfx.KICK, 0.75f, pan(k)) }
                val arrive = WINDUP + ls.shot.flight
                if (!ls.resolved && ls.t >= arrive - 0.06f) {
                    // a hand at the target spot as the ball arrives (small grace before) saves it; camera players'
                    // hands are seen a little late (camera, TV), so their window stays open that much longer
                    val tx = ls.shot.tx; val ty = targetY(k, ls.shot)
                    val r = saveRadius(k)
                    val saved = (0..1).any { h -> inp.handVisible[h] && hypot(inp.handX[h] - tx, inp.handY[h] - ty) < r }
                    if (saved || ls.t >= arrive + 0.06f + inputGrace(k)) {
                        ls.resolved = true
                        ls.saved = saved
                        val (px, py) = goalPoint(k, tx, ty)
                        if (saved) {
                            saves[k]++; streak[k]++; bestStreak[k] = maxOf(bestStreak[k], streak[k]); saveFlash[k] = 0f
                            env.sfx(Sfx.SAVE, 1f, pan(k))
                            if (streak[k] >= 3) env.sfx(if (env.sporty) Sfx.CROWD_CHEER else Sfx.CHEER, 0.5f, pan(k))
                            else if (env.sporty) env.sfx(Sfx.APPLAUSE, 0.35f, pan(k))
                            env.popups.add(if (streak[k] >= 3) "SAVE! x${streak[k]}" else "SAVE!", px, py - 70f, Colors.GOOD, 60f)
                            env.particles.burst(px, py, env.color(k), 20, kind = Particles.Kind.STAR, size = 12f)
                            env.particles.ring(px, py, Colors.GOOD, 120f)
                            env.particles.impact(px, py, Colors.WHITE, 0.6f)
                        } else {
                            goals[k]++; streak[k] = 0; goalFlash[k] = 0f
                            env.sfx(Sfx.GOAL_NET, 0.9f, pan(k))
                            if (env.sporty) env.sfx(Sfx.CROWD_AW, 0.3f, pan(k)) else env.sfx(Sfx.MISS, 0.5f, pan(k))
                            env.popups.add("GOAL", px, py - 70f, Colors.BAD, 50f)
                        }
                    }
                }
                if (ls.resolved && ls.t > arrive + 0.8f) it.remove()
            }
        }
    }

    private fun pan(k: Int) = if (k == 0) -0.6f else 0.6f

    // ---- CPU player: reacts to the glowing target after a human delay (sometimes too late or not at all),
    // moves the nearer hand there at a human speed and aims with a little error.
    private class BotView(val go: Boolean, val delay: Float, val ex: Float, val ey: Float)
    private val botViews = HashMap<LiveShot, BotView>()

    override fun autoplay(k: Int, inp: PlayerInput, skill: Float, rng: com.clrg.core.math.Rng) {
        val dt = 1f / 120f
        val speed = com.clrg.core.game.CpuTraits.handSpeed(skill)
        botViews.keys.removeAll { it.resolved }
        val ls = live[k].firstOrNull { !it.resolved }
        val v = ls?.let { s ->
            botViews.getOrPut(s) {
                val sd = lerp(0.2f, 0.06f, skill)
                BotView(rng.chance(lerp(0.88f, 0.98f, skill)), com.clrg.core.game.CpuTraits.reaction(skill) * rng.range(0.85f, 1.35f), rng.gaussian() * sd, rng.gaussian() * sd)
            }
        }
        val h = if (ls != null && ls.shot.tx < 0) 0 else 1
        for (i in 0..1) {
            val (tx, ty) = if (ls != null && v != null && v.go && i == h && ls.t >= v.delay) (ls.shot.tx + v.ex) to (targetY(k, ls.shot) + v.ey)
                else (if (i == 0) -0.3f else 0.3f) to -0.1f
            com.clrg.core.game.CpuTraits.moveHand(inp, i, tx, ty, speed, dt)
        }
    }

    private fun goalRect(k: Int): Rect {
        val cx = if (k == 0) screenW * 0.25f else screenW * 0.75f
        val w = minOf(screenW * 0.42f, 820f)
        return Rect(cx - w / 2f, 250f, cx + w / 2f, 790f)
    }

    /** Reach space -> point on the goal plane. */
    fun goalPoint(k: Int, rx: Float, ry: Float): Pair<Float, Float> {
        val g = goalRect(k)
        val x = g.cx + rx / 1.15f * g.width / 2f
        val y = g.bottom - (ry + 0.65f) / 1.85f * g.height
        return x to y
    }

    override val isOver: Boolean get() = playTime >= totalTime && live.all { l -> l.all { it.resolved } }

    override fun result() = MatchResult(info.id, saves.copyOf(), winnerOf(saves[0], saves[1]), info.scoreUnit,
        listOf("Goals let in: ${goals[0]} vs ${goals[1]}", "Best streak: ${bestStreak[0]} vs ${bestStreak[1]}"))

    override fun render(g: Gfx, inputs: Array<PlayerInput>, time: Double) {
        screenW = g.width
        for (k in 0..1) renderLane(g, k, inputs[k], time)
        Hud.divider(g)
        val label = if (inBreak()) "Round ${round() + 2} next!" else "Round ${round() + 1} of $ROUNDS"
        val left = (ROUND_SEC - (playTime % (ROUND_SEC + BREAK_SEC))).coerceAtLeast(0.0)
        Hud.topBar(g, env, saves, if (inBreak()) "Break" else Hud.formatTime(left), label, inputs)
        if (inBreak()) Hud.banner(g, "Round ${round() + 2} - faster shots!", 540f, Colors.GOLD, 70f)
    }

    private fun renderLane(g: Gfx, k: Int, inp: PlayerInput, time: Double) {
        val lane = Rect(if (k == 0) 0f else screenW / 2f, 0f, if (k == 0) screenW / 2f else screenW, 1080f)
        g.save(); g.clipRect(lane.left, lane.top, lane.right, lane.bottom)
        val gr = goalRect(k)
        val key = if (env.sporty) "goalie$k" else "goalieC$k"
        // the stadium: sky, a cheering crowd in the stand (cached frames), then the pitch and the goal net (cached)
        if (env.sporty) g.layer("$key.sky", lane.left, 0f, lane.right, 580f, res = 0.5f) { lg -> eveningSky(lg, lane) }
        else {
            Scenery.nightSky(g, key, Rect(lane.left, 0f, lane.right, 700f), time + k)
            Draw.aurora(g, Rect(lane.left, 0f, lane.right, 460f), time + k * 3, 0.8f)
        }
        Scenery.crowd(g, key, lane.left, 556f, lane.right, 700f, time + k * 0.37, rows = 3, shade = 0.42f, blur = 1.2f)
        if (env.sporty) {
            val rng = com.clrg.core.math.Rng((time * 4).toLong() + k * 77)
            g.save(); g.setBlend(com.clrg.core.render.Blend.ADD)
            repeat(3) { if (rng.chance(0.35f)) { val fx = lane.left + rng.nextFloat() * lane.width; val fy = 575f + rng.nextFloat() * 110f; g.glow(fx, fy, 30f, 0x99FFFFFF.toInt()) } }
            g.restore()
        }
        g.layer("$key.pitch", lane.left, 280f, lane.right, 1080f, grain = 0.15f) { lg -> pitch(lg, lane, gr) }
        if (!env.sporty) Draw.stringLights(g, lane.left, lane.right, 130f, time)
        if (goalFlash[k] < 0.4f) g.fillRect(gr.left, gr.top, gr.right, gr.bottom, Colors.withAlpha(Colors.BAD, 0.35f * (1f - goalFlash[k] / 0.4f)))
        // keeper: buddy in the goal, mittens follow the player's hands in reach space
        val hands = FloatArray(4)
        for (h in 0..1) {
            val rx = if (inp.handVisible[h]) inp.handX[h] else if (h == 0) -0.3f else 0.3f
            val ry = if (inp.handVisible[h]) inp.handY[h] else -0.6f
            val (px, py) = goalPoint(k, rx, ry)
            hands[h * 2] = px; hands[h * 2 + 1] = py
        }
        val (_, shoulderY) = goalPoint(k, 0f, 0f)
        Draw.person(g, env.sporty, gr.cx, shoulderY + 0.6f * 560f * 0.65f, 560f * 0.65f, env.color(k), inp.avatar,
            if (saveFlash[k] < 0.6f) Expression.EXCITED else if (goalFlash[k] < 0.6f) Expression.OOPS else Expression.FOCUSED,
            t = time, handTargets = hands, mittenScale = 2.0f)
        for (h in 0..1) g.strokeCircle(hands[h * 2], hands[h * 2 + 1], saveRadius(k) / 1.15f * gr.width / 2f, 3f, Colors.withAlpha(env.color(k), 0.45f))
        // posts and crossbar (candy cane at Christmas, plain white otherwise)
        for (px in floatArrayOf(gr.left, gr.right)) {
            g.fillRect(px - 12f, gr.top - 12f, px + 12f, gr.bottom, Colors.WHITE)
            g.fillRect(px + 4f, gr.top - 12f, px + 12f, gr.bottom, 0x22000000)
            if (!env.sporty) for (i in 0 until 10) g.fillRect(px - 12f, gr.top + i * 56f, px + 12f, gr.top + i * 56f + 24f, Colors.CANDY)
        }
        g.fillRect(gr.left - 12f, gr.top - 24f, gr.right + 12f, gr.top, Colors.WHITE)
        g.fillRect(gr.left - 12f, gr.top - 8f, gr.right + 12f, gr.top, 0x22000000)
        if (!env.sporty) for (i in 0 until 14) g.fillRect(gr.left + i * gr.width / 14f, gr.top - 24f, gr.left + i * gr.width / 14f + 24f, gr.top, Colors.CANDY)
        // kicker elf in the foreground
        val kickerX = lane.cx + lane.width * 0.28f
        val kickPose = AvatarPose().apply { valid = true; upper[0] = 2.4f; fore[0] = 2.6f; upper[1] = 0.6f; fore[1] = 0.4f }
        if (env.sporty) Draw.athlete(g, kickerX, 1070f, 300f, 0xFF424242.toInt(), kickPose, Expression.FOCUSED, t = time, look = 2)
        else Draw.elf(g, kickerX, 1070f, 300f, kickPose, time, 0f)
        // shots: target marker, then ball flying from the kicker to the goal plane
        for (ls in live[k]) {
            val tx = ls.shot.tx; val ty = targetY(k, ls.shot)
            val (px, py) = goalPoint(k, tx, ty)
            val arrive = WINDUP + ls.shot.flight
            if (!ls.resolved) {
                val f = clamp01(ls.t / arrive)
                val pulse = 0.5f + 0.5f * sin(time.toFloat() * 12f)
                g.glow(px, py, 90f, Colors.withAlpha(Colors.GOLD, 0.4f + 0.3f * pulse))
                g.strokeCircle(px, py, 30f + 70f * (1f - f), 6f, Colors.GOLD)
                g.line(px - 20f, py, px + 20f, py, 4f, Colors.GOLD); g.line(px, py - 20f, px, py + 20f, 4f, Colors.GOLD)
            }
            if (ls.t >= WINDUP && !(ls.resolved && ls.saved && ls.t > arrive + 0.15f)) {
                val u = clamp01((ls.t - WINDUP) / ls.shot.flight)
                val sx = lerp(kickerX - 40f, px, u)
                val sy = lerp(1000f, py, u) - 160f * 4f * u * (1f - u)
                val size = lerp(46f, 20f, u)
                if (ls.resolved && ls.saved) {
                    val bounce = (ls.t - arrive) * 900f
                    drawBall(g, px + bounce * (if (tx > 0) 1f else -1f) * 0.5f, py - bounce * 0.3f, 22f, time)
                } else drawBall(g, sx, sy, size, time)
            }
        }
        if (streak[k] >= 3) g.text("Streak ${streak[k]}!", lane.cx, 860f, 46f, Colors.GOLD, outline = Colors.BLACK, outlineWidth = 6f)
        Hud.laneLabel(g, Rect(lane.left, 140f, lane.right, 280f), env, k)
        g.restore()
    }

    /** Evening sky over the stadium: deep blue to a warm glow at the rim, the stand's roof and two light towers. */
    private fun eveningSky(g: Gfx, lane: Rect) {
        g.gradientRect(lane.left, 0f, lane.right, 580f, 0xFF071634.toInt(), 0xFF3A5C92.toInt())
        g.fillRectRadial(lane.left, 300f, lane.right, 580f, lane.cx, 600f, lane.width * 0.7f, 0x44FFB074, 0x00FFB074)
        for (i in 0 until 50) {
            val x = lane.left + Draw.hash01(i * 7 + 2) * lane.width; val y = Draw.hash01(i * 11 + 5) * 300f
            g.fillCircle(x, y, 0.8f + Draw.hash01(i) * 1.2f, 0x88FFFFFF.toInt())
        }
        // the upper tier and roof of the stand opposite
        g.gradientRect(lane.left, 470f, lane.right, 580f, 0xFF1B2436.toInt(), 0xFF101624.toInt())
        g.fillRect(lane.left, 466f, lane.right, 474f, 0xFF3A465C.toInt())
        for (i in 0 until 10) {
            val lx = lane.left + (i + 0.5f) * lane.width / 10f
            g.fillOval(lx - 10f, 474f, lx + 10f, 480f, 0xFFFFF3D6.toInt())
        }
        for (i in 0 until 60) {
            val x = lane.left + Draw.hash01(i * 13 + 1) * lane.width; val y = 490f + Draw.hash01(i * 17 + 3) * 80f
            g.fillCircle(x, y, 2f, if (i % 3 == 0) 0x66FFE082 else 0x33FFFFFF)
        }
    }

    /** The pitch: mown stripes, the six-yard box and penalty spot, light from the towers, the goal's net with depth. */
    private fun pitch(g: Gfx, lane: Rect, gr: Rect) {
        Draw.floodlight(g, lane.left + 70f, 700f, 330f, lane.cx - 60f, 1000f, 240f)
        Draw.floodlight(g, lane.right - 70f, 700f, 330f, lane.cx + 60f, 1000f, 240f)
        g.gradientRect(lane.left, 700f, lane.right, 1080f, 0xFF2E8B55.toInt(), 0xFF145030.toInt())
        for (i in 0 until 9) g.fillRect(lane.left, 700f + i * i * 6f + i * 24f, lane.right, 700f + i * i * 6f + i * 24f + (12f + i * 6f), 0x12FFFFFF)
        // grass texture: short blades in the foreground
        for (i in 0 until 500) {
            val x = lane.left + Draw.hash01(i * 7 + 1) * lane.width
            val y = 760f + Draw.hash01(i * 13 + 2).let { it * it } * 320f
            val h = 3f + (y - 760f) * 0.03f
            g.line(x, y, x + (Draw.hash01(i) - 0.5f) * 3f, y - h, 1.2f, if (i % 2 == 0) 0x1AFFFFFF else 0x1A002000)
        }
        Draw.lightPool(g, lane.cx, 900f, lane.width * 0.5f, 110f, 0x30FFF6D8)
        if (env.sporty) {
            g.fillRect(lane.left, 700f, lane.right, 708f, 0xCCFFFFFF.toInt())
            // six-yard box in perspective and the penalty spot
            val box = floatArrayOf(gr.left - 40f, 708f, gr.right + 40f, 708f, gr.right + 120f, 800f, gr.left - 120f, 800f)
            g.polyline(box, 4, 5f, 0xAAFFFFFF.toInt(), closed = false)
            g.fillOval(lane.cx - 14f, 905f, lane.cx + 14f, 915f, 0xCCFFFFFF.toInt())
        } else g.fillRoundRectGradient(lane.left, 694f, lane.right, 716f, 10f, Colors.SNOW, 0xFFB0C4DE.toInt())
        // the goal's net: a back panel, side netting in perspective, the mesh
        val depth = 70f
        g.fillRectRadial(gr.left + depth * 0.5f, gr.top + depth * 0.4f, gr.right - depth * 0.5f, gr.bottom, gr.cx, gr.cy, gr.width * 0.7f, 0x22FFFFFF, 0x50000814)
        val sideL = floatArrayOf(gr.left, gr.top, gr.left + depth * 0.5f, gr.top + depth * 0.4f, gr.left + depth * 0.5f, gr.bottom, gr.left, gr.bottom)
        val sideR = floatArrayOf(gr.right, gr.top, gr.right - depth * 0.5f, gr.top + depth * 0.4f, gr.right - depth * 0.5f, gr.bottom, gr.right, gr.bottom)
        g.fillPolygon(sideL, 4, 0x30000814); g.fillPolygon(sideR, 4, 0x30000814)
        var nx = gr.left + depth * 0.5f
        while (nx <= gr.right - depth * 0.5f) { g.line(nx, gr.top + depth * 0.4f, nx, gr.bottom, 1.8f, 0x40FFFFFF); nx += 30f }
        var ny = gr.top + depth * 0.4f
        while (ny <= gr.bottom) { g.line(gr.left + depth * 0.5f, ny, gr.right - depth * 0.5f, ny, 1.8f, 0x40FFFFFF); ny += 30f }
        for (j in 0..6) { val u = j / 6f; g.line(gr.left, gr.top + (gr.bottom - gr.top) * u, gr.left + depth * 0.5f, gr.top + depth * 0.4f + (gr.bottom - gr.top - depth * 0.4f) * u, 1.5f, 0x40FFFFFF); g.line(gr.right, gr.top + (gr.bottom - gr.top) * u, gr.right - depth * 0.5f, gr.top + depth * 0.4f + (gr.bottom - gr.top - depth * 0.4f) * u, 1.5f, 0x40FFFFFF) }
        // the posts' shadows on the grass
        g.fillRect(gr.left - 12f, gr.bottom, gr.right + 12f, gr.bottom + 10f, 0x33000000)
    }

    private fun drawBall(g: Gfx, x: Float, y: Float, r: Float, time: Double) {
        g.fillCircle(x, y, r, Colors.WHITE)
        g.strokeCircle(x, y, r, r * 0.12f, 0xFF263238.toInt())
        if (env.sporty) {
            // classic football patches
            g.fillCircle(x, y, r * 0.32f, 0xFF212121.toInt())
            for (i in 0 until 5) {
                val a = time.toFloat() * 4f + i * 1.2566f
                g.fillCircle(x + kotlin.math.cos(a) * r * 0.72f, y + kotlin.math.sin(a) * r * 0.72f, r * 0.2f, 0xFF212121.toInt())
            }
        } else Draw.star(g, x, y, r * 0.55f, Colors.CANDY, time.toFloat() * 4f)
    }

    override fun demo(g: Gfx, r: Rect, t: Double) {
        val phase = ((t % 2.0) / 2.0).toFloat()
        val target = floatArrayOf(r.cx + r.width * 0.28f, r.top + r.height * 0.3f)
        val k = clamp01((phase - 0.2f) / 0.4f)
        val hands = floatArrayOf(r.cx - r.width * 0.12f, r.cy + r.height * 0.2f, lerp(r.cx + r.width * 0.12f, target[0], k), lerp(r.cy + r.height * 0.2f, target[1], k))
        Draw.person(g, env.sporty, r.cx, r.bottom + r.height * 0.1f, r.height * 1.0f, env.color(1), null, Expression.FOCUSED, t = t, handTargets = hands, mittenScale = 2f)
        g.strokeCircle(target[0], target[1], 30f, 5f, Colors.GOLD)
        if (phase > 0.4f) drawBall(g, lerp(r.left + 40f, target[0], clamp01((phase - 0.4f) / 0.3f)), lerp(r.bottom - 30f, target[1], clamp01((phase - 0.4f) / 0.3f)), 18f, t)
    }
}
