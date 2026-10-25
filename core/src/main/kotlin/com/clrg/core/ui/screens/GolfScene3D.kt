package com.clrg.core.ui.screens

import com.clrg.core.golf.Blob
import com.clrg.core.golf.Club
import com.clrg.core.golf.GolfPhysics
import com.clrg.core.golf.Hole
import com.clrg.core.golf.Pt
import com.clrg.core.math.Rect
import com.clrg.core.math.Rng
import com.clrg.core.render.Cam3
import com.clrg.core.render.Colors
import com.clrg.core.render.Gfx
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Christmas Golf in 3D: the hole seen from behind the ball, a camera that follows the shot down the fairway, a
 * green you look along when putting, and a snowman golfer copying the player's swing. Drawn with the ordinary 2D
 * drawing calls (polygons projected through [Cam3]), so it works on every phone the rest of the game runs on.
 */
class GolfScene3D {
    val cam = Cam3()
    // current and wanted camera (eye, look-at), eased towards each other
    private val eye = floatArrayOf(0f, -8f, 3f)
    private val look = floatArrayOf(0f, 40f, 0f)
    private val wantEye = floatArrayOf(0f, -8f, 3f)
    private val wantLook = floatArrayOf(0f, 40f, 0f)
    private var snapNext = true
    var vfov = 52f
    /** Summer course (sports mode): green land and hills, no snow, a golfer in a polo shirt instead of a snowman. */
    var summer = false
    private var wantVfov = 52f

    /** What to show besides the course. */
    class Extras {
        var ballX = Float.NaN; var ballY = 0f; var ballH = 0f
        var ballColor = Colors.WHITE
        /** Aim guide: from the ball along [aim] for [aimDist] m, landing ring [ringR] m; [apex] > 0 draws the flight arc. */
        var showAim = false
        var aim = 0f; var aimDist = 0f; var ringR = 1f; var apex = 0f; var aimStrong = false
        var aimColor = Colors.GOLD
        var putting = false
        /** The golfer next to the ball: swing angle (radians, 0 = address, + = backswing), club, handedness. */
        var golfer = false
        /** The ball the golfer stands at (the start of the shot). */
        var golferX = 0f; var golferY = 0f
        var swingAngle = 0f
        var club = Club.DRIVER
        var leftHanded = false
        var golferColor = Colors.CANDY
        /** Recent ball positions in flight (x, y, h triples), drawn as a fading trail. */
        val trail = FloatArray(3 * 30)
        var trailN = 0
    }

    fun snap() { snapNext = true }

    /** Behind the ball, looking down the aim line (full shots). */
    fun aimView(ball: Pt, aim: Float, leftHanded: Boolean) {
        val d = GolfPhysics.dir(aim)
        val rt = Pt(d.y, -d.x)
        val side = if (leftHanded) -0.7f else 0.7f
        set(ball.x - d.x * 6.2f + rt.x * side, ball.y - d.y * 6.2f + rt.y * side, 2.3f, ball.x + d.x * 42f, ball.y + d.y * 42f, 0f, 50f)
    }

    /** Low behind the ball (on the side away from the golfer), looking along the putt to the hole. */
    fun puttView(ball: Pt, aim: Float, pin: Pt, leftHanded: Boolean = false) {
        val d = GolfPhysics.dir(aim)
        val rt = Pt(d.y, -d.x)
        val side = if (leftHanded) -1f else 1f
        val dist = ball.dist(pin).coerceIn(3f, 30f)
        val back = 4.2f + dist * 0.15f
        set(ball.x - d.x * back + rt.x * 1.1f * side, ball.y - d.y * back + rt.y * 1.1f * side, 1.7f + dist * 0.06f,
            ball.x + d.x * dist * 0.8f, ball.y + d.y * dist * 0.8f, 0f, 46f)
    }

    /**
     * Following a full shot: from the view behind the golfer it swings round to a camera riding just behind and
     * above the ball, all the way down the fairway, and settles behind the ball as it lands and rolls.
     */
    fun flightView(start: Pt, aim: Float, leftHanded: Boolean, bx: Float, by: Float, h: Float, dir: Float, t: Float) {
        aimView(start, aim, leftHanded)
        val d = GolfPhysics.dir(dir)
        // straight after the strike the view tilts up after the ball, then rides along behind it
        val tilt = (t / 0.25f).coerceIn(0f, 1f)
        wantLook[2] += (h * 0.8f - wantLook[2]) * tilt
        val b = ((t - 0.15f) / 0.6f).coerceIn(0f, 1f).let { it * it * (3f - 2f * it) }
        val back = 13f
        val ce0 = bx - d.x * back; val ce1 = by - d.y * back; val ce2 = 2.5f + h * 0.85f
        val cl0 = bx + d.x * 10f; val cl1 = by + d.y * 10f; val cl2 = h * 0.9f
        for (i in 0..2) {
            val ce = when (i) { 0 -> ce0; 1 -> ce1; else -> ce2 }
            val cl = when (i) { 0 -> cl0; 1 -> cl1; else -> cl2 }
            wantEye[i] += (ce - wantEye[i]) * b
            wantLook[i] += (cl - wantLook[i]) * b
        }
        wantVfov += (54f - wantVfov) * b
        snapNext = true // the blend itself is smooth; the camera must keep up with a fast ball
    }

    /** Chasing a ball in flight (or rolling): behind it along the shot, a little above. */
    fun chaseView(ballX: Float, ballY: Float, h: Float, dir: Float, progress: Float) {
        val d = GolfPhysics.dir(dir)
        val back = 14f + 6f * (1f - progress)
        set(ballX - d.x * back, ballY - d.y * back, 4f + h * 0.55f, ballX + d.x * 18f, ballY + d.y * 18f, h * 0.5f, 52f)
    }

    /** High above the tee, the whole hole laid out ahead. */
    fun overview(h: Hole, t: Double) {
        val mid = h.fairway[h.fairway.size / 2]
        val sway = sin(t * 0.25).toFloat() * 8f
        set(h.tee.x + sway, h.tee.y - 30f, 48f, (mid.x + h.pin.x) / 2f, (mid.y + h.pin.y) / 2f, 0f, 55f)
    }

    private fun set(ex: Float, ey: Float, ez: Float, lx: Float, ly: Float, lz: Float, fov: Float) {
        wantEye[0] = ex; wantEye[1] = ey; wantEye[2] = ez
        wantLook[0] = lx; wantLook[1] = ly; wantLook[2] = lz
        wantVfov = fov
    }

    /** Eases the camera towards where it is wanted ([rate] per second; larger = quicker). */
    fun update(dt: Float, rate: Float = 3.2f) {
        val k = if (snapNext) 1f else (1f - exp(-dt * rate))
        snapNext = false
        for (i in 0..2) { eye[i] += (wantEye[i] - eye[i]) * k; look[i] += (wantLook[i] - look[i]) * k }
        vfov += (wantVfov - vfov) * k
    }

    // ------------------------------------------------------------------------------------------------ drawing

    private val inX = FloatArray(MAXV); private val inY = FloatArray(MAXV); private val inZ = FloatArray(MAXV)
    private val cX = FloatArray(MAXV); private val cY = FloatArray(MAXV); private val cD = FloatArray(MAXV)
    private val oX = FloatArray(MAXV); private val oY = FloatArray(MAXV); private val oD = FloatArray(MAXV)
    private val screen = FloatArray(MAXV * 2)
    private var fogColor = 0xFFDCE8F5.toInt()

    /** Fills a ground/world polygon (n points in inX/inY/inZ), clipped at the near plane. */
    private fun poly(g: Gfx, n: Int, color: Int, fog: Boolean = true) {
        var cxs = 0f; var cys = 0f
        for (i in 0 until n) {
            cX[i] = cam.camX(inX[i], inY[i], inZ[i]); cY[i] = cam.camY(inX[i], inY[i], inZ[i]); cD[i] = cam.depth(inX[i], inY[i], inZ[i])
            cxs += inX[i]; cys += inY[i]
        }
        // Sutherland-Hodgman against depth >= near
        var m = 0
        val near = cam.near
        for (i in 0 until n) {
            val j = (i + 1) % n
            val ai = cD[i] >= near; val aj = cD[j] >= near
            if (ai) { oX[m] = cX[i]; oY[m] = cY[i]; oD[m] = cD[i]; m++ }
            if (ai != aj) {
                val t = (near - cD[i]) / (cD[j] - cD[i])
                oX[m] = cX[i] + (cX[j] - cX[i]) * t; oY[m] = cY[i] + (cY[j] - cY[i]) * t; oD[m] = near; m++
            }
            if (m >= MAXV - 2) break
        }
        if (m < 3) return
        for (i in 0 until m) {
            screen[i * 2] = cam.cx + cam.focal * oX[i] / oD[i]
            screen[i * 2 + 1] = cam.cy - cam.focal * oY[i] / oD[i]
        }
        val c = if (fog) fogged(color, hypot(cxs / n - cam.ex, cys / n - cam.ey)) else color
        g.fillPolygon(screen, m, c)
    }

    private fun fogged(c: Int, dist: Float): Int {
        val k = ((dist - 70f) / 420f).coerceIn(0f, 0.82f)
        return if (k <= 0f) c else Colors.lerp(c, fogColor, k)
    }

    private fun quad(g: Gfx, ax: Float, ay: Float, bx: Float, by: Float, cx: Float, cy: Float, dx: Float, dy: Float, color: Int, z: Float = 0f) {
        inX[0] = ax; inY[0] = ay; inX[1] = bx; inY[1] = by; inX[2] = cx; inY[2] = cy; inX[3] = dx; inY[3] = dy
        for (i in 0..3) inZ[i] = z
        poly(g, 4, color)
    }

    private fun ellipse(g: Gfx, x: Float, y: Float, rx: Float, ry: Float, color: Int, n: Int = 28, z: Float = 0f) {
        for (i in 0 until n) {
            val a = i * 2.0 * PI / n
            inX[i] = x + (cos(a) * rx).toFloat(); inY[i] = y + (sin(a) * ry).toFloat(); inZ[i] = z
        }
        poly(g, n, color)
    }

    /** Draws the whole scene into [r]. */
    fun draw(g: Gfx, r: Rect, h: Hole, time: Double, x: Extras) {
        cam.viewport(r, vfov)
        cam.lookAt(eye[0], eye[1], eye[2], look[0], look[1], look[2])
        g.save()
        g.clipRect(r.left, r.top, r.right, r.bottom)
        val hy = cam.horizonY()
        sky(g, r, hy, time)
        // the snowy land all around, lighter towards the horizon
        if (summer) g.gradientRect(r.left, max(r.top, hy), r.right, r.bottom, 0xFF86B46A.toInt(), 0xFF5F9447.toInt())
        else g.gradientRect(r.left, max(r.top, hy), r.right, r.bottom, 0xFFDDE9F6.toInt(), 0xFFF7FBFF.toInt())
        ground(g, h, time, x)
        objects(g, h, time, x)
        g.restore()
    }

    // ------------------------------------------------------------------------------------------------ sky

    private fun sky(g: Gfx, r: Rect, hy: Float, time: Double) {
        val top = r.top
        val hz = hy.coerceIn(r.top, r.bottom)
        g.gradientRect(r.left, top, r.right, hz, 0xFF2D5C9A.toInt(), 0xFFAFCBEA.toInt())
        // low winter sun glow on one side
        val sunX = r.left + r.width * (0.5f + 0.9f * sin(cam.yaw + 1.1f))
        g.glow(sunX, hz - 60f, 260f, 0x55FFE7B0)
        g.fillCircle(sunX, hz - 70f, 26f, 0xFFFFF4D6.toInt())
        // two ranges of snowy mountains, far away (they turn with the camera, they do not come closer)
        for (layer in 0..1) {
            val amp = if (layer == 0) 120f else 70f
            val col = if (summer) (if (layer == 0) 0xFF6F9A78.toInt() else 0xFF94B89A.toInt()) else if (layer == 0) 0xFF8FB0D6.toInt() else 0xFFB9CFE8.toInt()
            val n = 48
            var k = 0
            val w = r.width
            for (i in 0..n) {
                val sx = r.left + w * i / n
                val ang = cam.yaw + atan2(sx - cam.cx, cam.focal)
                val hgt = amp * (0.55f + 0.25f * sin(ang * 3.1f + layer * 1.7f) + 0.2f * sin(ang * 7.3f + layer) + 0.12f * sin(ang * 13f + 2f))
                screen[k++] = sx; screen[k++] = hz - hgt * (cam.focal / 900f).coerceIn(0.6f, 1.6f)
            }
            screen[k++] = r.right; screen[k++] = hz + 2f
            screen[k++] = r.left; screen[k++] = hz + 2f
            // snowy tops fading into blue-grey slopes
            val topCol = if (summer) (if (layer == 0) 0xFF8FB496.toInt() else 0xFFB4CDB6.toInt()) else if (layer == 0) 0xFFF2F7FF.toInt() else 0xFFE3EEFA.toInt()
            g.fillPolygonGradient(screen, n + 3, hz - amp * 1.1f, hz, topCol, col)
        }
        if (summer) return
        // light falling snow
        val rng = Rng(5)
        for (i in 0 until 40) {
            val sx = r.left + ((rng.nextFloat() * r.width + time * 18.0 * (0.5 + rng.nextFloat())) % r.width).toFloat()
            val sy = r.top + ((rng.nextFloat() * r.height + time * 40.0 * (0.6 + rng.nextFloat())) % r.height).toFloat()
            g.fillCircle(sx, sy, 2f + rng.nextFloat() * 2f, 0x99FFFFFF.toInt())
        }
    }

    // ------------------------------------------------------------------------------------------------ ground

    private fun ground(g: Gfx, h: Hole, time: Double, x: Extras) {
        // the course: rough inside the boundary
        quad(g, h.xMin, h.yMin, h.xMax, h.yMin, h.xMax, h.yMax, h.xMin, h.yMax, 0xFF79A874.toInt())
        // rough texture: soft darker patches
        val rng = Rng(h.number * 31L + 7)
        for (i in 0 until 26) {
            val px = h.xMin + rng.nextFloat() * (h.xMax - h.xMin); val py = h.yMin + rng.nextFloat() * (h.yMax - h.yMin)
            ellipse(g, px, py, 6f + rng.nextFloat() * 10f, 5f + rng.nextFloat() * 9f, 0xFF6E9C69.toInt(), 12)
        }
        // fairway: darker edge, then the mown strip with rounded joints and mowing stripes across it
        for (pass in 0..1) {
            val c = if (pass == 0) 0xFF3F9548.toInt() else 0xFF58BA60.toInt()
            val hw = h.fairwayWidth / 2f + if (pass == 0) 1.4f else 0f
            for (i in 0 until h.fairway.size - 1) strip(g, h.fairway[i], h.fairway[i + 1], hw, 0f, 1f, c)
            for (i in 1 until h.fairway.size - 1) ellipse(g, h.fairway[i].x, h.fairway[i].y, hw, hw, c, 18)
            ellipse(g, h.fairway.first().x, h.fairway.first().y, hw, hw, c, 18)
            ellipse(g, h.fairway.last().x, h.fairway.last().y, hw, hw, c, 18)
        }
        for (i in 0 until h.fairway.size - 1) {
            val a = h.fairway[i]; val b = h.fairway[i + 1]
            val len = a.dist(b)
            var d = 0f; var k = 0
            while (d < len) {
                val e = min(len, d + 10f)
                if (k % 2 == 0) strip(g, a, b, h.fairwayWidth / 2f - 0.3f, d / len, e / len, 0xFF63C66B.toInt())
                d = e; k++
            }
        }
        // tee box and its candy markers are drawn with the objects
        quad(g, -4.5f, -3.5f, 4.5f, -3.5f, 4.5f, 4.5f, -4.5f, 4.5f, 0xFF86D68A.toInt())
        // water: icy rim, water, ripples
        for (b in h.water) {
            ellipse(g, b.x, b.y, b.rx + 1.8f, b.ry + 1.8f, 0xFFF2F8FF.toInt())
            ellipse(g, b.x, b.y, b.rx, b.ry, 0xFF2E78C2.toInt())
            ellipse(g, b.x - b.rx * 0.2f, b.y + b.ry * 0.25f, b.rx * 0.45f, b.ry * 0.3f, 0xFF4A93D8.toInt(), 16)
        }
        // bunkers: lip and sand
        for (b in h.bunkers) {
            ellipse(g, b.x, b.y, b.rx + 0.8f, b.ry + 0.8f, 0xFFC4A262.toInt(), 20)
            ellipse(g, b.x, b.y, b.rx, b.ry, 0xFFF0DDA2.toInt(), 20)
        }
        // green: fringe, putting surface, cup
        ellipse(g, h.pin.x, h.pin.y, h.greenR + 2.6f, h.greenR + 2.6f, 0xFF52B95A.toInt(), 36)
        ellipse(g, h.pin.x, h.pin.y, h.greenR, h.greenR, 0xFF8FE091.toInt(), 36)
        ellipse(g, h.pin.x - h.greenR * 0.25f, h.pin.y + h.greenR * 0.2f, h.greenR * 0.55f, h.greenR * 0.45f, 0xFF9CE89D.toInt(), 20)
        if (x.putting) slopeFlow(g, h, time)
        ellipse(g, h.pin.x, h.pin.y, 0.22f, 0.22f, 0xFF1A1A1A.toInt(), 12)
        // the aim guide on the ground
        if (x.showAim && !x.ballX.isNaN()) aimGuide(g, x)
    }

    /** A strip of ground along a fairway segment from fraction [u0] to [u1], [hw] wide on each side. */
    private fun strip(g: Gfx, a: Pt, b: Pt, hw: Float, u0: Float, u1: Float, color: Int) {
        val dx = b.x - a.x; val dy = b.y - a.y
        val l = hypot(dx, dy).coerceAtLeast(1e-3f)
        val nx = -dy / l * hw; val ny = dx / l * hw
        val x0 = a.x + dx * u0; val y0 = a.y + dy * u0; val x1 = a.x + dx * u1; val y1 = a.y + dy * u1
        quad(g, x0 + nx, y0 + ny, x0 - nx, y0 - ny, x1 - nx, y1 - ny, x1 + nx, y1 + ny, color)
    }

    /** Putting: little white dashes drifting downhill across the green show its slope. */
    private fun slopeFlow(g: Gfx, h: Hole, time: Double) {
        val s = h.slope
        val mag = s.len()
        if (mag < 0.01f) return
        val ux = s.x / mag; val uy = s.y / mag
        val step = 1.6f
        val len = 0.25f + mag * 2f
        val phase = ((time * 0.5) % 1.0).toFloat()
        var gx = -h.greenR
        while (gx <= h.greenR) {
            var gy = -h.greenR
            while (gy <= h.greenR) {
                if (hypot(gx, gy) < h.greenR - 0.8f) {
                    val x0 = h.pin.x + gx + ux * step * phase; val y0 = h.pin.y + gy + uy * step * phase
                    if (cam.project(x0, y0, 0.01f)) {
                        val ax = cam.px; val ay = cam.py
                        if (cam.project(x0 + ux * len, y0 + uy * len, 0.01f)) g.line(ax, ay, cam.px, cam.py, 2f, 0x55FFFFFF)
                    }
                }
                gy += step
            }
            gx += step
        }
    }

    private fun aimGuide(g: Gfx, x: Extras) {
        val d = GolfPhysics.dir(x.aim)
        val bx = x.ballX; val by = x.ballY
        val lx = bx + d.x * x.aimDist; val ly = by + d.y * x.aimDist
        val col = Colors.withAlpha(Colors.WHITE, if (x.aimStrong) 0.95f else 0.75f)
        val n = (x.aimDist / 2.5f).toInt().coerceIn(4, 120)
        if (x.apex > 0f) {
            // the planned flight, dotted through the air
            for (i in 1 until n) {
                val u = i / n.toFloat()
                if (cam.project(bx + (lx - bx) * u, by + (ly - by) * u, x.apex * 4f * u * (1f - u))) {
                    val rr = (0.18f * cam.focal / cam.pd).coerceIn(1.5f, 7f)
                    g.fillCircle(cam.px, cam.py, rr, col)
                }
            }
        }
        // dashes along the ground
        for (i in 0 until n step 2) {
            val u0 = i / n.toFloat(); val u1 = (i + 1) / n.toFloat()
            if (!cam.project(bx + (lx - bx) * u0, by + (ly - by) * u0, 0.02f)) continue
            val ax = cam.px; val ay = cam.py
            if (!cam.project(bx + (lx - bx) * u1, by + (ly - by) * u1, 0.02f)) continue
            g.line(ax, ay, cam.px, cam.py, (0.12f * cam.focal / cam.pd).coerceIn(1.5f, 6f), Colors.withAlpha(x.aimColor, 0.7f))
        }
        // landing ring
        ringOnGround(g, lx, ly, max(0.6f, x.ringR), x.aimColor, 5f)
        ringOnGround(g, lx, ly, 0.35f, x.aimColor, 5f)
    }

    private fun ringOnGround(g: Gfx, cx0: Float, cy0: Float, rad: Float, color: Int, width: Float) {
        val n = 32
        var k = 0
        for (i in 0..n) {
            val a = i * 2.0 * PI / n
            if (!cam.project(cx0 + (cos(a) * rad).toFloat(), cy0 + (sin(a) * rad).toFloat(), 0.03f)) { k = 0; continue }
            screen[k * 2] = cam.px; screen[k * 2 + 1] = cam.py
            k++
            if (k >= 2) g.line(screen[(k - 2) * 2], screen[(k - 2) * 2 + 1], screen[(k - 1) * 2], screen[(k - 1) * 2 + 1], width, color)
        }
    }

    // ------------------------------------------------------------------------------------------------ objects

    private class Obj(var kind: Int, var x: Float, var y: Float, var size: Float, var depth: Float, var tint: Int)

    private val objs = ArrayList<Obj>(700)
    private var objCount = 0
    private var treesFor = -1
    private val treeList = ArrayList<FloatArray>() // x, y, height, tint

    private fun addObj(kind: Int, x: Float, y: Float, size: Float, tint: Int = 0) {
        val d = cam.depth(x, y, size * 0.4f)
        if (d < cam.near) return
        if (objCount >= objs.size) objs.add(Obj(kind, x, y, size, d, tint)) else objs[objCount].let { it.kind = kind; it.x = x; it.y = y; it.size = size; it.depth = d; it.tint = tint }
        objCount++
    }

    /** Trees in the hole's stands plus a forest framing the boundary (made once per hole). */
    private fun plantTrees(h: Hole) {
        val key = h.number * 1000 + h.pin.x.toInt() * 7 + h.pin.y.toInt()
        if (treesFor == key) return
        treesFor = key
        treeList.clear()
        for (b in h.trees) {
            val trng = Rng((b.x * 31 + b.y * 17).toLong())
            val n = ((b.rx * b.ry) / 45f).toInt().coerceIn(3, 40)
            repeat(n) {
                val a = trng.nextFloat() * 6.283f; val d = sqrt(trng.nextFloat()) * 0.85f
                treeList.add(floatArrayOf(b.x + cos(a) * b.rx * d, b.y + sin(a) * b.ry * d, 7f + trng.nextFloat() * 5f, trng.nextFloat()))
            }
        }
        val frng = Rng(h.number * 977L)
        var y = h.yMin - 30f
        while (y < h.yMax + 50f) {
            for (row in 0..1) {
                treeList.add(floatArrayOf(h.xMin - 7f - row * 9f + frng.nextFloat() * 3f, y + frng.nextFloat() * 5f, 8f + frng.nextFloat() * 6f, frng.nextFloat()))
                treeList.add(floatArrayOf(h.xMax + 7f + row * 9f - frng.nextFloat() * 3f, y + frng.nextFloat() * 5f, 8f + frng.nextFloat() * 6f, frng.nextFloat()))
            }
            y += 10f
        }
        var x = h.xMin - 20f
        while (x < h.xMax + 20f) {
            treeList.add(floatArrayOf(x + frng.nextFloat() * 4f, h.yMax + 10f + frng.nextFloat() * 8f, 9f + frng.nextFloat() * 6f, frng.nextFloat()))
            x += 9f
        }
    }

    private fun objects(g: Gfx, h: Hole, time: Double, x: Extras) {
        plantTrees(h)
        objCount = 0
        for (t in treeList) addObj(K_TREE, t[0], t[1], t[2], (t[3] * 1000).toInt())
        addObj(K_FLAG, h.pin.x, h.pin.y, 2.4f)
        addObj(K_MARKER, -3.2f, 0f, 0.15f); addObj(K_MARKER, 3.2f, 0f, 0.15f)
        // out-of-bounds stakes along both sides
        var sy = h.yMin
        while (sy <= h.yMax) { addObj(K_STAKE, h.xMin, sy, 0.9f); addObj(K_STAKE, h.xMax, sy, 0.9f); sy += 25f }
        if (x.golfer) addObj(K_GOLFER, x.golferX, x.golferY, 1.6f)
        if (!x.ballX.isNaN()) addObj(K_BALL, x.ballX, x.ballY, 0.05f)
        // far to near
        val list = objs.subList(0, objCount)
        list.sortByDescending { it.depth }
        for (o in list) when (o.kind) {
            K_TREE -> tree(g, o)
            K_FLAG -> flag(g, h, time)
            K_MARKER -> marker(g, o.x, o.y)
            K_STAKE -> stake(g, o.x, o.y)
            K_GOLFER -> golfer(g, x)
            K_BALL -> ball(g, x)
        }
    }

    private fun tree(g: Gfx, o: Obj) {
        if (!cam.project(o.x, o.y, 0f)) return
        val bx = cam.px; val by = cam.py; val d = cam.pd
        val hpx = o.size * cam.focal / d
        if (hpx < 3f) return
        if (bx < cam.cx - cam.focal * 2.2f || bx > cam.cx + cam.focal * 2.2f) return
        val w = hpx * 0.42f
        val dist = hypot(o.x - cam.ex, o.y - cam.ey)
        val dark = fogged(if (o.tint % 2 == 0) 0xFF1E5A33.toInt() else 0xFF235F3A.toInt(), dist)
        val mid = fogged(0xFF2F7A46.toInt(), dist)
        val snow = fogged(0xFFF4F9FF.toInt(), dist)
        // shadow on the snow
        g.fillOval(bx - w * 0.9f, by - hpx * 0.03f, bx + w * 0.9f, by + hpx * 0.05f, 0x33000000)
        if (hpx < 16f) { // far away: a single cone
            screen[0] = bx - w * 0.55f; screen[1] = by; screen[2] = bx + w * 0.55f; screen[3] = by; screen[4] = bx; screen[5] = by - hpx
            g.fillPolygon(screen, 3, dark)
            return
        }
        g.fillRect(bx - w * 0.07f, by - hpx * 0.18f, bx + w * 0.07f, by, fogged(0xFF5D4037.toInt(), dist))
        for (tier in 0..2) {
            val tb = by - hpx * (0.12f + tier * 0.25f)
            val tt = tb - hpx * 0.42f
            val tw = w * (1f - tier * 0.24f)
            screen[0] = bx - tw; screen[1] = tb; screen[2] = bx + tw; screen[3] = tb; screen[4] = bx; screen[5] = tt
            g.fillPolygon(screen, 3, if (tier == 1) mid else dark)
            if (summer) continue
            // snow resting on the tier
            screen[0] = bx - tw * 0.45f; screen[1] = tt + (tb - tt) * 0.45f; screen[2] = bx + tw * 0.45f; screen[3] = tt + (tb - tt) * 0.45f; screen[4] = bx; screen[5] = tt
            g.fillPolygon(screen, 3, snow)
        }
    }

    private fun flag(g: Gfx, h: Hole, time: Double) {
        if (!cam.project(h.pin.x, h.pin.y, 0f)) return
        val bx = cam.px; val by = cam.py
        if (!cam.project(h.pin.x, h.pin.y, 2.4f)) return
        val tx = cam.px; val ty = cam.py
        val pole = by - ty
        g.line(bx, by, tx, ty, max(2f, pole * 0.025f), 0xFFECEFF1.toInt())
        for (i in 0..5) g.line(bx + (tx - bx) * i / 6f, by + (ty - by) * i / 6f, bx + (tx - bx) * (i + 0.5f) / 6f, by + (ty - by) * (i + 0.5f) / 6f, max(2f, pole * 0.025f), Colors.CANDY)
        val dir = if (h.wind.x >= 0f) 1f else -1f
        val wave = sin(time.toFloat() * 6f) * pole * 0.03f
        screen[0] = tx; screen[1] = ty; screen[2] = tx + dir * pole * 0.42f; screen[3] = ty + pole * 0.1f + wave; screen[4] = tx; screen[5] = ty + pole * 0.24f
        g.fillPolygon(screen, 3, Colors.CANDY)
        g.glow(tx, ty + pole * 0.1f, pole * 0.4f, 0x33FF5252)
    }

    private fun marker(g: Gfx, x0: Float, y0: Float) {
        if (!cam.project(x0, y0, 0.12f)) return
        val r = (0.12f * cam.focal / cam.pd).coerceIn(2f, 40f)
        g.fillCircleRadial(cam.px, cam.py, r, cam.px - r * 0.3f, cam.py - r * 0.3f, r * 1.4f, Colors.WHITE, Colors.CANDY)
    }

    private fun stake(g: Gfx, x0: Float, y0: Float) {
        if (!cam.project(x0, y0, 0f)) return
        val bx = cam.px; val by = cam.py
        if (!cam.project(x0, y0, 0.9f)) return
        g.line(bx, by, cam.px, cam.py, max(1.5f, 0.06f * cam.focal / cam.pd), 0xFFFFFFFF.toInt())
    }

    private fun ball(g: Gfx, x: Extras) {
        // trail (only the part well in front of the camera: close points would streak across the view)
        for (i in 1 until x.trailN) {
            val a = i - 1
            if (cam.depth(x.trail[a * 3], x.trail[a * 3 + 1], x.trail[a * 3 + 2]) < 5f) continue
            if (!cam.project(x.trail[a * 3], x.trail[a * 3 + 1], x.trail[a * 3 + 2])) continue
            val ax = cam.px; val ay = cam.py
            if (!cam.project(x.trail[i * 3], x.trail[i * 3 + 1], x.trail[i * 3 + 2])) continue
            g.line(ax, ay, cam.px, cam.py, 3f + 3f * i / x.trailN, Colors.withAlpha(Colors.WHITE, 0.15f + 0.5f * i / x.trailN))
        }
        if (cam.project(x.ballX, x.ballY, 0f)) {
            val sr = max(3f, 0.12f * cam.focal / cam.pd)
            g.fillOval(cam.px - sr, cam.py - sr * 0.35f, cam.px + sr, cam.py + sr * 0.35f, 0x55000000)
        }
        if (!cam.project(x.ballX, x.ballY, x.ballH + 0.05f)) return
        val r = max(6f, 0.09f * cam.focal / cam.pd)
        g.glow(cam.px, cam.py, r * 4f, Colors.withAlpha(x.ballColor, 0.55f))
        g.fillCircleRadial(cam.px, cam.py, r, cam.px - r * 0.35f, cam.py - r * 0.35f, r * 1.4f, Colors.WHITE, 0xFFC7D3E0.toInt())
        g.strokeCircle(cam.px, cam.py, r, max(1.5f, r * 0.18f), Colors.withAlpha(x.ballColor, 0.9f))
    }

    // ------------------------------------------------------------------------------------------------ golfer

    /**
     * A snowman golfer beside the ball, facing it, swinging in a tilted plane along the target line. The swing
     * angle comes from the real player's hands (or the computer's animation): 0 at address, positive on the way
     * back, negative in the follow-through.
     */
    private fun golfer(g: Gfx, x: Extras) {
        val d = GolfPhysics.dir(x.aim)
        val rt = Pt(d.y, -d.x) // right of the target line
        val side = if (x.leftHanded) -1f else 1f
        // stands on the far side of the ball from where the club is swung, facing the ball
        val bx0 = x.golferX; val by0 = x.golferY
        val gx = bx0 - rt.x * 0.78f * side; val gy = by0 - rt.y * 0.78f * side
        val face = Pt(rt.x * side, rt.y * side)
        fun pt(ox: Float, oy: Float, z: Float): Boolean = cam.project(gx + ox, gy + oy, z)
        // shadow
        if (pt(0f, 0f, 0f)) {
            val s = 0.5f * cam.focal / cam.pd
            g.fillOval(cam.px - s, cam.py - s * 0.3f, cam.px + s, cam.py + s * 0.3f, 0x44000000)
        }
        // body spheres (lean towards the ball at address)
        val lean = 0.12f
        val parts = arrayOf(floatArrayOf(0f, 0.36f, 0.36f), floatArrayOf(lean, 0.93f, 0.26f), floatArrayOf(lean * 1.6f, 1.36f, 0.19f))
        val col = x.golferColor
        // the club and arms are drawn after the body when they are in front of it (nearer the camera)
        val sx = gx + face.x * lean * 1.3f; val sy = gy + face.y * lean * 1.3f; val sz = 1.12f
        // swing plane: from the shoulders down to the ball, and along the target line
        var e1x = bx0 - sx; var e1y = by0 - sy; var e1z = 0.05f - sz
        val l1 = sqrt(e1x * e1x + e1y * e1y + e1z * e1z)
        e1x /= l1; e1y /= l1; e1z /= l1
        var e2x = d.x; var e2y = d.y; var e2z = 0f
        val dot = e2x * e1x + e2y * e1y + e2z * e1z
        e2x -= dot * e1x; e2y -= dot * e1y; e2z -= dot * e1z
        val l2 = sqrt(e2x * e2x + e2y * e2y + e2z * e2z).coerceAtLeast(1e-3f)
        e2x /= l2; e2y /= l2; e2z /= l2
        val phi = x.swingAngle
        val arm = 0.62f
        val hx = sx + arm * (cos(phi) * e1x - sin(phi) * e2x)
        val hy = sy + arm * (cos(phi) * e1y - sin(phi) * e2y)
        val hz = sz + arm * (cos(phi) * e1z - sin(phi) * e2z)
        val hinge = (if (phi >= 0f) min(phi, 1.6f) else max(phi, -1.6f)) * 0.85f
        val clubLen = when (x.club) { Club.DRIVER -> 1.12f; Club.WOOD -> 1.05f; Club.PUTTER -> 0.85f; else -> 0.95f }
        val ca = phi + hinge
        val cx0 = hx + clubLen * (cos(ca) * e1x - sin(ca) * e2x)
        val cy0 = hy + clubLen * (cos(ca) * e1y - sin(ca) * e2y)
        val cz0 = (hz + clubLen * (cos(ca) * e1z - sin(ca) * e2z)).coerceAtLeast(0.02f)
        val handsNearer = cam.depth(hx, hy, hz) < cam.depth(gx, gy, 0.9f)
        if (!handsNearer) clubAndArms(g, sx, sy, sz, face, hx, hy, hz, cx0, cy0, cz0, x.club)
        for ((i, p) in parts.withIndex()) {
            if (!pt(face.x * p[0], face.y * p[0], p[1])) continue
            val r = p[2] * cam.focal / cam.pd
            if (summer) {
                // trousers, polo shirt in the player's colour, head
                val (c0, c1) = when (i) { 0 -> 0xFF5D6D7E.toInt() to 0xFF2E3B48.toInt(); 1 -> Colors.lighter(col, 0.25f) to Colors.darker(col, 0.7f); else -> 0xFFF6D2B4.toInt() to 0xFFC99A78.toInt() }
                if (i == 0) g.fillRoundRect(cam.px - r * 0.7f, cam.py - r, cam.px + r * 0.7f, cam.py + r, r * 0.3f, c1)
                else g.fillCircleRadial(cam.px, cam.py, r, cam.px - r * 0.35f, cam.py - r * 0.4f, r * 1.5f, c0, c1)
            } else g.fillCircleRadial(cam.px, cam.py, r, cam.px - r * 0.35f, cam.py - r * 0.4f, r * 1.5f, Colors.WHITE, 0xFFC5D5E8.toInt())
        }
        // summer: a cap in the player's colour; winter: scarf and hat
        if (summer) {
            if (pt(face.x * lean * 1.6f, face.y * lean * 1.6f, 1.47f)) {
                val r = 0.18f * cam.focal / cam.pd
                g.fillOval(cam.px - r, cam.py - r * 0.6f, cam.px + r, cam.py + r * 0.2f, col)
                g.fillOval(cam.px - r * 0.2f, cam.py - r * 0.05f, cam.px + r * 1.3f, cam.py + r * 0.25f, Colors.darker(col, 0.7f))
            }
            if (handsNearer) clubAndArms(g, sx, sy, sz, face, hx, hy, hz, cx0, cy0, cz0, x.club)
            return
        }
        if (pt(face.x * lean * 1.4f, face.y * lean * 1.4f, 1.18f)) {
            val r = 0.2f * cam.focal / cam.pd
            g.fillRoundRect(cam.px - r, cam.py - r * 0.25f, cam.px + r, cam.py + r * 0.25f, r * 0.2f, col)
        }
        if (pt(face.x * lean * 1.6f, face.y * lean * 1.6f, 1.52f)) {
            val r = 0.17f * cam.focal / cam.pd
            g.fillCircleRadial(cam.px, cam.py, r, cam.px - r * 0.3f, cam.py - r * 0.4f, r * 1.4f, Colors.lighter(col, 0.3f), Colors.darker(col, 0.7f))
            g.fillCircle(cam.px, cam.py - r * 1.05f, r * 0.35f, Colors.WHITE)
        }
        if (handsNearer) clubAndArms(g, sx, sy, sz, face, hx, hy, hz, cx0, cy0, cz0, x.club)
    }

    private fun clubAndArms(g: Gfx, sx: Float, sy: Float, sz: Float, face: Pt, hx: Float, hy: Float, hz: Float, cx0: Float, cy0: Float, cz0: Float, club: Club) {
        // arms from both shoulders to the joined hands
        val across = Pt(-face.y, face.x)
        for (s in intArrayOf(-1, 1)) {
            if (!cam.project(sx + across.x * 0.2f * s, sy + across.y * 0.2f * s, sz)) continue
            val ax = cam.px; val ay = cam.py
            if (!cam.project(hx, hy, hz)) continue
            g.line(ax, ay, cam.px, cam.py, max(2f, 0.05f * cam.focal / cam.pd), if (summer) 0xFFE8BC98.toInt() else 0xFF6D4C41.toInt())
        }
        if (!cam.project(hx, hy, hz)) return
        val hxs = cam.px; val hys = cam.py
        val mitt = max(3f, 0.07f * cam.focal / cam.pd)
        if (!cam.project(cx0, cy0, cz0)) return
        g.line(hxs, hys, cam.px, cam.py, max(1.5f, 0.02f * cam.focal / cam.pd), 0xFFCFD8DC.toInt())
        val head = max(2.5f, (if (club == Club.PUTTER) 0.05f else 0.07f) * cam.focal / cam.pd)
        g.fillCircle(cam.px, cam.py, head, if (club == Club.DRIVER || club == Club.WOOD) 0xFF37474F.toInt() else 0xFF90A4AE.toInt())
        g.fillCircle(hxs, hys, mitt, if (summer) 0xFFF5F5F5.toInt() else Colors.CANDY)
    }

    companion object {
        private const val MAXV = 96
        private const val K_TREE = 0
        private const val K_FLAG = 1
        private const val K_MARKER = 2
        private const val K_STAKE = 3
        private const val K_GOLFER = 4
        private const val K_BALL = 5

        /** Swing angle for the 3D golfer from the player's hands (body units from the shoulders, screen-mirrored). */
        fun swingAngleFromHands(hx: Float, hy: Float, leftHanded: Boolean): Float {
            val side = if (leftHanded) -1f else 1f
            return atan2(side * hx, -hy).let { if (abs(it) < 0.05f) 0f else it }
        }
    }
}
