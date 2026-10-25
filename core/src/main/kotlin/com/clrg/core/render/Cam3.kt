package com.clrg.core.render

import com.clrg.core.math.Rect
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * A pinhole camera for the 3D scenes (metres): x to the right, y forwards (into the scene), z up.
 * No roll: the horizon is always level.
 */
class Cam3 {
    var ex = 0f; var ey = 0f; var ez = 2f
    var fx = 0f; var fy = 1f; var fz = 0f
    var rx = 1f; var ry = 0f; var rz = 0f
    var ux = 0f; var uy = 0f; var uz = 1f
    var focal = 900f
    var cx = 960f
    var cy = 540f
    val near = 0.35f

    fun lookAt(eyeX: Float, eyeY: Float, eyeZ: Float, tx: Float, ty: Float, tz: Float) {
        ex = eyeX; ey = eyeY; ez = eyeZ
        var dx = tx - eyeX; var dy = ty - eyeY; var dz = tz - eyeZ
        val l = sqrt(dx * dx + dy * dy + dz * dz).coerceAtLeast(1e-4f)
        dx /= l; dy /= l; dz /= l
        fx = dx; fy = dy; fz = dz
        // right = forward x up(0,0,1), up = right x forward
        var r0 = dy; var r1 = -dx
        val rl = hypot(r0, r1).coerceAtLeast(1e-4f)
        r0 /= rl; r1 /= rl
        rx = r0; ry = r1; rz = 0f
        ux = ry * fz - rz * fy; uy = rz * fx - rx * fz; uz = rx * fy - ry * fx
    }

    fun viewport(r: Rect, vfovDeg: Float) {
        cx = r.cx; cy = r.cy
        focal = (r.height / 2f) / tan((vfovDeg * PI / 360.0).toFloat())
    }

    fun depth(x: Float, y: Float, z: Float) = (x - ex) * fx + (y - ey) * fy + (z - ez) * fz
    fun camX(x: Float, y: Float, z: Float) = (x - ex) * rx + (y - ey) * ry + (z - ez) * rz
    fun camY(x: Float, y: Float, z: Float) = (x - ex) * ux + (y - ey) * uy + (z - ez) * uz

    /** Screen y of the horizon (where the flat ground meets the sky). */
    fun horizonY(): Float {
        val hl = hypot(fx, fy).coerceAtLeast(1e-4f)
        val hx = fx / hl; val hy = fy / hl
        val d = hx * fx + hy * fy
        val u = hx * ux + hy * uy
        return cy - focal * u / d.coerceAtLeast(1e-3f)
    }

    /** Heading of the camera (radians, 0 = towards the green). */
    val yaw: Float get() = atan2(fx, fy)

    // last projection
    var px = 0f; var py = 0f; var pd = 0f

    /** Projects a point; false when it is behind the camera. */
    fun project(x: Float, y: Float, z: Float): Boolean {
        val d = depth(x, y, z)
        pd = d
        if (d < near) return false
        px = cx + focal * camX(x, y, z) / d
        py = cy - focal * camY(x, y, z) / d
        return true
    }
}

