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
import com.clrg.core.gesture.GestureEvent
import com.clrg.core.math.Rect
import com.clrg.core.math.clamp
import com.clrg.core.math.lerp
import com.clrg.core.render.Colors
import com.clrg.core.render.Draw
import com.clrg.core.render.Expression
import com.clrg.core.render.Gfx
import com.clrg.core.render.Prop
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.sin

/**
 * 5. Festive Bowling - two lanes side by side, each player bowls on their own schedule (no waiting for the other's
 * animation). Underarm swing to release; the sideways direction of the swing angles the ball a little (forgiving),
 * leaning beforehand moves your starting spot. 5 frames, simplified scoring:
 * every pin = 1 point, STRIKE = 15 for the frame, SPARE = 13 for the frame (no carry-over bonuses).
 */
class BowlingGame(env: GameEnv) : MiniGame(env) {
    override val info: GameInfo get() = if (env.sporty) SPORTS_INFO else INFO

    companion object {
        const val FRAMES = 5
        const val LANE_HALF = 0.53f
        const val BALL_R = 0.109f
        const val PIN_R = 0.061f
        const val HEAD_PIN_Y = 10.5f
        const val PIT_Y = 11.6f
        const val MAX_AIM_RAD = 0.075f // ~4.3 degrees: forgiving limits
        const val STRIKE_SCORE = 15
        const val SPARE_SCORE = 13

        val INFO = GameInfo(
            id = "bowling", title = "Festive Bowling", tagline = "Swing low, roll for a strike!",
            howTo = listOf(
                "Lean left or right to choose where you stand.",
                "Swing your arm down and back, then forward and up (underarm) to roll the ball.",
                "Swing slightly sideways to angle the ball a little.",
                "5 frames. Each pin = 1 point. STRIKE = 15, SPARE = 13. You bowl at your own pace.",
            ),
            controls = listOf("Lean" to "Pick your spot", "Underarm swing" to "Roll the ball"),
            gentleNotes = "Reduced movement: a small low-to-chest swing rolls the ball. Seated: swing from your lap up to your chest.",
            music = MusicTrack.GAME, scoreUnit = "points", goal = "Highest total after 5 frames",
        )

        val SPORTS_INFO by lazy { GameInfo(
            id = "bowling_s", title = "Bowling", tagline = INFO.tagline, howTo = INFO.howTo, controls = INFO.controls,
            gentleNotes = INFO.gentleNotes, music = INFO.music, scoreUnit = INFO.scoreUnit, goal = INFO.goal,
        ) }

        fun pinSpots(): List<Pair<Float, Float>> {
            val out = ArrayList<Pair<Float, Float>>()
            for (row in 0..3) for (i in 0..row) out.add((i - row / 2f) * 0.305f to HEAD_PIN_Y + row * 0.264f)
            return out
        }
    }

    enum class LaneState { AIM, ROLL, SETTLE, RESULT, DONE }

    class Pin(val homeX: Float, val homeY: Float) {
        var x = homeX; var y = homeY; var vx = 0f; var vy = 0f
        var down = false
        var removed = false
        var angle = 0f
        fun reset() { x = homeX; y = homeY; vx = 0f; vy = 0f; down = false; removed = false; angle = 0f }
    }

    inner class Lane(val k: Int) {
        val pins = pinSpots().map { Pin(it.first, it.second) }
        var state = LaneState.AIM
        var stateTime = 0f
        var bx = 0f; var by = 0f; var bvx = 0f; var bvy = 0f
        var rolling = false
        var gutter = false
        var startX = 0f
        var frame = 0
        var ballInFrame = 0
        var firstBallPins = 0
        val frameScore = IntArray(FRAMES) { -1 }
        val marks = Array(FRAMES) { "" }
        var message = ""
        var standingAtStart = 10
        var strikes = 0
        var spares = 0
        var lastAim = 0f
        var spin = 0f

        val total: Int get() = frameScore.filter { it >= 0 }.sum()

        fun release(aim: Float, power: Float) {
            val angle = clamp(aim, -1f, 1f) * MAX_AIM_RAD
            val speed = lerp(6.8f, 9.5f, clamp(power, 0f, 1f))
            bx = startX; by = 0f
            bvx = speed * sin(angle); bvy = speed * cos(angle)
            rolling = true; gutter = false
            lastAim = aim
            standingAtStart = pins.count { !it.removed && !it.down }
            state = LaneState.ROLL; stateTime = 0f
            env.sfx(Sfx.ROLL, 0.7f, pan(k))
        }

        fun step(dt: Float) {
            if (rolling) {
                bx += bvx * dt; by += bvy * dt
                bvy *= (1f - 0.03f * dt)
                spin += bvy * dt * 2f
                if (!gutter && abs(bx) > LANE_HALF - BALL_R * 0.6f) {
                    gutter = true
                    bx = if (bx > 0) LANE_HALF + 0.05f else -LANE_HALF - 0.05f
                    bvx = 0f
                    message = "Gutter!"
                    env.sfx(Sfx.MISS, 0.4f, pan(k))
                    env.sfx(Sfx.CROWD_AW, 0.3f, pan(k))
                }
                if (!gutter) for (p in pins) if (!p.removed) collideBall(p)
                if (by > PIT_Y + 0.5f) rolling = false
            }
            for (i in pins.indices) {
                val p = pins[i]
                if (p.removed) continue
                if (p.vx != 0f || p.vy != 0f) {
                    p.x += p.vx * dt; p.y += p.vy * dt
                    val f = exp(-2.5f * dt)
                    p.vx *= f; p.vy *= f
                    if (hypot(p.vx, p.vy) < 0.02f) { p.vx = 0f; p.vy = 0f }
                    p.angle = atan2(p.vy, p.vx)
                }
                for (j in i + 1 until pins.size) if (!pins[j].removed) collidePins(p, pins[j])
                if (!p.down && hypot(p.x - p.homeX, p.y - p.homeY) > 0.05f) p.down = true
                if (abs(p.x) > LANE_HALF + 0.1f || p.y > PIT_Y + 0.3f) { p.down = true; p.vx = 0f; p.vy = 0f }
            }
        }

        private fun collideBall(p: Pin) {
            val dx = p.x - bx; val dy = p.y - by
            val d = hypot(dx, dy)
            val minD = BALL_R + PIN_R
            if (d >= minD || d < 1e-5f) return
            val nx = dx / d; val ny = dy / d
            val rel = (bvx - p.vx) * nx + (bvy - p.vy) * ny
            if (rel > 0f) {
                val mb = 6.5f; val mp = 1.5f; val e = 0.65f
                val j = (1 + e) * rel / (1 / mb + 1 / mp)
                bvx -= j / mb * nx; bvy -= j / mb * ny
                p.vx += j / mp * nx; p.vy += j / mp * ny
                if (!p.down) { p.down = true; env.sfx(Sfx.PINS, 0.7f, pan(k)) }
            }
            val push = minD - d
            p.x += nx * push; p.y += ny * push
        }

        private fun collidePins(a: Pin, b: Pin) {
            val dx = b.x - a.x; val dy = b.y - a.y
            val d = hypot(dx, dy)
            val minD = PIN_R * 2f
            if (d >= minD || d < 1e-5f) return
            val nx = dx / d; val ny = dy / d
            val rel = (a.vx - b.vx) * nx + (a.vy - b.vy) * ny
            if (rel > 0f) {
                val j = (1 + 0.5f) * rel / 2f
                a.vx -= j * nx; a.vy -= j * ny
                b.vx += j * nx; b.vy += j * ny
                a.down = a.down || rel > 0.25f
                b.down = true
            }
            val push = (minD - d) / 2f
            a.x -= nx * push; a.y -= ny * push; b.x += nx * push; b.y += ny * push
        }

        fun pinsMoving() = pins.any { !it.removed && (it.vx != 0f || it.vy != 0f) }

        fun update(dt: Float, inp: PlayerInput) {
            stateTime += dt
            when (state) {
                LaneState.AIM -> {
                    val target = clamp(inp.lean, -1f, 1f) * 0.3f
                    startX += clamp(target - startX, -0.8f * dt, 0.8f * dt)
                    val b = inp.take<GestureEvent.Bowl>()
                    if (b != null && stateTime > 0.4f) release(b.aimX, b.power)
                }
                LaneState.ROLL -> {
                    for (i in 0 until 2) step(dt / 2f)
                    if ((!rolling && !pinsMoving()) || stateTime > 5f || (by > PIT_Y && stateTime > 0.4f && !pinsMoving())) { state = LaneState.SETTLE; stateTime = 0f }
                }
                LaneState.SETTLE -> {
                    step(dt)
                    if (stateTime > 0.9f) score()
                }
                LaneState.RESULT -> if (stateTime > 1.6f) advance()
                LaneState.DONE -> Unit
            }
        }

        private fun score() {
            val knocked = standingAtStart - pins.count { !it.removed && !it.down }
            state = LaneState.RESULT; stateTime = 0f
            if (ballInFrame == 0) {
                firstBallPins = knocked
                if (knocked == 10) {
                    frameScore[frame] = STRIKE_SCORE; marks[frame] = "X"; strikes++
                    message = "STRIKE!"
                    env.sfx(Sfx.STRIKE, 0.9f, pan(k))
                    env.sfx(if (env.sporty) Sfx.CROWD_CHEER else Sfx.CHEER, 0.9f, pan(k))
                    if (!env.sporty) env.sfx(Sfx.JINGLE, 0.6f, pan(k))
                    env.particles.confetti(laneRect(k).left, laneRect(k).right, 200f, 60)
                    env.particles.impact(laneRect(k).cx, 330f, Colors.GOLD, 1f)
                    env.popups.add("STRIKE! +15", laneRect(k).cx, 420f, Colors.GOLD, 80f, 1.4f)
                } else {
                    message = if (gutter && knocked == 0) "Gutter ball" else "$knocked pins"
                    env.popups.add(message, laneRect(k).cx, 420f, env.color(k), 60f)
                }
            } else {
                val total = firstBallPins + knocked
                if (total == 10) {
                    frameScore[frame] = SPARE_SCORE; marks[frame] = "${firstBallPins} /"; spares++
                    message = "SPARE!"
                    env.sfx(Sfx.APPLAUSE, 0.7f, pan(k))
                    env.popups.add("SPARE! +13", laneRect(k).cx, 420f, Colors.GOLD, 70f, 1.3f)
                } else {
                    frameScore[frame] = total; marks[frame] = "$firstBallPins ${knocked}"
                    message = "$total for the frame"
                    env.popups.add("+$total", laneRect(k).cx, 420f, env.color(k), 60f)
                }
            }
        }

        private fun advance() {
            val frameOver = frameScore[frame] >= 0
            if (frameOver) {
                frame++
                ballInFrame = 0
                pins.forEach { it.reset() }
                if (frame >= FRAMES) { state = LaneState.DONE; message = "Finished: $total"; return }
            } else {
                ballInFrame = 1
                pins.forEach { if (it.down) it.removed = true }
            }
            startX = 0f
            state = LaneState.AIM; stateTime = 0f
            message = ""
        }
    }

    val lanes = arrayOf(Lane(0), Lane(1))
    private var screenW = 1920f
    private var doneTime = -1.0

    private fun pan(k: Int) = if (k == 0) -0.6f else 0.6f
    private fun laneRect(k: Int) = Rect(if (k == 0) 0f else screenW / 2f, 140f, if (k == 0) screenW / 2f else screenW, 1080f)

    override fun update(dt: Float, inputs: Array<PlayerInput>) {
        for (k in 0..1) lanes[k].update(dt, inputs[k])
        if (doneTime < 0 && lanes.all { it.state == LaneState.DONE }) doneTime = playTime
    }

    override val isOver: Boolean get() = doneTime >= 0 && playTime - doneTime > 1.2

    override fun result() = MatchResult(info.id, intArrayOf(lanes[0].total, lanes[1].total), winnerOf(lanes[0].total, lanes[1].total), info.scoreUnit,
        listOf("Strikes: ${lanes[0].strikes} vs ${lanes[1].strikes}", "Spares: ${lanes[0].spares} vs ${lanes[1].spares}"))

    // ---------------------------------------------------------------- rendering
    override fun render(g: Gfx, inputs: Array<PlayerInput>, time: Double) {
        screenW = g.width
        if (env.sporty) {
            // bowling alley: dark hall with neon strips
            g.gradientRect(0f, 0f, g.width, g.height, 0xFF141B33.toInt(), 0xFF070A14.toInt())
            for ((i, c) in listOf(0xFF00E5FF.toInt(), 0xFFFF4081.toInt(), 0xFFFFEA00.toInt()).withIndex()) {
                val y = 128f + i * 9f
                g.line(0f, y, g.width, y, 4f, Colors.withAlpha(c, 0.7f + 0.3f * kotlin.math.sin(time.toFloat() * 2f + i)))
            }
        } else {
            g.gradientRect(0f, 0f, g.width, g.height, 0xFF2E1636.toInt(), 0xFF0E0818.toInt())
            Draw.stringLights(g, 0f, g.width, 130f, time, sag = 30f)
        }
        for (k in 0..1) renderLane(g, lanes[k], inputs[k], time)
        Hud.divider(g)
        Hud.topBar(g, env, intArrayOf(lanes[0].total, lanes[1].total), "Frame ${minOf(FRAMES, maxOf(lanes[0].frame, lanes[1].frame) + 1)} of $FRAMES", "Strike = 15, Spare = 13", inputs)
    }

    private fun renderLane(g: Gfx, lane: Lane, inp: PlayerInput, time: Double) {
        val r = laneRect(lane.k)
        g.save(); g.clipRect(r.left, r.top, r.right, r.bottom)
        val cx = r.cx
        val fx = r.width * 0.42f * 6f / LANE_HALF
        val k = 6530f
        val hz = -28f
        fun z(y: Float) = y + 6f
        fun sy(y: Float) = hz + k / z(y)
        fun sx(x: Float, y: Float) = cx + x * fx / z(y)
        fun scale(y: Float) = fx / z(y)
        // lane and gutters
        val near = 0f; val far = PIT_Y + 0.6f
        // the neighbouring lanes either side, darker, with their pins standing
        for (side in intArrayOf(-1, 1)) {
            val off = side * (LANE_HALF * 2f + 0.55f)
            val np = floatArrayOf(sx(off - LANE_HALF, near), sy(near), sx(off + LANE_HALF, near), sy(near), sx(off + LANE_HALF, far), sy(far), sx(off - LANE_HALF, far), sy(far))
            g.fillPolygonGradient(np, 4, sy(far), sy(near), 0xFF5E4A35.toInt(), 0xFF8C7253.toInt())
            val cap = floatArrayOf(sx(off - side * (LANE_HALF + 0.12f), near), sy(near), sx(side * (LANE_HALF + 0.12f), near), sy(near), sx(side * (LANE_HALF + 0.12f), far), sy(far), sx(off - side * (LANE_HALF + 0.12f), far), sy(far))
            g.fillPolygonGradient(cap, 4, sy(far), sy(near), 0xFF242C38.toInt(), 0xFF3A4656.toInt())
            for (row in 0 until 4) for (j in 0..row) {
                val py = HEAD_PIN_Y + row * 0.26f
                val px = off + (j - row / 2f) * 0.3f
                drawPin(g, sx(px, py), sy(py), scale(py) * 0.95f, false, 0f)
            }
        }
        val gut = floatArrayOf(sx(-LANE_HALF - 0.12f, near), sy(near), sx(LANE_HALF + 0.12f, near), sy(near), sx(LANE_HALF + 0.12f, far), sy(far), sx(-LANE_HALF - 0.12f, far), sy(far))
        g.fillPolygonGradient(gut, 4, sy(far), sy(near), 0xFF1E272C.toInt(), 0xFF4A5A63.toInt())
        val lanePts = floatArrayOf(sx(-LANE_HALF, near), sy(near), sx(LANE_HALF, near), sy(near), sx(LANE_HALF, far), sy(far), sx(-LANE_HALF, far), sy(far))
        g.fillPolygonGradient(lanePts, 4, sy(far), sy(near), 0xFFB98E5C.toInt(), 0xFFE8CB9C.toInt())
        // alternating board tones and the glossy oil reflection down the middle
        for (i in 0 until 8) {
            if (i % 2 == 1) continue
            val x0 = -LANE_HALF + i * LANE_HALF * 2 / 8; val x1 = x0 + LANE_HALF * 2 / 8
            pinPts[0] = sx(x0, near); pinPts[1] = sy(near); pinPts[2] = sx(x1, near); pinPts[3] = sy(near); pinPts[4] = sx(x1, far); pinPts[5] = sy(far); pinPts[6] = sx(x0, far); pinPts[7] = sy(far)
            g.fillPolygon(pinPts, 4, 0x10000000)
        }
        for (i in 1 until 8) {
            val x = -LANE_HALF + i * LANE_HALF * 2 / 8
            g.line(sx(x, near), sy(near), sx(x, far), sy(far), 1.5f, 0x22000000)
        }
        pinPts[0] = sx(-0.12f, near); pinPts[1] = sy(near); pinPts[2] = sx(0.12f, near); pinPts[3] = sy(near); pinPts[4] = sx(0.05f, far); pinPts[5] = sy(far); pinPts[6] = sx(-0.05f, far); pinPts[7] = sy(far)
        g.fillPolygonGradient(pinPts, 4, sy(far), sy(near), 0x40FFFFFF, 0x08FFFFFF)
        // the pin deck is lit
        Draw.lightPool(g, cx, sy(HEAD_PIN_Y + 0.4f), scale(HEAD_PIN_Y) * 0.9f, scale(HEAD_PIN_Y) * 0.2f, 0x55FFF3D6)
        // aiming arrows
        for (i in -2..2) Draw.tri(g, sx(i * 0.12f - 0.03f, 4.5f), sy(4.5f), sx(i * 0.12f + 0.03f, 4.5f), sy(4.5f), sx(i * 0.12f, 4.2f), sy(4.2f), 0xAA8D3B3B.toInt())
        g.fillRect(sx(-LANE_HALF, 0.02f), sy(0.02f) - 3f, sx(LANE_HALF, 0.02f), sy(0.02f) + 3f, if (env.sporty) 0xFF263238.toInt() else Colors.CANDY)
        // back wall of the pin deck and the light falling on the pins
        g.gradientRect(r.left, sy(far) - 160f, r.right, sy(far), 0xFF2A1830.toInt(), 0xFF120A16.toInt())
        g.glow(cx, sy(far) - 40f, scale(far) * 1.6f, 0x40FFE7B0)
        // pins (far to near) then ball
        val items = ArrayList<Pair<Float, () -> Unit>>()
        for (p in lane.pins) {
            if (p.removed) continue
            items.add(p.y to { drawPin(g, sx(p.x, p.y), sy(p.y), scale(p.y), p.down, p.angle) })
        }
        if (lane.state == LaneState.ROLL || lane.state == LaneState.SETTLE || lane.state == LaneState.AIM) {
            val by = if (lane.state == LaneState.AIM) 0.35f else lane.by
            val bx = if (lane.state == LaneState.AIM) lane.startX else lane.bx
            if (by < far) items.add(by to { drawBall(g, sx(bx, by), sy(by), BALL_R * scale(by), env.color(lane.k), lane.spin) })
        }
        items.sortByDescending { it.first }
        items.forEach { it.second() }
        if (lane.state == LaneState.AIM) {
            // aim guide: forgiving cone
            val y0 = 0.6f; val y1 = 6f
            for (s in intArrayOf(-1, 1)) g.line(sx(lane.startX, y0), sy(y0), sx(lane.startX + s * MAX_AIM_RAD * (y1 - y0), y1), sy(y1), 4f, Colors.withAlpha(env.color(lane.k), 0.6f))
            g.text("Swing underarm to bowl!", cx, 360f, 44f, Colors.WHITE, outline = Colors.BLACK, outlineWidth = 6f)
            g.text("Lean to move", cx, 410f, 32f, Colors.TEXT_DIM)
        }
        // bowler (mirrors the player's arms) in the corner
        val pose = if (inp.avatar.valid) inp.avatar else AvatarPose().apply { valid = true; upper[1] = -1.4f; fore[1] = -1.5f; upper[0] = -1.9f; fore[0] = -1.8f }
        Draw.person(g, env.sporty, r.left + 120f + (lane.startX / 0.3f) * 40f, r.bottom - 30f, 260f, env.color(lane.k), pose,
            if (lane.message.startsWith("STRIKE") || lane.message.startsWith("SPARE")) Expression.EXCITED else Expression.FOCUSED,
            if (lane.state == LaneState.AIM) Prop.BOWLING_BALL else Prop.NONE, inp.dominant, time, look = lane.k * 3)
        // scorecard
        drawCard(g, lane, Rect(r.left + 30f, 160f, r.right - 30f, 250f))
        if (lane.state == LaneState.DONE) g.text(if (lanes.all { it.state == LaneState.DONE }) "Total ${lane.total}" else "Total ${lane.total} - waiting...", cx, 520f, 56f, env.color(lane.k), outline = Colors.BLACK, outlineWidth = 6f)
        g.restore()
    }

    private val pinPts = FloatArray(8)

    private fun drawCard(g: Gfx, lane: Lane, r: Rect) {
        val w = r.width / (FRAMES + 1)
        for (f in 0 until FRAMES) {
            val x0 = r.left + f * w
            val current = f == lane.frame && lane.state != LaneState.DONE
            g.fillRoundRect(x0 + 4f, r.top, x0 + w - 4f, r.bottom, 10f, if (current) Colors.withAlpha(env.color(lane.k), 0.8f) else 0xCC0E2240.toInt())
            g.text("${f + 1}", x0 + 22f, r.top + 18f, 20f, Colors.TEXT_DIM)
            g.text(lane.marks[f], x0 + w / 2f, r.top + 30f, 28f, Colors.WHITE)
            if (lane.frameScore[f] >= 0) g.text(lane.frameScore[f].toString(), x0 + w / 2f, r.bottom - 22f, 30f, Colors.GOLD)
        }
        val x0 = r.left + FRAMES * w
        g.fillRoundRect(x0 + 4f, r.top, r.right, r.bottom, 10f, env.color(lane.k))
        g.text(lane.total.toString(), (x0 + r.right) / 2f, r.cy, 44f, Colors.BLACK)
    }

    private fun drawPin(g: Gfx, x: Float, y: Float, s: Float, down: Boolean, angle: Float) {
        val h = 0.38f * s
        val w = 0.12f * s
        if (down) {
            g.save(); g.translate(x, y - w * 0.5f); g.rotate(if (angle == 0f) 1.3f else angle)
            g.fillRoundRect(-h * 0.5f, -w * 0.5f, h * 0.5f, w * 0.5f, w * 0.5f, 0xFFECEFF1.toInt())
            g.fillRect(h * 0.1f, -w * 0.5f, h * 0.18f, w * 0.5f, Colors.CANDY)
            g.restore()
            return
        }
        g.fillOval(x - w * 0.7f, y - w * 0.25f, x + w * 0.7f, y + w * 0.25f, Colors.SHADOW)
        g.save(); g.translate(x, y - h * 0.27f); g.scale(1f, h * 0.55f / (w * 1.24f))
        g.fillCircleRadial(0f, 0f, w * 0.62f, -w * 0.25f, -w * 0.3f, w * 0.9f, Colors.WHITE, 0xFFB8C4D0.toInt())
        g.restore()
        g.save(); g.translate(x, y - h * 0.7f); g.scale(1f, h * 0.5f / (w * 0.72f))
        g.fillCircleRadial(0f, 0f, w * 0.36f, -w * 0.12f, -w * 0.15f, w * 0.5f, Colors.WHITE, 0xFFC5CED8.toInt())
        g.restore()
        g.fillCircleRadial(x, y - h * 0.95f, w * 0.38f, x - w * 0.12f, y - h * 0.98f, w * 0.5f, Colors.WHITE, 0xFFC5CED8.toInt())
        g.fillRect(x - w * 0.36f, y - h * 0.72f, x + w * 0.36f, y - h * 0.66f, Colors.CANDY)
        g.fillRect(x - w * 0.34f, y - h * 0.62f, x + w * 0.34f, y - h * 0.57f, Colors.CANDY)
        g.fillCircle(x - w * 0.25f, y - h * 0.35f, w * 0.1f, 0x99FFFFFF.toInt())
    }

    private fun drawBall(g: Gfx, x: Float, y: Float, r: Float, color: Int, spin: Float) {
        g.fillOval(x - r, y - r * 0.25f, x + r, y + r * 0.25f, Colors.SHADOW)
        g.fillCircleRadial(x, y - r, r, x - r * 0.35f, y - r * 1.4f, r * 1.7f, Colors.lighter(color, 0.4f), Colors.darker(color, 0.45f))
        g.fillCircle(x - r * 0.35f, y - r * 1.38f, r * 0.24f, Colors.withAlpha(Colors.WHITE, 0.6f))
        // holly pattern rotating as it rolls
        for (i in 0..2) {
            val a = spin + i * 2.1f
            val px = x + cos(a) * r * 0.5f
            val py = y - r + sin(a) * r * 0.25f
            if (sin(a) > -0.2f) g.fillCircle(px, py, r * 0.12f, Colors.darker(color, 0.5f))
        }
    }

    override fun demo(g: Gfx, r: Rect, t: Double) {
        val phase = ((t % 2.2) / 2.2).toFloat()
        val p = AvatarPose()
        p.upper[0] = -1.9f; p.fore[0] = -1.8f
        val a = if (phase < 0.45f) -1.57f - 0.6f * (phase / 0.45f) else -2.17f + 2.0f * ((phase - 0.45f) / 0.25f).coerceIn(0f, 1f)
        p.upper[1] = a; p.fore[1] = a + 0.1f
        p.valid = true
        Draw.person(g, env.sporty, r.cx - r.width * 0.2f, r.bottom - 10f, r.height * 0.8f, env.color(1), p, Expression.FOCUSED, if (phase < 0.6f) Prop.BOWLING_BALL else Prop.NONE, 1, t)
        if (phase >= 0.6f) {
            val k = (phase - 0.6f) / 0.4f
            drawBall(g, r.cx - r.width * 0.1f + r.width * 0.4f * k, r.bottom - 30f, 22f, env.color(1), k * 10f)
        }
        for (i in 0..2) drawPin(g, r.right - 60f + i * 18f - 18f, r.bottom - 30f - (i % 2) * 6f, 140f, false, 0f)
    }

    /** True when the lane is waiting for a bowl. */
    fun ready(k: Int) = lanes[k].state == LaneState.AIM && lanes[k].stateTime > 0.4f

    private val botBowlAt = FloatArray(2) { Float.NaN }

    /**
     * CPU player: takes a human amount of time to line up, aims at the pocket (first ball) or at the pins still
     * standing (second ball), with an aiming error that shrinks with skill.
     */
    override fun autoplay(k: Int, inp: PlayerInput, skill: Float, rng: com.clrg.core.math.Rng) {
        val lane = lanes[k]
        inp.lean = 0f
        if (lane.state != LaneState.AIM) { botBowlAt[k] = Float.NaN; return }
        if (botBowlAt[k].isNaN()) botBowlAt[k] = 0.5f + lerp(2.4f, 1.0f, skill) * rng.range(0.8f, 1.3f)
        if (lane.stateTime < botBowlAt[k] || !ready(k)) return
        val standing = lane.pins.filter { !it.removed && !it.down }
        val aimX = if (standing.size >= 10 || standing.isEmpty()) 0.05f else standing.map { it.x }.average().toFloat()
        val finalX = aimX + rng.gaussian() * lerp(0.2f, 0.06f, skill)
        val aim = clamp(kotlin.math.atan((finalX - lane.startX) / HEAD_PIN_Y) / MAX_AIM_RAD, -1f, 1f)
        inp.events.add(GestureEvent.Bowl(playTime, 1, aim, clamp(lerp(0.45f, 0.85f, skill) + (rng.nextFloat() - 0.5f) * 0.2f, 0f, 1f)))
        botBowlAt[k] = Float.MAX_VALUE
    }
}
