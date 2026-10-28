package com.bodmannferdinand.apexrush

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import org.json.JSONObject
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

// ============================================================
//  APEX RUSH - arcade car racing for Android
//  Single-file game: pseudo-3D renderer, garage with deep car
//  customization, upgrades, 3 themed tracks, AI opponents,
//  drift/nitro mechanics, saved progress. No assets needed:
//  every graphic is drawn procedurally with Canvas.
// ============================================================

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(GameView(this))
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
    }
}

// ------------------------- utils -------------------------

object U {
    fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
    fun clamp(v: Float, lo: Float, hi: Float) = if (v < lo) lo else if (v > hi) hi else v
    fun wrap(v: Float, span: Float): Float = ((v % span) + span) % span
    fun mod(v: Int, n: Int): Int = ((v % n) + n) % n
    fun c255(v: Float) = if (v < 0f) 0 else if (v > 255f) 255 else v.toInt()

    fun lighten(c: Int, f: Float): Int {
        val r = (c shr 16 and 0xFF); val g = (c shr 8 and 0xFF); val b = (c and 0xFF)
        return Color.argb(255, c255(r + (255 - r) * f), c255(g + (255 - g) * f), c255(b + (255 - b) * f))
    }

    fun darken(c: Int, f: Float): Int {
        val r = (c shr 16 and 0xFF); val g = (c shr 8 and 0xFF); val b = (c and 0xFF)
        return Color.argb(255, c255(r * (1 - f)), c255(g * (1 - f)), c255(b * (1 - f)))
    }

    fun withA(c: Int, a: Int): Int = (a shl 24) or (c and 0xFFFFFF)

    fun fmtTime(ms: Long): String {
        val t = if (ms < 0) 0 else ms
        val m = t / 60000
        val s = (t % 60000) / 1000
        val hs = (t % 1000) / 10
        return String.format(Locale.US, "%d:%02d.%02d", m, s, hs)
    }
}

class Rng(seed: Int) {
    var s = seed
    fun next(): Float {
        s = s * 1664525 + 1013904223
        return ((s ushr 9) and 0x7FFFFF) / 8388608f
    }
    fun range(a: Float, b: Float) = a + (b - a) * next()
    fun int(n: Int) = (next() * n).toInt()
}

// ------------------------- data -------------------------

class CarSpec {
    var chassis = 0   // 0 Coupe GT, 1 Muscle 442, 2 Hyper X
    var paint = 0     // PAINTS index
    var finish = 1    // 0 matte, 1 gloss, 2 metallic, 3 chameleon
    var rim = 0       // 0..4 rim style
    var rimColor = 0
    var spoiler = 0   // 0 none, 1 ducktail, 2 GT wing
    var glow = 0      // 0 off, else NEONS index+1
    var stripes = 0   // 0 none, 1 dual, 2 center

    fun copyFrom(o: CarSpec) {
        chassis = o.chassis; paint = o.paint; finish = o.finish; rim = o.rim
        rimColor = o.rimColor; spoiler = o.spoiler; glow = o.glow; stripes = o.stripes
    }

    fun toJson(): JSONObject = JSONObject()
        .put("chassis", chassis).put("paint", paint).put("finish", finish)
        .put("rim", rim).put("rimColor", rimColor).put("spoiler", spoiler)
        .put("glow", glow).put("stripes", stripes)

    fun fromJson(j: JSONObject?) {
        if (j == null) return
        chassis = j.optInt("chassis", 0); paint = j.optInt("paint", 0)
        finish = j.optInt("finish", 1); rim = j.optInt("rim", 0)
        rimColor = j.optInt("rimColor", 0); spoiler = j.optInt("spoiler", 0)
        glow = j.optInt("glow", 0); stripes = j.optInt("stripes", 0)
    }
}

class Upg {
    var engine = 0; var turbo = 0; var tires = 0
    companion object { fun cost(level: Int) = 300 + level * 300 }
}

class Save(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("apex_rush", Context.MODE_PRIVATE)
    var coins = 800
    val spec = CarSpec()
    val upg = Upg()
    var best = longArrayOf(-1L, -1L, -1L, -1L, -1L, -1L)
    var track = 0

    fun load() {
        coins = prefs.getInt("coins", 800)
        spec.fromJson(prefs.getString("spec", null)?.let { runCatching { JSONObject(it) }.getOrNull() })
        upg.engine = prefs.getInt("e", 0); upg.turbo = prefs.getInt("t", 0); upg.tires = prefs.getInt("g", 0)
        val b = prefs.getString("best", null)
        if (b != null) {
            val parts = b.split(",")
            for (i in 0 until 6) if (i < parts.size) best[i] = parts[i].toLongOrNull() ?: -1L
        }
        track = min(prefs.getInt("track", 0), THEMES.size - 1)
    }

    fun save() {
        prefs.edit()
            .putInt("coins", coins)
            .putString("spec", spec.toJson().toString())
            .putInt("e", upg.engine).putInt("t", upg.turbo).putInt("g", upg.tires)
            .putString("best", best.joinToString(","))
            .putInt("track", track)
            .apply()
    }

    fun topSpeedKmh() = 200 + upg.engine * 22
    fun accelStat() = 1f + upg.turbo * 0.15f
    fun gripStat() = 1f + upg.tires * 0.13f
}

// ------------------------- palettes & themes -------------------------

val PAINTS = intArrayOf(
    0xFFD32F2F.toInt(), 0xFFE64A19.toInt(), 0xFFF9A825.toInt(), 0xFF388E3C.toInt(),
    0xFF00BFA5.toInt(), 0xFF0091EA.toInt(), 0xFF1E88E5.toInt(), 0xFF3949AB.toInt(),
    0xFF8E24AA.toInt(), 0xFFD81B60.toInt(), 0xFFF06292.toInt(), 0xFF00E5FF.toInt(),
    0xFFFAFAFA.toInt(), 0xFFB0BEC5.toInt(), 0xFF455A64.toInt(), 0xFF151515.toInt()
)
val FINISH_NAMES = arrayOf("Matte", "Gloss", "Metallic", "Chameleon")
val RIM_NAMES = arrayOf("Classic 5", "Turbofan", "Deep Dish", "Mesh", "Blade")
val RIM_COLORS = intArrayOf(
    0xFFECEFF1.toInt(), 0xFFFFD54F.toInt(), 0xFF212121.toInt(),
    0xFF8D6E63.toInt(), 0xFFE53935.toInt()
)
val NEONS = intArrayOf(
    0xFF00E676.toInt(), 0xFF00E5FF.toInt(), 0xFFFF4081.toInt(),
    0xFFAA00FF.toInt(), 0xFFFF6D00.toInt(), 0xFFFF1744.toInt()
)
val SPOILER_NAMES = arrayOf("Clean", "Ducktail", "GT Wing")
val STRIPE_NAMES = arrayOf("None", "Dual Stripe", "Center Stripe")
val CHASSIS_NAMES = arrayOf("Coupe GT", "Muscle 442", "Hyper X")

class Theme(
    val name: String,
    val skyTop: Int, val skyBot: Int,
    val sunColor: Int, val sunX: Float, val sunY: Float, val sunR: Float,
    val mtFar: Int, val mtNear: Int,
    val grass1: Int, val grass2: Int,
    val road1: Int, val road2: Int,
    val rumble1: Int, val rumble2: Int,
    val lane: Int, val fog: Int,
    val night: Boolean, val city: Boolean, val snow: Boolean,
    val rain: Boolean, val lightning: Boolean,
    val scenery: IntArray, val accent: Int
)

val THEMES = arrayOf(
    Theme("Sunset Coast", 0xFF3A2A6A.toInt(), 0xFFFF9E4F.toInt(),
        0xFFFFB74D.toInt(), 0.62f, 0.30f, 0.09f,
        0xFF8A4A6E.toInt(), 0xFF5E2F55.toInt(),
        0xFFC98A4B.toInt(), 0xFFBE7F42.toInt(),
        0xFF4A4A52.toInt(), 0xFF45454D.toInt(),
        0xFFF5F5F5.toInt(), 0xFFE53935.toInt(),
        0xFFEDEDED.toInt(), 0xFFE8996B.toInt(),
        false, false, false, false, false,
        intArrayOf(0, 1, 2), 0xFFFF6D00.toInt()),
    Theme("Neon City", 0xFF07071A.toInt(), 0xFF33104C.toInt(),
        0xFFB2EBF2.toInt(), 0.72f, 0.16f, 0.05f,
        0xFF0D0D22.toInt(), 0xFF11112E.toInt(),
        0xFF121218.toInt(), 0xFF0E0E14.toInt(),
        0xFF26262E.toInt(), 0xFF212229.toInt(),
        0xFFF50057.toInt(), 0xFF00E5FF.toInt(),
        0xFF9FE8FF.toInt(), 0xFF0A0A18.toInt(),
        true, true, false, false, false,
        intArrayOf(3, 4), 0xFF00E5FF.toInt()),
    Theme("Alpine Pass", 0xFF1E5FBF.toInt(), 0xFFA8D8F0.toInt(),
        0xFFFFF3C4.toInt(), 0.30f, 0.14f, 0.05f,
        0xFF7A9BBF.toInt(), 0xFF4A6E92.toInt(),
        0xFFE8F0F6.toInt(), 0xFFDCE8F2.toInt(),
        0xFF3E3E46.toInt(), 0xFF393941.toInt(),
        0xFFE53935.toInt(), 0xFFF5F5F5.toInt(),
        0xFFF5F5F5.toInt(), 0xFFC8DCF0.toInt(),
        false, false, true, false, false,
        intArrayOf(5, 1), 0xFF2E7D32.toInt()),
    Theme("Desert Heat", 0xFF1565C0.toInt(), 0xFFFFCC80.toInt(),
        0xFFFFF59D.toInt(), 0.50f, 0.12f, 0.07f,
        0xFF9E7B5A.toInt(), 0xFF7A5B44.toInt(),
        0xFFD9B166.toInt(), 0xFFCEA55C.toInt(),
        0xFF4E4A46.toInt(), 0xFF484441.toInt(),
        0xFFF5F5F5.toInt(), 0xFFE53935.toInt(),
        0xFFF5F5F5.toInt(), 0xFFE8C98A.toInt(),
        false, false, false, false, false,
        intArrayOf(6, 1, 2), 0xFFF57F17.toInt()),
    Theme("Emerald Forest", 0xFF33606E.toInt(), 0xFFA8C6B0.toInt(),
        0xFFEAFBEA.toInt(), 0.35f, 0.18f, 0.05f,
        0xFF395E4B.toInt(), 0xFF254A38.toInt(),
        0xFF1F5E33.toInt(), 0xFF1A5329.toInt(),
        0xFF3A3D40.toInt(), 0xFF35383B.toInt(),
        0xFFF5F5F5.toInt(), 0xFF4CAF50.toInt(),
        0xFFE8F5E9.toInt(), 0xFF7FA58C.toInt(),
        false, false, false, true, false,
        intArrayOf(5, 5, 1), 0xFF4CAF50.toInt()),
    Theme("Midnight Storm", 0xFF050510.toInt(), 0xFF1B2340.toInt(),
        0xFFE1E8F0.toInt(), 0.68f, 0.20f, 0.045f,
        0xFF0B0F1E.toInt(), 0xFF111730.toInt(),
        0xFF0E1420.toInt(), 0xFF0C111A.toInt(),
        0xFF22242E.toInt(), 0xFF1E2028.toInt(),
        0xFF80D8FF.toInt(), 0xFF2962FF.toInt(),
        0xFFB3E5FC.toInt(), 0xFF0A0E1A.toInt(),
        true, true, false, true, true,
        intArrayOf(4, 3), 0xFF448AFF.toInt()
    )
)

// difficulty rating (1..6) used in track select + AI pacing
val TRACK_RATING = intArrayOf(1, 2, 2, 3, 4, 5)

// ------------------------- track -------------------------

const val SEG_LEN = 200f
const val ROAD_W = 2200f
const val RUMBLE_LEN = 3
const val DRAW_DIST = 280
const val CAM_H = 1050f
const val CAM_DEPTH = 0.8391f          // 1 / tan(50 deg)
const val PLAYER_Z = CAM_H * CAM_DEPTH
const val BASE_MAX_SPEED = 12000f
const val LAPS = 3
const val N_RACERS = 6

class WPoint(var x: Float = 0f, var y: Float = 0f, var z: Float = 0f)

class ProjPoint {
    val world = WPoint()
    val cam = WPoint()
    var sx = 0f; var sy = 0f; var sw = 0f; var scale = 0f
}

class Sprite(val type: Int, val offset: Float, val scale: Float)

class Seg(val index: Int, val p1: ProjPoint, val p2: ProjPoint) {
    var curve = 0f
    var clip = 0f
    var fog = 1f
    val sprites = ArrayList<Sprite>()
}

class Track(val themeIdx: Int, val segments: Array<Seg>) {
    val length = segments.size * SEG_LEN
    val theme: Theme get() = THEMES[themeIdx]

    // Real 2D map of the circuit: the per-segment curvature is integrated
    // into a driven path (heading += curve * k), then normalized to 0..1.
    // This is the actual track geometry used for minimaps — not a picture.
    val mapX = FloatArray(segments.size)
    val mapY = FloatArray(segments.size)

    init {
        val k = 0.03f
        var hx = 0f
        var hy = 0f
        var heading = 0f
        var minX = 0f; var maxX = 0f
        var minY = 0f; var maxY = 0f
        for (i in segments.indices) {
            mapX[i] = hx
            mapY[i] = hy
            hx += cos(heading)
            hy += sin(heading)
            heading += segments[i].curve * k
            if (hx < minX) minX = hx
            if (hx > maxX) maxX = hx
            if (hy < minY) minY = hy
            if (hy > maxY) maxY = hy
        }
        val cx = (minX + maxX) * 0.5f
        val cy = (minY + maxY) * 0.5f
        val s = 1f / maxOf(maxX - minX, maxY - minY, 1f)
        for (i in segments.indices) {
            mapX[i] = 0.5f + (mapX[i] - cx) * s
            mapY[i] = 0.5f + (mapY[i] - cy) * s
        }
    }
}

object TrackBuilder {
    private const val PI_F = 3.14159274f

    private fun easeIn(a: Float, b: Float, p: Float) = a + (b - a) * p * p
    private fun easeInOut(a: Float, b: Float, p: Float) = a + (b - a) * (1f - cos(p * PI_F)) * 0.5f

    fun build(themeIdx: Int): Track {
        val segs = ArrayList<Seg>()
        var lastY = 0f
        fun add(curve: Float, y: Float) {
            val p1 = ProjPoint(); val p2 = ProjPoint()
            p1.world.y = lastY
            p2.world.y = y
            val s = Seg(segs.size, p1, p2)
            s.curve = curve
            segs.add(s)
            lastY = y
        }
        fun addRoad(enter: Int, hold: Int, leave: Int, curve: Float, hillHeight: Float) {
            val startY = lastY
            val endY = startY + hillHeight * SEG_LEN
            val total = enter + hold + leave
            for (i in 0 until enter) add(easeIn(0f, curve, i / enter.toFloat()), easeInOut(startY, endY, i / total.toFloat()))
            for (i in 0 until hold) add(curve, easeInOut(startY, endY, (enter + i) / total.toFloat()))
            for (i in 0 until leave) add(easeInOut(curve, 0f, i / leave.toFloat()), easeInOut(startY, endY, (enter + hold + i) / total.toFloat()))
        }

        when (themeIdx) {
            0 -> { // Sunset Coast: flowing S-curves, big hills
                addRoad(30, 60, 30, 0f, 0f)
                addRoad(40, 60, 40, 2.4f, 12f)
                addRoad(40, 40, 40, -3.2f, -6f)
                addRoad(30, 50, 30, 3.6f, 20f)
                addRoad(60, 60, 60, 0f, -26f)
                addRoad(40, 70, 40, -2.2f, 8f)
                addRoad(30, 30, 30, 4.4f, -8f)
                addRoad(50, 80, 50, 1.8f, 14f)
                addRoad(40, 40, 40, -4.2f, -18f)
                addRoad(40, 60, 40, 3.0f, 10f)
                addRoad(60, 80, 60, 0f, -14f)
                addRoad(30, 40, 30, -3.6f, 0f)
            }
            1 -> { // Neon City: tighter technical layout
                addRoad(25, 50, 25, 0f, 0f)
                addRoad(25, 35, 25, 4.2f, 4f)
                addRoad(25, 25, 25, -4.6f, -2f)
                addRoad(20, 30, 20, 5.2f, 0f)
                addRoad(35, 45, 35, 0f, 8f)
                addRoad(20, 25, 20, -5.0f, -4f)
                addRoad(20, 20, 20, 5.4f, 2f)
                addRoad(30, 40, 30, -2.6f, -6f)
                addRoad(25, 30, 25, 4.0f, 4f)
                addRoad(35, 50, 35, 0f, -6f)
                addRoad(25, 35, 25, -4.4f, 0f)
                addRoad(25, 40, 25, 3.2f, 0f)
            }
            2 -> { // Alpine Pass: long sweepers, huge climbs
                addRoad(30, 50, 30, 0f, 0f)
                addRoad(50, 80, 50, 2.0f, 30f)
                addRoad(40, 60, 40, -2.8f, 22f)
                addRoad(60, 90, 60, 0f, -40f)
                addRoad(35, 55, 35, 3.4f, 18f)
                addRoad(40, 50, 40, -3.4f, -24f)
                addRoad(30, 60, 30, 1.6f, 14f)
                addRoad(45, 70, 45, -2.0f, -10f)
                addRoad(35, 60, 35, 2.6f, 6f)
                addRoad(50, 70, 50, 0f, -16f)
            }
            3 -> { // Desert Heat: fast banked sweepers over dunes
                addRoad(30, 60, 30, 0f, 0f)
                addRoad(40, 70, 40, 2.8f, 10f)
                addRoad(30, 40, 30, -2.4f, -14f)
                addRoad(50, 90, 50, 3.8f, 22f)
                addRoad(30, 30, 30, -4.6f, -10f)
                addRoad(40, 60, 40, 1.6f, 16f)
                addRoad(35, 45, 35, -3.0f, -24f)
                addRoad(45, 75, 45, 4.0f, 12f)
                addRoad(30, 40, 30, -3.8f, -12f)
            }
            4 -> { // Emerald Forest: tight technical twisties
                addRoad(25, 50, 25, 0f, 0f)
                addRoad(25, 30, 25, -3.4f, 6f)
                addRoad(20, 25, 20, 4.8f, -4f)
                addRoad(30, 40, 30, -2.2f, 12f)
                addRoad(20, 20, 20, 5.2f, 4f)
                addRoad(25, 35, 25, -4.4f, -8f)
                addRoad(35, 50, 35, 2.6f, 10f)
                addRoad(20, 25, 20, -5.0f, -6f)
                addRoad(25, 30, 25, 3.6f, -10f)
                addRoad(30, 45, 30, -2.8f, 0f)
            }
            else -> { // Midnight Storm: huge straights + big sweepers
                addRoad(40, 80, 40, 0f, 0f)
                addRoad(30, 60, 30, 3.2f, 6f)
                addRoad(60, 100, 60, -1.6f, -12f)
                addRoad(25, 35, 25, 5.4f, 8f)
                addRoad(50, 90, 50, 2.2f, -14f)
                addRoad(25, 30, 25, -5.2f, 10f)
                addRoad(60, 110, 60, 0f, -8f)
                addRoad(35, 55, 35, 3.4f, 16f)
                addRoad(40, 60, 40, -3.0f, -6f)
            }
        }
        addRoad(30, 60, 30, 0f, -lastY / SEG_LEN) // glide back to start height

        val arr = segs.toTypedArray()
        val theme = THEMES[themeIdx]
        val rng = Rng(1234 + themeIdx * 77)
        val dens = if (themeIdx == 4) 4 else 5
        for (i in 8 until arr.size step dens) {
            if (rng.next() < 0.55f) {
                val type = theme.scenery[rng.int(theme.scenery.size)]
                val side = if (rng.next() < 0.5f) -1f else 1f
                arr[i].sprites.add(Sprite(type, side * rng.range(1.5f, 3.2f), rng.range(0.8f, 1.4f)))
            }
        }
        // start/finish gantry + mid-track gantries to drive under
        arr[1].sprites.add(Sprite(7, 0f, 8f))
        for (i in 280 until arr.size - 8 step 280) {
            arr[i].sprites.add(Sprite(7, 0f, 8f))
        }
        // chevron corner boards on the outside of sharp corners
        for (i in 12 until arr.size step 7) {
            val cv = arr[i].curve
            if (abs(cv) > 2.8f) {
                arr[i].sprites.add(Sprite(8, if (cv > 0f) -1.45f else 1.45f, 1.25f))
            }
        }
        return Track(themeIdx, arr)
    }
}

// ------------------------- car renderer -------------------------

object CarArt {
    val bodyPath = Path()
    val cabinPath = Path()
    val tmp = Path()
    val sparkX = FloatArray(60) { ((it * 37) % 17).toFloat() - 8f }
    val sparkY = FloatArray(60) { -8f - (it * 53) % 52 }

    fun bodyPaint(color: Int, finish: Int): Paint {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        when (finish) {
            0 -> p.color = U.darken(color, 0.15f)
            2 -> p.shader = LinearGradient(0f, -64f, 0f, 0f,
                U.lighten(color, 0.35f), U.darken(color, 0.45f), Shader.TileMode.CLAMP)
            3 -> p.shader = LinearGradient(0f, -64f, 0f, 0f,
                U.lighten(color, 0.30f), U.darken(color, 0.20f), Shader.TileMode.CLAMP)
            else -> p.shader = LinearGradient(0f, -64f, 0f, 0f,
                U.lighten(color, 0.28f), U.darken(color, 0.40f), Shader.TileMode.CLAMP)
        }
        return p
    }

    /** Rear-view stylized car. Units: width ~128, height ~64. Origin = ground center. */
    fun draw(c: Canvas, spec: CarSpec, cx: Float, cy: Float, s: Float,
             steer: Float, bounce: Float, boosting: Boolean, braking: Boolean,
             night: Boolean, t: Float, detail: Boolean) {
        val color = PAINTS[spec.paint % PAINTS.size]
        val rimC = RIM_COLORS[spec.rimColor % RIM_COLORS.size]
        val wide = if (spec.chassis == 2) 60f else if (spec.chassis == 1) 58f else 54f
        val cabinTop = if (spec.chassis == 2) -50f else if (spec.chassis == 1) -54f else -58f

        c.save()
        c.translate(cx, cy - bounce)
        c.rotate(steer * 4f)
        c.scale(s, s)

        val p = Paint(Paint.ANTI_ALIAS_FLAG)

        // shadow + underglow
        p.color = 0x66000000
        c.drawOval(RectF(-66f, -4f, 66f, 8f), p)
        if (spec.glow > 0) {
            val g = NEONS[(spec.glow - 1) % NEONS.size]
            val pulse = 0.55f + 0.25f * sin(t * 3f)
            val gp = Paint(Paint.ANTI_ALIAS_FLAG)
            gp.shader = RadialGradient(0f, 2f, 62f, U.withA(g, (150 * pulse).toInt()),
                U.withA(g, 0), Shader.TileMode.CLAMP)
            c.drawOval(RectF(-70f, -12f, 70f, 12f), gp)
        }

        // exhaust flames when boosting
        if (boosting) {
            val fl = Paint(Paint.ANTI_ALIAS_FLAG)
            val len = 26f + sin(t * 41f) * 7f
            for (ex in floatArrayOf(-22f, 22f)) {
                fl.color = U.withA(0xFFFF6D00.toInt(), 200)
                c.drawOval(RectF(ex - 5f, -10f, ex + 5f, -10f + len), fl)
                fl.color = U.withA(0xFFFFEB3B.toInt(), 220)
                c.drawOval(RectF(ex - 2.6f, -10f, ex + 2.6f, -10f + len * 0.62f), fl)
            }
        }

        // rear wheels
        for (wx in floatArrayOf(-52f, 52f)) {
            p.color = 0xFF101010.toInt()
            c.drawRoundRect(RectF(wx - 13f, -27f, wx + 13f, 1f), 6f, 6f, p)
            if (detail) {
                p.color = 0xFF2A2A2A.toInt()
                c.drawCircle(wx, -12f, 9.5f, p)
                drawRim(c, wx, -12f, 8.6f, spec.rim, rimC)
                if (braking) {
                    p.color = U.withA(0xFFFF1744.toInt(), 130)
                    c.drawCircle(wx, -12f, 6f, p)
                }
            }
        }

        // lower body
        bodyPath.reset()
        bodyPath.moveTo(-wide, -4f)
        bodyPath.quadTo(-wide - 4f, -22f, -wide + 8f, -30f)
        bodyPath.lineTo(wide - 8f, -30f)
        bodyPath.quadTo(wide + 4f, -22f, wide, -4f)
        bodyPath.quadTo(wide - 6f, 2f, wide - 14f, 2f)
        bodyPath.lineTo(-wide + 14f, 2f)
        bodyPath.quadTo(-wide + 6f, 2f, -wide, -4f)
        bodyPath.close()
        c.drawPath(bodyPath, bodyPaint(color, spec.finish))

        // cabin
        cabinPath.reset()
        cabinPath.moveTo(-wide + 22f, -30f)
        cabinPath.lineTo(-wide + 26f, cabinTop + 4f)
        cabinPath.quadTo(-wide + 27f, cabinTop, -wide + 31f, cabinTop)
        cabinPath.lineTo(wide - 31f, cabinTop)
        cabinPath.quadTo(wide - 27f, cabinTop, wide - 26f, cabinTop + 4f)
        cabinPath.lineTo(wide - 22f, -30f)
        cabinPath.close()
        p.color = U.darken(color, 0.18f)
        c.drawPath(cabinPath, p)

        // metallic flake (clipped to body)
        if (detail && spec.finish == 2) {
            c.save()
            c.clipPath(bodyPath)
            val mp = Paint(Paint.ANTI_ALIAS_FLAG)
            mp.color = U.withA(0xFFFFFFFF.toInt(), 110)
            for (i in sparkX.indices) {
                val sx = sparkX[i] * (wide / 54f) * 3.4f
                c.drawCircle(sx, sparkY[i], 0.8f, mp)
            }
            c.restore()
        }

        // stripes
        if (spec.stripes > 0) {
            val sp = Paint(Paint.ANTI_ALIAS_FLAG)
            sp.color = U.withA(0xFFF5F5F5.toInt(), 235)
            c.save(); c.clipPath(bodyPath)
            if (spec.stripes == 1) { c.drawRect(RectF(-13f, -30f, -5f, 2f), sp); c.drawRect(RectF(5f, -30f, 13f, 2f), sp) }
            else c.drawRect(RectF(-4.5f, -30f, 4.5f, 2f), sp)
            c.restore()
            c.save(); c.clipPath(cabinPath)
            if (spec.stripes == 1) { c.drawRect(RectF(-13f, cabinTop, -5f, -30f), sp); c.drawRect(RectF(5f, cabinTop, 13f, -30f), sp) }
            else c.drawRect(RectF(-4.5f, cabinTop, 4.5f, -30f), sp)
            c.restore()
        }

        // rear window
        val glass = Paint(Paint.ANTI_ALIAS_FLAG)
        glass.shader = LinearGradient(0f, cabinTop, 0f, -30f,
            0xFF263238.toInt(), 0xFF546E7A.toInt(), Shader.TileMode.CLAMP)
        tmp.reset()
        tmp.moveTo(-wide + 27f, -32f)
        tmp.lineTo(-wide + 30f, cabinTop + 6f)
        tmp.quadTo(0f, cabinTop + 2f, wide - 30f, cabinTop + 6f)
        tmp.lineTo(wide - 27f, -32f)
        tmp.close()
        c.drawPath(tmp, glass)
        if (detail) {
            glass.shader = null
            glass.color = U.withA(0xFFFFFFFF.toInt(), 46)
            tmp.reset()
            tmp.moveTo(-wide + 32f, -34f)
            tmp.lineTo(-wide + 38f, cabinTop + 7f)
            tmp.lineTo(-wide + 46f, cabinTop + 7f)
            tmp.lineTo(-wide + 40f, -34f)
            tmp.close()
            c.drawPath(tmp, glass)
        }

        // taillights
        val bright = braking || night
        val lp = Paint(Paint.ANTI_ALIAS_FLAG)
        for (lx in floatArrayOf(-1f, 1f)) {
            val x0 = lx * (wide - 18f)
            lp.shader = RadialGradient(x0, -22f, 16f,
                U.withA(0xFFFF1744.toInt(), if (bright) 160 else 70),
                U.withA(0xFFFF1744.toInt(), 0), Shader.TileMode.CLAMP)
            c.drawCircle(x0, -22f, 16f, lp)
            lp.shader = null
            lp.color = if (bright) 0xFFFF5252.toInt() else 0xFFC62828.toInt()
            if (spec.chassis == 1) c.drawRect(RectF(x0 - 14f, -26f, x0 + 14f, -18f), lp)
            else c.drawRoundRect(RectF(x0 - 12f, -27f, x0 + 12f, -17f), 4f, 4f, lp)
        }
        if (spec.chassis == 2 && detail) {
            lp.color = U.withA(0xFF00E5FF.toInt(), 120)
            c.drawRect(RectF(-wide + 16f, -13f, wide - 16f, -10f), lp)
        }

        // plate
        lp.color = 0xFFEEEEEE.toInt()
        c.drawRect(RectF(-9f, -15f, 9f, -8f), lp)

        // spoiler
        if (spec.spoiler == 1) {
            val dp = Paint(Paint.ANTI_ALIAS_FLAG)
            dp.color = U.darken(color, 0.1f)
            c.drawRoundRect(RectF(-wide + 4f, -36f, wide - 4f, -31f), 3f, 3f, dp)
        } else if (spec.spoiler == 2) {
            val sp2 = Paint(Paint.ANTI_ALIAS_FLAG)
            sp2.color = 0xFF212121.toInt()
            c.drawRect(RectF(-34f, -48f, -29f, -33f), sp2)
            c.drawRect(RectF(29f, -48f, 34f, -33f), sp2)
            val wingP = Paint(Paint.ANTI_ALIAS_FLAG)
            wingP.shader = LinearGradient(0f, -56f, 0f, -48f,
                U.lighten(color, 0.15f), U.darken(color, 0.35f), Shader.TileMode.CLAMP)
            c.drawRoundRect(RectF(-wide - 4f, -56f, wide + 4f, -48f), 3f, 3f, wingP)
        }

        // exhausts
        val ep = Paint(Paint.ANTI_ALIAS_FLAG)
        for (ex in floatArrayOf(-22f, 22f)) {
            ep.color = 0xFF1A1A1A.toInt()
            val r = if (spec.chassis == 2) 7f else if (spec.chassis == 1) 6f else 5f
            c.drawCircle(ex, -5f, r, ep)
            ep.color = 0xFF616161.toInt()
            c.drawCircle(ex, -5f, r * 0.55f, ep)
        }

        c.restore()
    }

    private fun drawRim(c: Canvas, x: Float, y: Float, r: Float, style: Int, color: Int) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val ring = Paint(Paint.ANTI_ALIAS_FLAG)
        ring.color = color
        ring.style = Paint.Style.STROKE
        when (style) {
            0 -> { p.color = color; c.drawCircle(x, y, r, p); spokes(c, x, y, r, 5, ring, true) }
            1 -> { p.color = color; c.drawCircle(x, y, r, p); spokes(c, x, y, r, 12, ring, false) }
            2 -> { p.color = color; c.drawCircle(x, y, r * 0.45f, p); ring.strokeWidth = r * 0.22f; c.drawCircle(x, y, r, ring) }
            3 -> { p.color = color; c.drawCircle(x, y, r * 0.3f, p); spokes(c, x, y, r, 8, ring, false); ring.strokeWidth = r * 0.12f; c.drawCircle(x, y, r * 0.75f, ring) }
            else -> { p.color = color; c.drawCircle(x, y, r * 0.35f, p); spokes(c, x, y, r, 6, ring, true) }
        }
    }

    private fun spokes(c: Canvas, x: Float, y: Float, r: Float, n: Int, p: Paint, thick: Boolean) {
        p.strokeWidth = if (thick) r * 0.24f else r * 0.10f
        val rr = if (thick) r * 0.85f else r
        for (i in 0 until n) {
            val a = i * (2.0 * Math.PI / n).toFloat()
            c.drawLine(x, y, x + sin(a) * rr, y + cos(a) * rr, p)
        }
    }
}

// ------------------------- particles & npc -------------------------

class Particle(var x: Float, var y: Float, var vx: Float, var vy: Float,
               var life: Float, var maxLife: Float, var size: Float, var color: Int)

class Npc(val spec: CarSpec, var z: Float, var offset: Float, var base: Float) {
    var speed = 0f
    var total = 0f
    var wanderT = 0f
    var targetOffset = offset
}

// ------------------------- game view -------------------------

class GameView(context: Context) : View(context) {

    private companion object {
        const val SCR_MENU = 0
        const val SCR_TRACKS = 1
        const val SCR_GARAGE = 2
        const val SCR_UPGRADES = 3
        const val SCR_RACE = 4
        const val SCR_RESULTS = 5
    }

    private val save = Save(context)
    private val rng = Rng(20261028)
    private var w = 0f
    private var h = 0f
    private var screen = SCR_MENU
    private var lastT = 0L
    private var time = 0f

    // garage
    private var tab = 0
    private val previewSpec = CarSpec()

    // race
    private var track: Track? = null
    private var playerPos = 0f
    private var playerX = 0f
    private var speed = 0f
    private var lapCount = 0
    private var raceTime = 0L
    private var countdown = 0f
    private var nitro = 100f
    private var boosting = false
    private var finishT = -1f
    private var paused = false
    private var playerY = 0f
    private var skyOffset = 0f
    private var steerL = false
    private var steerR = false
    private var brakeHeld = false
    private var nitroHeld = false
    private var raceFinished = false
    private var resultPos = 1
    private var resultCoins = 0
    private var newRecord = false
    private val npcs = ArrayList<Npc>()
    private val particles = ArrayList<Particle>()
    private val snow = Array(90) { Particle(0f, 0f, 0f, 0f, 1f, 1f, 2f, 0xFFFFFFFF.toInt()) }

    // per-frame drawn segment cache
    private val segX = FloatArray(DRAW_DIST)
    private val segY = FloatArray(DRAW_DIST)
    private val segW = FloatArray(DRAW_DIST)
    private val segClip = FloatArray(DRAW_DIST)
    private val segFog = FloatArray(DRAW_DIST)
    private val segDrawn = BooleanArray(DRAW_DIST)
    private val npcAtSeg = HashMap<Int, Npc>()

    // scenery static layers
    private var mtFar = FloatArray(0)
    private var mtNear = FloatArray(0)
    private var stars = FloatArray(0)
    private var clouds = FloatArray(0)
    private var skyShader: Shader? = null
    private var skyShaderTheme = -1

    // track cache + minimap paths (real track geometry)
    private val trackCache = arrayOfNulls<Track>(THEMES.size)
    private val mmPath = Path()
    private var mmTrack: Track? = null
    private val selectPaths = arrayOfNulls<Path>(THEMES.size)
    private var flash = 0f   // lightning flash (storm theme)

    private fun trackFor(i: Int): Track {
        var t = trackCache[i]
        if (t == null) { t = TrackBuilder.build(i); trackCache[i] = t }
        return t!!
    }

    private fun circuitPath(i: Int): Path {
        var p = selectPaths[i]
        if (p == null) {
            p = Path()
            val tr = trackFor(i)
            for (k in 0 until tr.segments.size step 6) {
                if (k == 0) p.moveTo(tr.mapX[k], tr.mapY[k]) else p.lineTo(tr.mapX[k], tr.mapY[k])
            }
            p.lineTo(tr.mapX[0], tr.mapY[0])
            selectPaths[i] = p
        }
        return p!!
    }

    // ui
    private val uiButtons = ArrayList<Triple<String, RectF, Boolean>>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val font = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)

    init { save.load() }

    override fun onSizeChanged(nw: Int, nh: Int, ow: Int, oh: Int) {
        w = nw.toFloat(); h = nh.toFloat()
        val r = Rng(99)
        mtFar = FloatArray(26) { r.range(0.25f, 1f) }
        mtNear = FloatArray(20) { r.range(0.2f, 0.9f) }
        stars = FloatArray(160) { if (it % 2 == 0) r.next() * w else r.next() * h * 0.45f }
        clouds = FloatArray(15) { if (it % 3 == 0) r.next() * w else r.range(0.03f, 0.3f) * h }
        for (s in snow) {
            s.x = r.next() * w; s.y = r.next() * h
            s.vx = r.range(-70f, -30f); s.vy = r.range(40f, 110f); s.size = r.range(1.5f, 4f)
        }
        skyShaderTheme = -1
    }

    // ================= loop =================

    override fun onDraw(c: Canvas) {
        val now = System.nanoTime()
        var dt = (now - lastT) / 1e9f
        lastT = now
        if (dt <= 0f || dt > 0.05f) dt = if (dt <= 0f) 0.016f else 0.05f
        time += dt
        uiButtons.clear()

        when (screen) {
            SCR_MENU -> { drawMenuBg(c, 0); drawMenu(c) }
            SCR_TRACKS -> { drawMenuBg(c, save.track); drawTrackSelect(c) }
            SCR_GARAGE -> drawGarage(c)
            SCR_UPGRADES -> drawUpgrades(c)
            SCR_RACE -> { updateRace(dt); drawRace(c) }
            SCR_RESULTS -> { drawMenuBg(c, save.track); drawResults(c) }
        }
        postInvalidateOnAnimation()
    }

    // ================= input =================

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_MOVE -> {
                if (screen == SCR_RACE && !paused && !raceFinished) updateRaceTouches(e)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                handleTap(e.x, e.y)
                if (screen == SCR_RACE && !paused && !raceFinished) updateRaceTouches(e, true)
            }
        }
        return true
    }

    private fun updateRaceTouches(e: MotionEvent, up: Boolean = false) {
        steerL = false; steerR = false; brakeHeld = false; nitroHeld = false
        val skip = if (up) e.actionIndex else -1
        for (i in 0 until e.pointerCount) {
            if (i == skip) continue
            val x = e.getX(i); val y = e.getY(i)
            if (y > h * 0.55f) {
                if (x < w * 0.5f) { if (x < w * 0.125f) steerL = true else steerR = true }
                else if (x > w - w * 0.17f) brakeHeld = true
                else nitroHeld = true
            }
        }
    }

    private fun handleTap(x: Float, y: Float) {
        for (b in uiButtons) {
            if (b.third && b.second.contains(x, y)) { fire(b.first); return }
        }
    }

    private fun fire(id: String) {
        when {
            id == "menu_race" -> screen = SCR_TRACKS
            id == "menu_garage" -> { previewSpec.copyFrom(save.spec); screen = SCR_GARAGE }
            id == "menu_upg" -> screen = SCR_UPGRADES
            id == "menu_back" -> screen = SCR_MENU
            id == "track_go" -> startRace()
            id == "garage_save" -> { save.spec.copyFrom(previewSpec); save.save(); screen = SCR_MENU }
            id == "garage_race" -> { save.spec.copyFrom(previewSpec); save.save(); screen = SCR_TRACKS }
            id == "chassis_cycle" -> previewSpec.chassis = (previewSpec.chassis + 1) % 3
            id.startsWith("sel") -> save.track = id.removePrefix("sel").toInt()
            id.startsWith("tab") -> tab = id.removePrefix("tab").toInt()
            id.startsWith("paint") -> previewSpec.paint = id.removePrefix("paint").toInt()
            id.startsWith("finish") -> previewSpec.finish = id.removePrefix("finish").toInt()
            id.startsWith("rimc") -> previewSpec.rimColor = id.removePrefix("rimc").toInt()
            id.startsWith("rim") -> previewSpec.rim = id.removePrefix("rim").toInt()
            id.startsWith("chassis") -> previewSpec.chassis = id.removePrefix("chassis").toInt()
            id.startsWith("spoiler") -> previewSpec.spoiler = id.removePrefix("spoiler").toInt()
            id.startsWith("stripe") -> previewSpec.stripes = id.removePrefix("stripe").toInt()
            id.startsWith("glow") -> previewSpec.glow = id.removePrefix("glow").toInt()
            id == "buy_engine" -> buyUpg(save.upg.engine) { save.upg.engine++ }
            id == "buy_turbo" -> buyUpg(save.upg.turbo) { save.upg.turbo++ }
            id == "buy_tires" -> buyUpg(save.upg.tires) { save.upg.tires++ }
            id == "pause" -> paused = true
            id == "resume" -> paused = false
            id == "restart" -> startRace()
            id == "quit" -> { paused = false; screen = SCR_MENU }
            id == "results_menu" -> screen = SCR_MENU
            id == "results_garage" -> { previewSpec.copyFrom(save.spec); screen = SCR_GARAGE }
            id == "results_retry" -> startRace()
        }
    }

    private fun buyUpg(level: Int, apply: () -> Unit) {
        if (level >= 5) return
        val cost = Upg.cost(level)
        if (save.coins < cost) return
        save.coins -= cost
        apply()
        save.save()
    }

    // ================= race =================

    private fun startRace() {
        track = trackFor(save.track)
        playerPos = 0f; playerX = 0f; speed = 0f
        lapCount = 0; raceTime = 0L; countdown = 3.99f
        nitro = 100f; boosting = false; finishT = -1f; paused = false
        raceFinished = false; newRecord = false
        particles.clear()
        npcs.clear()
        for (i in 0 until N_RACERS - 1) {
            val s = CarSpec()
            s.chassis = rng.int(3); s.paint = rng.int(PAINTS.size)
            s.finish = rng.int(4); s.rim = rng.int(5); s.rimColor = rng.int(RIM_COLORS.size)
            s.spoiler = rng.int(3); s.stripes = rng.int(3)
            val diff = 0.78f + save.track * 0.028f
            val base = BASE_MAX_SPEED * rng.range(diff, diff + 0.12f)
            val n = Npc(s, (i + 1) * 260f + 140f, (i % 3 - 1) * 0.55f, base)
            n.total = n.z
            npcs.add(n)
        }
        screen = SCR_RACE
    }

    private fun playerMaxSpeed(): Float {
        var m = BASE_MAX_SPEED * (1f + save.upg.engine * 0.09f)
        if (boosting) m *= 1.35f
        return m
    }

    private fun updateRace(dt: Float) {
        val tr = track ?: return
        if (paused) return
        val segs = tr.segments
        val nSeg = segs.size
        val baseIdx = U.mod((playerPos / SEG_LEN).toInt(), nSeg)
        val playerSegIdx = U.mod(((playerPos + PLAYER_Z) / SEG_LEN).toInt(), nSeg)
        val playerSeg = segs[playerSegIdx]
        val maxS = playerMaxSpeed()

        if (countdown > 0f) {
            countdown -= dt
            speed = 0f
        } else if (!raceFinished) {
            raceTime += (dt * 1000).toLong()
            val accel = BASE_MAX_SPEED / 4.5f * save.accelStat() * (if (boosting) 2.2f else 1f)
            if (brakeHeld) speed -= BASE_MAX_SPEED * dt
            else speed += accel * dt
            if (nitroHeld && nitro >= 34f && !boosting) { boosting = true; nitro -= 34f }
            if (boosting) {
                nitro -= 14f * dt
                if (nitro <= 0f) { nitro = 0f; boosting = false }
            } else {
                nitro = min(100f, nitro + 2.5f * dt)
            }

            val speedPct = U.clamp(speed / BASE_MAX_SPEED, 0f, 1.4f)
            val steerDir = (if (steerR) 1f else 0f) - (if (steerL) 1f else 0f)
            val grip = save.gripStat()
            playerX += steerDir * dt * 2.0f * U.clamp(speedPct, 0f, 1f) * grip
            playerX -= playerSeg.curve * dt * speedPct * 0.062f * (2f - grip * 0.7f)

            if (abs(playerX) > 1.05f) {
                if (speed > maxS * 0.35f) speed -= BASE_MAX_SPEED * 0.9f * dt
                if (speedPct > 0.4f && rng.next() < 0.5f) emitDust(playerX)
            }
            if (speed > maxS) speed += (maxS - speed) * min(1f, dt * 2f)
            if (speed < 0f) speed = 0f
            playerX = U.clamp(playerX, -2.4f, 2.4f)

            // drift smoke on hard corners
            if (steerDir != 0f && speedPct > 0.55f && abs(playerSeg.curve) > 1.4f && rng.next() < 0.6f) {
                nitro = min(100f, nitro + 8f * dt)
                emitSmoke()
            }

            playerPos += speed * dt
            while (playerPos >= tr.length) { playerPos -= tr.length; lapCount++ }
            if (lapCount >= LAPS && finishT < 0f) {
                finishT = 0f
                raceFinished = true
                finishRace(tr)
            }
        } else {
            finishT += dt
            speed = maxOf(0f, speed - BASE_MAX_SPEED * 0.4f * dt)
            playerPos += speed * dt
            while (playerPos >= tr.length) playerPos -= tr.length
            if (finishT > 2.2f) screen = SCR_RESULTS
        }

        skyOffset = U.wrap(skyOffset + playerSeg.curve * (speed / BASE_MAX_SPEED) * dt * 46f, w * 8f)

        val pPct = ((playerPos + PLAYER_Z) % SEG_LEN) / SEG_LEN
        playerY = U.lerp(playerSeg.p1.world.y, playerSeg.p2.world.y, pPct)

        // NPCs
        npcAtSeg.clear()
        for (npc in npcs) {
            if (countdown <= 0f) {
                val band = U.clamp((playerPos - npc.z) / tr.length, -1f, 1f)
                val target = npc.base * (1f + band * 0.10f)
                npc.speed += (target - npc.speed) * U.clamp(dt * 2f, 0f, 1f)
                if (raceFinished) npc.speed *= 0.995f
                npc.z += npc.speed * dt
                npc.total += npc.speed * dt
                while (npc.z >= tr.length) npc.z -= tr.length
                npc.wanderT += dt
                if (npc.wanderT > 2f) {
                    npc.wanderT = 0f
                    npc.targetOffset = rng.range(-0.6f, 0.6f)
                }
                npc.offset += (npc.targetOffset - npc.offset) * U.clamp(dt * 1.2f, 0f, 1f)
            }
            val rel = U.mod((npc.z / SEG_LEN).toInt() - baseIdx, nSeg)
            npcAtSeg[rel] = npc
        }

        // collisions
        if (countdown <= 0f && !raceFinished) {
            for (npc in npcs) {
                var dz = npc.z - (playerPos + PLAYER_Z)
                if (dz > tr.length / 2f) dz -= tr.length
                if (dz < -tr.length / 2f) dz += tr.length
                if (abs(dz) < 260f && abs(npc.offset - playerX) < 0.45f) {
                    speed = min(speed, npc.speed * 0.8f)
                    playerX += if (playerX < npc.offset) -0.16f else 0.16f
                    npc.speed *= 0.97f
                }
            }
        }

        // particles
        val it = particles.iterator()
        while (it.hasNext()) {
            val pt = it.next()
            pt.life -= dt
            if (pt.life <= 0f) it.remove()
            else { pt.x += pt.vx * dt; pt.y += pt.vy * dt; pt.size += 26f * dt }
        }
        if (tr.theme.snow) {
            for (s in snow) {
                s.x += s.vx * dt
                s.y += s.vy * dt * (0.4f + speed / BASE_MAX_SPEED)
                if (s.y > h) { s.y = -10f; s.x = rng.next() * w }
                if (s.x < -10f) s.x = w
            }
        }
        if (tr.theme.rain) {
            for (s in snow) {
                s.x += s.vx * dt * 1.6f
                s.y += s.vy * dt * (2.2f + speed / BASE_MAX_SPEED * 2f)
                if (s.y > h) { s.y = -20f; s.x = rng.next() * w }
                if (s.x < -10f) s.x = w
            }
        }
        if (tr.theme.lightning) {
            if (flash > 0f) flash -= dt
            else if (rng.next() < dt * 0.06f) flash = 0.45f
        }
    }

    private fun finishRace(tr: Track) {
        val playerTotal = lapCount * tr.length + playerPos
        var pos = 1
        for (npc in npcs) if (npc.total > playerTotal) pos++
        resultPos = pos
        val rewards = intArrayOf(500, 350, 250, 180, 120, 80)
        resultCoins = rewards[U.clamp((pos - 1).toFloat(), 0f, 5f).toInt()]
        save.coins += resultCoins
        if (save.best[save.track] < 0 || raceTime < save.best[save.track]) {
            save.best[save.track] = raceTime
            newRecord = true
        }
        save.save()
    }

    private fun emitSmoke() {
        val col = if (track?.theme?.snow == true) 0xFFFFFFFF.toInt() else 0xFFD7CCC8.toInt()
        for (side in floatArrayOf(-1f, 1f)) {
            particles.add(Particle(
                w / 2f + side * w * 0.09f, h * 0.92f,
                side * rng.range(10f, 50f) + rng.range(-15f, 15f), rng.range(-70f, -20f),
                rng.range(0.3f, 0.8f), 0.8f, rng.range(4f, 9f), U.withA(col, 130)))
        }
    }

    private fun emitDust(xDir: Float) {
        particles.add(Particle(
            w / 2f + xDir * w * 0.02f, h * 0.91f,
            rng.range(-40f, 40f), rng.range(-90f, -30f),
            rng.range(0.3f, 0.7f), 0.7f, rng.range(3f, 8f),
            U.withA(0xFF8D6E63.toInt(), 120)))
    }

    // ================= projection & road =================

    private fun project(p: ProjPoint, camX: Float, camY: Float, camZ: Float) {
        p.cam.x = p.world.x - camX
        p.cam.y = p.world.y - camY
        p.cam.z = p.world.z - camZ
        if (p.cam.z == 0f) p.cam.z = 0.01f
        p.scale = CAM_DEPTH / p.cam.z
        p.sx = w / 2f + p.scale * p.cam.x * w / 2f
        p.sy = h * 0.5f - p.scale * p.cam.y * h * 2f * 0.5f * 1.2f
        p.sw = p.scale * ROAD_W * w / 2f
    }

    private fun poly(c: Canvas, x1: Float, y1: Float, x2: Float, y2: Float,
                     x3: Float, y3: Float, x4: Float, y4: Float, p: Paint) {
        path.reset()
        path.moveTo(x1, y1); path.lineTo(x2, y2); path.lineTo(x3, y3); path.lineTo(x4, y4)
        path.close()
        c.drawPath(path, p)
    }

    private fun ensureSky(themeIdx: Int) {
        if (skyShaderTheme != themeIdx) {
            skyShaderTheme = themeIdx
            val th = THEMES[themeIdx]
            skyShader = LinearGradient(0f, 0f, 0f, h * 0.62f, th.skyTop, th.skyBot, Shader.TileMode.CLAMP)
        }
    }

    private fun drawRace(c: Canvas) {
        val tr = track ?: return
        val theme = tr.theme
        val segs = tr.segments
        val nSeg = segs.size
        val baseIdx = U.mod((playerPos / SEG_LEN).toInt(), nSeg)
        val basePct = (playerPos % SEG_LEN) / SEG_LEN
        val baseSeg = segs[baseIdx]

        ensureSky(tr.themeIdx)
        paint.shader = skyShader
        c.drawRect(0f, 0f, w, h * 0.62f, paint)
        paint.shader = null

        drawSky(c, theme)
        drawMountains(c, theme)

        var maxy = h
        var x = 0f
        var dx = -(baseSeg.curve * basePct)
        val camY = playerY + CAM_H

        val grassP = Paint()
        val roadP = Paint()
        val rumP = Paint()
        val laneP = Paint()
        val fogP = Paint()
        val edgeP = Paint()
        val startP = Paint(Paint.ANTI_ALIAS_FLAG)

        for (i in 0 until DRAW_DIST) {
            val seg = segs[U.mod(baseIdx + i, nSeg)]
            val looped = seg.index < baseIdx
            val camZ = playerPos - (if (looped) tr.length else 0f)
            project(seg.p1, playerX * ROAD_W - x, camY, camZ)
            project(seg.p2, playerX * ROAD_W - x - dx, camY, camZ)
            val f = exp(-((i.toFloat() / DRAW_DIST) * (i.toFloat() / DRAW_DIST)) * 4.5f)
            seg.clip = maxy
            x += dx
            dx += seg.curve

            if (seg.p1.cam.z <= CAM_DEPTH || seg.p2.sy >= maxy || seg.p2.sy >= seg.p1.sy) {
                segDrawn[i] = false
                continue
            }
            val p1 = seg.p1; val p2 = seg.p2
            val alt = (seg.index / RUMBLE_LEN) % 2 == 0
            segFog[i] = f

            grassP.color = if (alt) theme.grass1 else theme.grass2
            c.drawRect(0f, p2.sy, w, p1.sy + 1f, grassP)
            rumP.color = if (alt) theme.rumble1 else theme.rumble2
            val r1 = p1.sw / 6f; val r2 = p2.sw / 6f
            poly(c, p1.sx - p1.sw - r1, p1.sy, p1.sx - p1.sw, p1.sy, p2.sx - p2.sw, p2.sy, p2.sx - p2.sw - r2, p2.sy, rumP)
            poly(c, p1.sx + p1.sw + r1, p1.sy, p1.sx + p1.sw, p1.sy, p2.sx + p2.sw, p2.sy, p2.sx + p2.sw + r2, p2.sy, rumP)
            roadP.color = if (alt) theme.road1 else theme.road2
            poly(c, p1.sx - p1.sw, p1.sy, p1.sx + p1.sw, p1.sy, p2.sx + p2.sw, p2.sy, p2.sx - p2.sw, p2.sy, roadP)
            if (theme.night) { // glowing edge markers
                edgeP.color = U.withA(theme.accent, if (alt) 140 else 85)
                val e1 = p1.sw * 0.022f
                val e2 = p2.sw * 0.022f
                poly(c, p1.sx - p1.sw, p1.sy, p1.sx - p1.sw + e1, p1.sy, p2.sx - p2.sw + e2, p2.sy, p2.sx - p2.sw, p2.sy, edgeP)
                poly(c, p1.sx + p1.sw - e1, p1.sy, p1.sx + p1.sw, p1.sy, p2.sx + p2.sw, p2.sy, p2.sx + p2.sw - e2, p2.sy, edgeP)
            }
            if (seg.index == 1) {
                startP.color = 0xFFEEEEEE.toInt()
                poly(c, p1.sx - p1.sw, p1.sy, p1.sx + p1.sw, p1.sy, p2.sx + p2.sw, p2.sy, p2.sx - p2.sw, p2.sy, startP)
            } else if (seg.index == 2) {
                startP.color = 0xFF222222.toInt()
                poly(c, p1.sx - p1.sw, p1.sy, p1.sx + p1.sw, p1.sy, p2.sx + p2.sw, p2.sy, p2.sx - p2.sw, p2.sy, startP)
            }
            if (alt) {
                laneP.color = theme.lane
                val lw1 = p1.sw / 34f; val lw2 = p2.sw / 34f
                for (ln in 1 until 3) {
                    val la = -1f + ln * (2f / 3f)
                    val lx1 = p1.sx + p1.sw * la; val lx2 = p2.sx + p2.sw * la
                    poly(c, lx1 - lw1, p1.sy, lx1 + lw1, p1.sy, lx2 + lw2, p2.sy, lx2 - lw2, p2.sy, laneP)
                }
            }
            if (f < 1f) {
                fogP.color = U.withA(theme.fog, ((1f - f) * 255).toInt())
                c.drawRect(0f, p2.sy, w, p1.sy + 1f, fogP)
            }

            segX[i] = p1.sx; segY[i] = p1.sy; segW[i] = p1.sw; segClip[i] = seg.clip
            segDrawn[i] = true
            maxy = p2.sy
        }

        // scenery & NPC cars, far -> near
        for (i in DRAW_DIST - 1 downTo 2) {
            if (!segDrawn[i]) continue
            val seg = segs[U.mod(baseIdx + i, nSeg)]
            val a = (segFog[i] * 255).toInt()
            for (sp in seg.sprites) {
                val sx = segX[i] + segW[i] * sp.offset
                val sc = segW[i] * 0.15f * sp.scale
                if (segY[i] < segClip[i] + 4f) drawScenery(c, sp.type, sx, segY[i], sc, a, theme, sp.offset)
            }
            val npc = npcAtSeg[i]
            if (npc != null) {
                val t = (npc.z % SEG_LEN) / SEG_LEN
                val hasNext = i + 1 < DRAW_DIST && segDrawn[i + 1]
                val cx2 = if (hasNext) U.lerp(segX[i], segX[i + 1], t) else segX[i]
                val cy2 = if (hasNext) U.lerp(segY[i], segY[i + 1], t) else segY[i]
                val cw = if (hasNext) U.lerp(segW[i], segW[i + 1], t) else segW[i]
                if (cy2 < segClip[i] + 2f) {
                    CarArt.draw(c, npc.spec, cx2 + cw * npc.offset, cy2,
                        cw * 0.0032f, 0f, 0f, false, false, theme.night, time, true)
                }
            }
        }

        if (theme.snow) {
            val sp = Paint(Paint.ANTI_ALIAS_FLAG)
            sp.color = U.withA(0xFFFFFFFF.toInt(), 150)
            for (s in snow) c.drawCircle(s.x, s.y, s.size, sp)
        }
        if (theme.rain) {
            val rp = Paint(Paint.ANTI_ALIAS_FLAG)
            rp.color = U.withA(0xFF9FC9E8.toInt(), 110)
            rp.strokeWidth = 2.2f
            for (s in snow) c.drawLine(s.x, s.y, s.x + 1.5f, s.y + s.size * 6f, rp)
        }

        for (pt in particles) {
            paint.color = U.withA(pt.color, (pt.life / pt.maxLife * 200).toInt())
            c.drawCircle(pt.x, pt.y, pt.size, paint)
        }

        // player car
        val steer = (if (steerR) 1f else 0f) - (if (steerL) 1f else 0f)
        val bounce = abs(sin(time * 34f)) * (speed / BASE_MAX_SPEED) * 2.2f
        val offroad = abs(playerX) > 1.05f && speed > BASE_MAX_SPEED * 0.3f
        val shake = if (offroad) (rng.next() - 0.5f) * (speed / BASE_MAX_SPEED) * 8f else 0f
        c.save()
        c.translate(shake, shake * 0.6f)
        CarArt.draw(c, save.spec, w / 2f, h - h * 0.055f, w * 0.0021f,
            steer, bounce, boosting, brakeHeld, theme.night, time, true)
        c.restore()

        if (boosting) {
            val lp = Paint(Paint.ANTI_ALIAS_FLAG)
            lp.color = U.withA(0xFFFFFFFF.toInt(), 80)
            lp.strokeWidth = 2f
            for (i in 0 until 14) {
                val ang = rng.next() * 6.2831855f
                val r0 = w * 0.42f
                val x0 = w / 2f + sin(ang) * r0
                val y0 = h * 0.55f + cos(ang) * r0 * 0.7f
                c.drawLine(x0, y0, w / 2f + sin(ang) * r0 * 0.72f, h * 0.55f + cos(ang) * r0 * 0.52f, lp)
            }
        }

        if (theme.lightning && flash > 0f) {
            val fa = (U.clamp(flash, 0f, 0.45f) / 0.45f * 220f).toInt()
            paint.reset()
            paint.isAntiAlias = true
            paint.color = U.withA(0xFFCFE8FF.toInt(), fa)
            c.drawRect(0f, 0f, w, h, paint)
        }

        drawHud(c)
        if (countdown > 0f) drawCountdown(c)
        if (paused) drawPause(c)
    }

    // ================= scenery =================

    private fun drawScenery(c: Canvas, type: Int, x: Float, y: Float, s: Float, alpha: Int, theme: Theme, offsetSign: Float = 0f) {
        if (s < 0.4f) return
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        when (type) {
            0 -> { // palm
                p.color = U.withA(0xFF6D4C41.toInt(), alpha)
                path.reset()
                path.moveTo(x - s * 0.06f, y)
                path.quadTo(x + s * 0.18f, y - s * 1.1f, x + s * 0.06f, y - s * 2.0f)
                path.lineTo(x + s * 0.16f, y - s * 2.0f)
                path.quadTo(x + s * 0.28f, y - s * 1.1f, x + s * 0.14f, y)
                path.close()
                c.drawPath(path, p)
                for (i in 0 until 6) {
                    val a = -1.5707964f + (i - 2.5f) * 0.5f
                    p.color = U.withA(0xFF2E7D32.toInt(), alpha)
                    c.save()
                    c.translate(x + s * 0.1f, y - s * 2.0f)
                    c.rotate(a * 57.29578f + 90f)
                    c.drawOval(RectF(-s * 0.5f, -s * 0.14f, s * 0.5f, s * 0.14f), p)
                    c.restore()
                }
            }
            1 -> { // rock
                p.color = U.withA(0xFF78909C.toInt(), alpha)
                path.reset()
                path.moveTo(x - s * 0.4f, y)
                path.lineTo(x - s * 0.25f, y - s * 0.45f)
                path.lineTo(x + s * 0.05f, y - s * 0.55f)
                path.lineTo(x + s * 0.35f, y - s * 0.25f)
                path.lineTo(x + s * 0.4f, y)
                path.close()
                c.drawPath(path, p)
                p.color = U.withA(0xFF546E7A.toInt(), alpha)
                path.reset()
                path.moveTo(x + s * 0.05f, y - s * 0.55f)
                path.lineTo(x + s * 0.35f, y - s * 0.25f)
                path.lineTo(x + s * 0.4f, y)
                path.lineTo(x + s * 0.15f, y)
                path.close()
                c.drawPath(path, p)
            }
            2 -> { // billboard
                p.color = U.withA(0xFF455A64.toInt(), alpha)
                c.drawRect(RectF(x - s * 0.55f, y - s * 1.5f, x - s * 0.5f, y), p)
                c.drawRect(RectF(x + s * 0.5f, y - s * 1.5f, x + s * 0.55f, y), p)
                p.shader = LinearGradient(x - s, y - s * 1.6f, x + s, y - s * 0.6f,
                    0xFF212121.toInt(), theme.accent, Shader.TileMode.CLAMP)
                c.drawRect(RectF(x - s, y - s * 1.6f, x + s, y - s * 0.6f), p)
                p.shader = null
                p.color = U.withA(0xFFFFFFFF.toInt(), alpha)
                p.textSize = s * 0.3f
                p.typeface = font
                p.textAlign = Paint.Align.CENTER
                c.drawText("APEX", x, y - s * 1.0f, p)
            }
            3 -> { // building
                val bh = s * 3.2f
                val bw = s * 1.5f
                p.color = U.withA(0xFF101020.toInt(), alpha)
                c.drawRect(RectF(x - bw, y - bh, x + bw, y), p)
                p.color = U.withA(0xFFFFD54F.toInt(), alpha)
                for (gy in 0 until 8) {
                    for (gx in 0 until 4) {
                        if ((gy * 7 + gx * 13 + (x.toInt() % 11)) % 5 < 2) {
                            c.drawRect(RectF(
                                x - bw + bw * 0.25f + gx * bw * 0.45f,
                                y - bh + bh * 0.08f + gy * bh * 0.115f,
                                x - bw + bw * 0.55f + gx * bw * 0.45f,
                                y - bh + bh * 0.14f + gy * bh * 0.115f), p)
                        }
                    }
                }
                p.color = U.withA(0xFFFF1744.toInt(), alpha)
                c.drawCircle(x, y - bh, s * 0.08f, p)
            }
            4 -> { // streetlight
                p.color = U.withA(0xFF37474F.toInt(), alpha)
                p.strokeWidth = s * 0.08f
                val side = if (x < w / 2f) -1f else 1f
                c.drawLine(x, y, x, y - s * 1.8f, p)
                c.drawLine(x, y - s * 1.8f, x - s * 0.5f * side, y - s * 1.8f, p)
                val hx = x - s * 0.5f * side
                p.color = U.withA(0xFFFFECB3.toInt(), alpha)
                c.drawCircle(hx, y - s * 1.75f, s * 0.1f, p)
                val gp = Paint(Paint.ANTI_ALIAS_FLAG)
                gp.shader = RadialGradient(hx, y - s * 1.75f, s * 0.6f,
                    U.withA(0xFFFFECB3.toInt(), alpha / 3), U.withA(0xFFFFECB3.toInt(), 0), Shader.TileMode.CLAMP)
                c.drawCircle(hx, y - s * 1.75f, s * 0.6f, gp)
            }
            6 -> { // saguaro cactus
                val tw = s * 0.14f
                p.color = U.withA(0xFF388E3C.toInt(), alpha)
                c.drawRoundRect(RectF(x - tw, y - s * 1.7f, x + tw, y), tw, tw, p)
                p.color = U.withA(0xFF2E7D32.toInt(), alpha)
                // left arm
                c.drawRoundRect(RectF(x - s * 0.5f, y - s * 1.0f, x - tw, y - s * 0.86f), tw, tw, p)
                c.drawRoundRect(RectF(x - s * 0.5f, y - s * 1.35f, x - s * 0.36f, y - s * 1.0f), tw, tw, p)
                // right arm
                c.drawRoundRect(RectF(x + tw, y - s * 0.8f, x + s * 0.5f, y - s * 0.66f), tw, tw, p)
                c.drawRoundRect(RectF(x + s * 0.36f, y - s * 1.5f, x + s * 0.5f, y - s * 0.8f), tw, tw, p)
            }
            7 -> { // overhead gantry spanning the road
                val pw = s * 0.07f
                val ph = s * 0.62f
                p.color = U.withA(0xFF37474F.toInt(), alpha)
                c.drawRect(RectF(x - s, y - ph, x - s + pw, y), p)
                c.drawRect(RectF(x + s - pw, y - ph, x + s, y), p)
                val bh = s * 0.13f
                val cw2 = s * 2f / 12f
                for (k in 0 until 12) { // checkered beam
                    p.color = U.withA(if (k % 2 == 0) 0xFFEEEEEE.toInt() else 0xFF212121.toInt(), alpha)
                    c.drawRect(RectF(x - s + k * cw2, y - ph, x - s + (k + 1) * cw2, y - ph + bh), p)
                }
                p.color = U.withA(theme.accent, alpha)
                c.drawRect(RectF(x - s * 0.6f, y - ph + bh * 1.2f, x + s * 0.6f, y - ph + bh * 2.1f), p)
                p.color = U.withA(0xFFFFFFFF.toInt(), alpha)
                p.textSize = s * 0.10f
                p.typeface = font
                p.textAlign = Paint.Align.CENTER
                c.drawText("APEX RUSH", x, y - ph + bh * 1.8f, p)
            }
            8 -> { // chevron corner board
                val bw2 = s * 0.9f
                val bh2 = s * 0.5f
                val cy0 = y - s * 1.0f
                p.color = U.withA(0xFF0A0A0C.toInt(), alpha)
                c.drawRoundRect(RectF(x - bw2, cy0 - bh2, x + bw2, cy0 + bh2), s * 0.07f, s * 0.07f, p)
                p.color = U.withA(0xFFFFC107.toInt(), alpha)
                p.style = Paint.Style.STROKE
                p.strokeWidth = s * 0.13f
                p.strokeCap = Paint.Cap.ROUND
                val dir = if (offsetSign < 0f) 1f else -1f
                for (k in 0 until 3) {
                    val ax = x - bw2 * 0.55f + k * bw2 * 0.55f
                    path.reset()
                    path.moveTo(ax - dir * s * 0.16f, cy0 - bh2 * 0.5f)
                    path.lineTo(ax + dir * s * 0.16f, cy0)
                    path.lineTo(ax - dir * s * 0.16f, cy0 + bh2 * 0.5f)
                    c.drawPath(path, p)
                }
                p.style = Paint.Style.FILL
            }
            else -> { // pine
                p.color = U.withA(0xFF5D4037.toInt(), alpha)
                c.drawRect(RectF(x - s * 0.05f, y - s * 0.3f, x + s * 0.05f, y), p)
                for (l in 0 until 3) {
                    p.color = U.withA(if (theme.snow) 0xFF1B5E20.toInt() else 0xFF2E7D32.toInt(), alpha)
                    val ly = y - s * (0.35f + l * 0.5f)
                    val lw = s * (0.5f - l * 0.12f)
                    path.reset()
                    path.moveTo(x - lw, ly)
                    path.lineTo(x, ly - s * 0.6f)
                    path.lineTo(x + lw, ly)
                    path.close()
                    c.drawPath(path, p)
                    if (theme.snow) {
                        p.color = U.withA(0xFFFFFFFF.toInt(), alpha)
                        path.reset()
                        path.moveTo(x - lw * 0.4f, ly - s * 0.36f)
                        path.lineTo(x, ly - s * 0.6f)
                        path.lineTo(x + lw * 0.4f, ly - s * 0.36f)
                        path.close()
                        c.drawPath(path, p)
                    }
                }
            }
        }
    }

    // ================= sky =================

    private fun drawSky(c: Canvas, theme: Theme) {
        if (theme.night) {
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            for (i in 0 until 80) {
                val sx = stars[i * 2]
                val sy = stars[i * 2 + 1]
                p.color = U.withA(0xFFFFFFFF.toInt(), (90 + 130 * abs(sin(time * 0.8f + i))).toInt())
                c.drawCircle(sx, sy, 1.4f, p)
            }
        } else {
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            p.color = U.withA(0xFFFFFFFF.toInt(), 110)
            for (i in 0 until 5) {
                val cx = U.wrap(clouds[i * 3] + time * 6f + skyOffset * 0.05f, w + 200f) - 100f
                val cy = clouds[i * 3 + 1]
                val cs = w * 0.03f + i * w * 0.008f
                c.drawOval(RectF(cx - cs, cy - cs * 0.35f, cx + cs, cy + cs * 0.35f), p)
                c.drawOval(RectF(cx - cs * 0.5f, cy - cs * 0.6f, cx + cs * 0.4f, cy + cs * 0.2f), p)
            }
        }
        val sx = theme.sunX * w - skyOffset * 0.04f
        val sy = theme.sunY * h * 0.6f
        val sr = theme.sunR * w
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = RadialGradient(sx, sy, sr * 2.6f, U.withA(theme.sunColor, 130),
            U.withA(theme.sunColor, 0), Shader.TileMode.CLAMP)
        c.drawCircle(sx, sy, sr * 2.6f, p)
        p.shader = RadialGradient(sx, sy, sr, U.lighten(theme.sunColor, 0.4f), theme.sunColor, Shader.TileMode.CLAMP)
        c.drawCircle(sx, sy, sr, p)
        if (theme.night) {
            p.shader = null
            p.color = U.withA(0xFF9FA8B5.toInt(), 120)
            c.drawCircle(sx - sr * 0.3f, sy - sr * 0.2f, sr * 0.16f, p)
            c.drawCircle(sx + sr * 0.25f, sy + sr * 0.3f, sr * 0.11f, p)
        }
    }

    private fun drawMountains(c: Canvas, theme: Theme) {
        val hy = h * 0.62f
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        if (theme.city) {
            val off = U.wrap(skyOffset * 0.25f, w)
            val bw = w / 16f
            val lit = Paint(Paint.ANTI_ALIAS_FLAG)
            for (i in -1 until 18) {
                val bh = h * (0.10f + mtNear[U.mod(i, mtNear.size)] * 0.22f)
                val bx = i * bw - off
                p.color = theme.mtNear
                c.drawRect(bx, hy - bh, bx + bw * 0.92f, hy, p)
                lit.color = U.withA(0xFFFFD54F.toInt(), 170)
                for (gy in 0 until (bh / 26f).toInt()) {
                    if ((i * 31 + gy * 17) % 4 < 2) {
                        c.drawRect(RectF(bx + bw * 0.15f, hy - bh + 10f + gy * 24f,
                            bx + bw * 0.35f, hy - bh + 20f + gy * 24f), lit)
                    }
                }
            }
            lit.color = U.withA(0xFFF50057.toInt(), 200)
            lit.strokeWidth = 4f
            for (i in 0 until 18) {
                val bx = i * bw - off
                if (mtNear[U.mod(i, mtNear.size)] > 0.7f) {
                    c.drawPoint(bx + bw * 0.46f, hy - h * (0.10f + mtNear[U.mod(i, mtNear.size)] * 0.22f) - 4f, lit)
                }
            }
        } else {
            for (layer in 0 until 2) {
                val peaks = if (layer == 0) mtFar else mtNear
                p.color = if (layer == 0) theme.mtFar else theme.mtNear
                val speed = if (layer == 0) 0.12f else 0.22f
                val amp = if (layer == 0) 0.14f else 0.10f
                val step = w / (peaks.size - 1)
                val off = U.wrap(skyOffset * speed, step)
                path.reset()
                path.moveTo(-step, hy)
                for (i in 0 until peaks.size + 1) {
                    path.lineTo(i * step - off, hy - h * amp * peaks[U.mod(i, peaks.size)])
                }
                path.lineTo(w + step, hy)
                path.close()
                c.drawPath(path, p)
            }
            if (theme.snow) {
                p.color = U.withA(0xFFFFFFFF.toInt(), 170)
                val step = w / (mtNear.size - 1)
                val off = U.wrap(skyOffset * 0.22f, step)
                for (i in 0 until mtNear.size) {
                    if (mtNear[i] > 0.62f) {
                        val px = i * step - off
                        val py = hy - h * 0.10f * mtNear[i]
                        path.reset()
                        path.moveTo(px, py)
                        path.lineTo(px + step * 0.10f, py + h * 0.02f)
                        path.lineTo(px - step * 0.10f, py + h * 0.02f)
                        path.close()
                        c.drawPath(path, p)
                    }
                }
            }
        }
    }

    // ================= HUD & UI helpers =================

    private fun text(c: Canvas, s: String, x: Float, y: Float, size: Float, color: Int, alpha: Int = 255) {
        paint.reset()
        paint.isAntiAlias = true
        paint.color = U.withA(color, alpha)
        paint.textSize = size
        paint.typeface = font
        paint.textAlign = Paint.Align.CENTER
        val fm = paint.fontMetrics
        c.drawText(s, x, y - (fm.ascent + fm.descent) / 2f, paint)
    }

    private fun button(c: Canvas, id: String, r: RectF, label: String, enabled: Boolean = true, accent: Boolean = false) {
        paint.reset()
        paint.isAntiAlias = true
        paint.shader = if (accent) LinearGradient(r.left, r.top, r.left, r.bottom,
            U.lighten(0xFFFF6D00.toInt(), 0.10f), U.darken(0xFFE65100.toInt(), 0.10f), Shader.TileMode.CLAMP)
        else LinearGradient(r.left, r.top, r.left, r.bottom,
            U.withA(0xFFFFFFFF.toInt(), if (enabled) 38 else 16),
            U.withA(0xFF000000.toInt(), if (enabled) 70 else 90), Shader.TileMode.CLAMP)
        c.drawRoundRect(r, r.height() / 5f, r.height() / 5f, paint)
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f
        paint.color = if (accent) U.withA(0xFFFFB74D.toInt(), 200) else U.withA(0xFFFFFFFF.toInt(), if (enabled) 90 else 30)
        c.drawRoundRect(r, r.height() / 5f, r.height() / 5f, paint)
        text(c, label, r.centerX(), r.centerY(), r.height() * 0.40f,
            if (accent) 0xFFFFFFFF.toInt() else if (enabled) 0xFFEEEEEE.toInt() else 0xFF777777.toInt())
        uiButtons.add(Triple(id, RectF(r), enabled))
    }

    private fun ordinal(p: Int): String = when (p) {
        1 -> "1ST"; 2 -> "2ND"; 3 -> "3RD"; else -> "${p}TH"
    }

    private fun livePosition(tr: Track): Int {
        val playerTotal = lapCount * tr.length + playerPos
        var pos = 1
        for (npc in npcs) if (npc.total > playerTotal) pos++
        return pos
    }

    private fun drawHud(c: Canvas) {
        val tr = track ?: return
        // position
        text(c, ordinal(livePosition(tr)), w * 0.07f, h * 0.06f, w * 0.045f, 0xFFFFFFFF.toInt())
        text(c, "/ $N_RACERS", w * 0.07f, h * 0.115f, w * 0.018f, 0xFFBDBDBD.toInt())
        // speed
        val kmh = (U.clamp(speed / BASE_MAX_SPEED, 0f, 1.4f) * save.topSpeedKmh() /
                (1f + save.upg.engine * 0.09f)).toInt()
        text(c, kmh.toString(), w * 0.5f, h * 0.055f, w * 0.058f, 0xFFFFFFFF.toInt())
        text(c, "km/h", w * 0.5f, h * 0.105f, w * 0.020f, 0xFFBDBDBD.toInt())
        // lap + times
        text(c, "LAP ${min(lapCount + 1, LAPS)}/$LAPS", w * 0.72f, h * 0.05f, w * 0.026f, 0xFFE0E0E0.toInt())
        text(c, U.fmtTime(raceTime), w * 0.72f, h * 0.095f, w * 0.022f, 0xFFBDBDBD.toInt())
        if (save.best[save.track] > 0) text(c, "BEST " + U.fmtTime(save.best[save.track]), w * 0.72f, h * 0.135f, w * 0.016f, 0xFF9E9E9E.toInt())
        // nitro bar
        val nbw = w * 0.15f
        val nx = w * 0.72f
        paint.reset(); paint.isAntiAlias = true
        paint.color = U.withA(0xFF00E5FF.toInt(), 60)
        c.drawRoundRect(RectF(nx - nbw / 2f, h * 0.16f, nx + nbw / 2f, h * 0.16f + h * 0.012f), 4f, 4f, paint)
        paint.color = U.withA(0xFF00E5FF.toInt(), 235)
        val np = U.clamp(nitro / 100f, 0f, 1f)
        c.drawRoundRect(RectF(nx - nbw / 2f, h * 0.16f, nx - nbw / 2f + nbw * np, h * 0.16f + h * 0.012f), 4f, 4f, paint)
        // pause
        val pad = w * 0.025f
        button(c, "pause", RectF(w - pad - w * 0.06f, pad, w - pad, pad + w * 0.06f), "II")
        drawMinimap(c, tr)
        drawTouchControls(c)
    }

    // live circuit map — the real track geometry with player + rival dots
    private fun drawMinimap(c: Canvas, tr: Track) {
        if (mmTrack !== tr) {
            mmTrack = tr
            mmPath.reset()
            for (i in 0 until tr.segments.size step 4) {
                if (i == 0) mmPath.moveTo(tr.mapX[i], tr.mapY[i]) else mmPath.lineTo(tr.mapX[i], tr.mapY[i])
            }
            mmPath.lineTo(tr.mapX[0], tr.mapY[0])
        }
        val ms = min(w * 0.14f, h * 0.24f)
        val mx = w - w * 0.025f - ms
        val my = w * 0.09f
        paint.reset()
        paint.isAntiAlias = true
        paint.color = U.withA(0xFF05070C.toInt(), 120)
        c.drawRoundRect(RectF(mx - ms * 0.05f, my - ms * 0.05f, mx + ms + ms * 0.05f, my + ms + ms * 0.05f), 10f, 10f, paint)
        c.save()
        c.translate(mx + ms * 0.10f, my + ms * 0.10f)
        c.scale(ms * 0.80f, ms * 0.80f)
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = 0.052f
        paint.color = U.withA(0xFF37474F.toInt(), 255)
        c.drawPath(mmPath, paint)
        paint.strokeWidth = 0.026f
        paint.color = U.withA(0xFFCFD8DC.toInt(), 225)
        c.drawPath(mmPath, paint)
        paint.style = Paint.Style.FILL
        paint.color = 0xFFFFD54F.toInt()   // start / finish
        c.drawCircle(tr.mapX[0], tr.mapY[0], 0.028f, paint)
        paint.color = U.withA(0xFF64B5F6.toInt(), 230)   // rivals
        for (npc in npcs) {
            val ni = U.mod((npc.z / SEG_LEN).toInt(), tr.segments.size)
            c.drawCircle(tr.mapX[ni], tr.mapY[ni], 0.024f, paint)
        }
        paint.color = 0xFFFF6D00.toInt()   // player
        val pi = U.mod(((playerPos + PLAYER_Z) / SEG_LEN).toInt(), tr.segments.size)
        c.drawCircle(tr.mapX[pi], tr.mapY[pi], 0.036f, paint)
        c.restore()
    }

    private fun drawTouchControls(c: Canvas) {
        val bs = min(w * 0.13f, h * 0.2f)
        val cy = h - bs * 0.9f
        for (side in 0 until 2) {
            val cx = if (side == 0) w * 0.09f else w * 0.23f
            val active = if (side == 0) steerL else steerR
            paint.reset(); paint.isAntiAlias = true
            paint.color = U.withA(if (active) 0xFFFF6D00.toInt() else 0xFFFFFFFF.toInt(), if (active) 200 else 120)
            c.drawCircle(cx, cy, bs * 0.9f, paint)
            paint.color = U.withA(if (active) 0xFFFF6D00.toInt() else 0xFFFFFFFF.toInt(), 40)
            c.drawCircle(cx, cy, bs, paint)
            paint.color = U.withA(if (active) 0xFFFFFFFF.toInt() else 0xFF212121.toInt(), 230)
            path.reset()
            val d = if (side == 0) -1f else 1f
            path.moveTo(cx + d * bs * 0.35f, cy - bs * 0.3f)
            path.lineTo(cx + d * bs * 0.35f, cy + bs * 0.3f)
            path.lineTo(cx - d * bs * 0.35f, cy)
            path.close()
            c.drawPath(path, paint)
        }
        val rcx = w - w * 0.09f
        paint.reset(); paint.isAntiAlias = true
        paint.color = U.withA(if (brakeHeld) 0xFFFF1744.toInt() else 0xFFFFFFFF.toInt(), if (brakeHeld) 200 else 120)
        c.drawCircle(rcx, cy, bs * 0.85f, paint)
        text(c, "BRK", rcx, cy, bs * 0.30f, 0xFF212121.toInt())
        val ncx = w - w * 0.24f
        paint.reset(); paint.isAntiAlias = true
        paint.color = U.withA(if (nitroHeld || boosting) 0xFF00E5FF.toInt() else 0xFFFFFFFF.toInt(), if (nitroHeld || boosting) 200 else 120)
        c.drawCircle(ncx, cy, bs * 0.9f, paint)
        paint.color = U.withA(0xFF212121.toInt(), 230)
        path.reset()
        path.moveTo(ncx + bs * 0.08f, cy - bs * 0.4f)
        path.lineTo(ncx - bs * 0.15f, cy + bs * 0.05f)
        path.lineTo(ncx + bs * 0.02f, cy + bs * 0.05f)
        path.lineTo(ncx - bs * 0.08f, cy + bs * 0.4f)
        path.lineTo(ncx + bs * 0.18f, cy - bs * 0.05f)
        path.lineTo(ncx + bs * 0.02f, cy - bs * 0.05f)
        path.close()
        c.drawPath(path, paint)
    }

    private fun drawCountdown(c: Canvas) {
        val label = when {
            countdown > 3f -> "3"
            countdown > 2f -> "2"
            countdown > 1f -> "1"
            else -> "GO!"
        }
        val phase = countdown % 1f
        val sc = 1f + phase * 0.6f
        paint.reset()
        paint.isAntiAlias = true
        paint.color = U.withA(0xFF000000.toInt(), 100)
        c.drawCircle(w / 2f, h * 0.35f, w * 0.16f * sc, paint)
        text(c, label, w / 2f, h * 0.35f, w * 0.13f * sc, 0xFFFFFFFF.toInt())
    }

    private fun drawPause(c: Canvas) {
        paint.reset(); paint.isAntiAlias = true
        paint.color = U.withA(0xFF000000.toInt(), 160)
        c.drawRect(0f, 0f, w, h, paint)
        text(c, "PAUSED", w / 2f, h * 0.2f, w * 0.06f, 0xFFFFFFFF.toInt())
        val bw = w * 0.4f; val bh = h * 0.09f
        button(c, "resume", RectF(w / 2f - bw / 2f, h * 0.32f, w / 2f + bw / 2f, h * 0.32f + bh), "RESUME", accent = true)
        button(c, "restart", RectF(w / 2f - bw / 2f, h * 0.32f + bh * 1.3f, w / 2f + bw / 2f, h * 0.32f + bh * 2.3f), "RESTART")
        button(c, "quit", RectF(w / 2f - bw / 2f, h * 0.32f + bh * 2.6f, w / 2f + bw / 2f, h * 0.32f + bh * 3.6f), "QUIT TO MENU")
    }

    // ================= menu =================

    private fun drawMenuBg(c: Canvas, themeIdx: Int) {
        val theme = THEMES[themeIdx]
        ensureSky(themeIdx)
        paint.shader = skyShader
        c.drawRect(0f, 0f, w, h * 0.62f, paint)
        paint.shader = null
        skyOffset = U.wrap(skyOffset + 0.6f, w * 8f)
        drawSky(c, theme)
        drawMountains(c, theme)
        paint.color = theme.grass1
        c.drawRect(0f, h * 0.62f, w, h, paint)
        val hy = h * 0.62f
        paint.color = theme.road1
        path.reset()
        path.moveTo(w * 0.30f, h); path.lineTo(w * 0.46f, h)
        path.lineTo(w * 0.52f, hy); path.lineTo(w * 0.48f, hy)
        path.close()
        c.drawPath(path, paint)
        for (i in 0 until 6) {
            val t0 = i / 6f
            val t1 = t0 + 0.06f
            paint.color = theme.lane
            c.drawRect(RectF(
                w * (0.495f - 0.004f * t0), hy + (h - hy) * t0,
                w * (0.505f + 0.004f * t0), hy + (h - hy) * t1), paint)
        }
        val bob = abs(sin(time * 2.2f)) * 3f
        CarArt.draw(c, save.spec, w / 2f, h - h * 0.06f, w * 0.0024f,
            sin(time * 0.7f) * 0.2f, bob, false, false, theme.night, time, true)
    }

    private fun drawMenu(c: Canvas) {
        paint.reset()
        paint.isAntiAlias = true
        paint.shader = LinearGradient(w * 0.2f, h * 0.10f, w * 0.8f, h * 0.20f,
            0xFFFF6D00.toInt(), 0xFFFF1744.toInt(), Shader.TileMode.CLAMP)
        paint.textSize = w * 0.085f
        paint.typeface = font
        paint.textAlign = Paint.Align.CENTER
        paint.textSkewX = -0.18f
        val fm = paint.fontMetrics
        c.drawText("APEX RUSH", w / 2f, h * 0.15f - (fm.ascent + fm.descent) / 2f, paint)
        paint.textSkewX = 0f
        paint.shader = null
        text(c, "STREET LEGENDS", w / 2f, h * 0.21f, w * 0.026f, 0xFFFFB74D.toInt())

        val bw = w * 0.5f; val bh = h * 0.10f
        val cx = w / 2f
        button(c, "menu_race", RectF(cx - bw / 2f, h * 0.34f, cx + bw / 2f, h * 0.34f + bh), "RACE", accent = true)
        button(c, "menu_garage", RectF(cx - bw / 2f, h * 0.34f + bh * 1.25f, cx + bw / 2f, h * 0.34f + bh * 2.25f), "GARAGE")
        button(c, "menu_upg", RectF(cx - bw / 2f, h * 0.34f + bh * 2.5f, cx + bw / 2f, h * 0.34f + bh * 3.5f), "UPGRADES")
        drawCoins(c, w - w * 0.02f - w * 0.16f, h * 0.025f, w * 0.16f)
    }

    private fun drawCoins(c: Canvas, left: Float, top: Float, width: Float) {
        paint.reset(); paint.isAntiAlias = true
        paint.shader = LinearGradient(left, top, left, top + width * 0.22f,
            0xFFFFD54F.toInt(), 0xFFF9A825.toInt(), Shader.TileMode.CLAMP)
        c.drawRoundRect(RectF(left, top, left + width, top + width * 0.22f), width * 0.06f, width * 0.06f, paint)
        paint.shader = null
        paint.color = 0xFF3E2723.toInt()
        c.drawCircle(left + width * 0.11f, top + width * 0.11f, width * 0.07f, paint)
        text(c, save.coins.toString(), left + width * 0.62f, top + width * 0.11f, width * 0.115f, 0xFF3E2723.toInt())
    }

    // ================= track select =================

    private fun drawTrackSelect(c: Canvas) {
        text(c, "SELECT TRACK", w / 2f, h * 0.05f, w * 0.042f, 0xFFFFFFFF.toInt())
        drawCoins(c, w - w * 0.02f - w * 0.16f, h * 0.02f, w * 0.16f)
        val cols = 3
        val cardW = w * 0.293f
        val cardH = h * 0.36f
        for (i in 0 until THEMES.size) {
            val row = i / cols
            val col = i % cols
            val left = w * 0.03f + col * (cardW + w * 0.015f)
            val top = h * 0.085f + row * (cardH + h * 0.02f)
            val sel = save.track == i
            val th = THEMES[i]
            val tr = trackFor(i)
            paint.reset(); paint.isAntiAlias = true
            paint.shader = LinearGradient(left, top, left, top + cardH, th.skyTop, th.skyBot, Shader.TileMode.CLAMP)
            c.drawRoundRect(RectF(left, top, left + cardW, top + cardH), 12f, 12f, paint)
            paint.shader = null
            // the actual circuit map, drawn from the real track geometry
            val ms = min(cardW * 0.84f, cardH * 0.56f)
            val cp = circuitPath(i)
            c.save()
            c.translate(left + (cardW - ms) / 2f, top + cardH * 0.05f)
            c.scale(ms, ms)
            paint.style = Paint.Style.STROKE
            paint.strokeCap = Paint.Cap.ROUND
            paint.strokeWidth = 0.075f
            paint.color = U.withA(0xFF0D0D14.toInt(), 230)
            c.drawPath(cp, paint)
            paint.strokeWidth = 0.040f
            paint.color = U.withA(th.accent, 255)
            c.drawPath(cp, paint)
            paint.style = Paint.Style.FILL
            paint.color = 0xFFFFFFFF.toInt()
            c.drawCircle(tr.mapX[0], tr.mapY[0], 0.045f, paint)
            c.restore()
            // info band
            val bandTop = top + cardH * 0.66f
            paint.color = U.withA(0xFF0C0D12.toInt(), 242)
            c.drawRoundRect(RectF(left, bandTop, left + cardW, top + cardH + 12f), 12f, 12f, paint)
            text(c, th.name, left + cardW / 2f, bandTop + cardH * 0.085f, cardW * 0.060f, 0xFFFFFFFF.toInt())
            val best = save.best[i]
            text(c, if (best > 0) "BEST " + U.fmtTime(best) else "NO RECORD",
                left + cardW / 2f, bandTop + cardH * 0.155f, cardW * 0.050f, 0xFFFFD54F.toInt())
            val rating = TRACK_RATING[i]
            val barW = cardW * 0.052f
            val totalW = barW * 6f + barW * 0.5f * 5f
            var bx = left + cardW / 2f - totalW / 2f
            val by = bandTop + cardH * 0.225f
            for (d in 0 until 6) {
                paint.color = if (d < rating) 0xFFFF6D00.toInt() else U.withA(0xFFFFFFFF.toInt(), 45)
                c.drawRoundRect(RectF(bx, by, bx + barW, by + cardH * 0.035f), 2f, 2f, paint)
                bx += barW * 1.5f
            }
            if (sel) {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 5f
                paint.color = 0xFFFF6D00.toInt()
                c.drawRoundRect(RectF(left, top, left + cardW, top + cardH), 12f, 12f, paint)
                paint.style = Paint.Style.FILL
            }
            uiButtons.add(Triple("sel$i", RectF(left, top, left + cardW, top + cardH), true))
        }
        val bw = w * 0.36f; val bh = h * 0.085f
        val by = h * 0.885f
        button(c, "menu_back", RectF(w * 0.04f, by, w * 0.04f + bw, by + bh), "BACK")
        button(c, "track_go", RectF(w * 0.60f, by, w * 0.60f + bw, by + bh), "START RACE", accent = true)
    }

    // ================= garage =================

    private fun drawGarage(c: Canvas) {
        paint.reset(); paint.isAntiAlias = true
        paint.shader = LinearGradient(0f, 0f, 0f, h, 0xFF23253A.toInt(), 0xFF12131C.toInt(), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, paint)
        paint.shader = null
        val floorY = h * 0.46f
        paint.shader = RadialGradient(w / 2f, floorY, w * 0.4f, U.withA(0xFFFFECB3.toInt(), 70),
            U.withA(0xFFFFECB3.toInt(), 0), Shader.TileMode.CLAMP)
        c.drawCircle(w / 2f, floorY, w * 0.4f, paint)
        paint.shader = null
        paint.color = U.withA(0xFFFFFFFF.toInt(), 22)
        for (i in 1 until 8) {
            val gy = floorY + (h - floorY) * (i * i) / 64f
            c.drawLine(0f, gy, w, gy, paint)
        }
        text(c, "GARAGE", w * 0.5f, h * 0.04f, w * 0.040f, 0xFFFFFFFF.toInt())
        drawCoins(c, w - w * 0.02f - w * 0.16f, h * 0.02f, w * 0.16f)

        val sway = sin(time * 0.9f) * 0.25f
        val bob = abs(sin(time * 2.5f)) * 2f
        CarArt.draw(c, previewSpec, w / 2f, floorY, w * 0.0030f, sway, bob, false, false, false, time, true)
        text(c, CHASSIS_NAMES[previewSpec.chassis], w * 0.5f, floorY + h * 0.06f, w * 0.024f, 0xFFFFB74D.toInt())

        val tabs = arrayOf("PAINT", "FINISH", "WHEELS", "BODY", "GLOW")
        val tabW = w / tabs.size
        for (i in tabs.indices) {
            val r = RectF(i * tabW, h * 0.56f, (i + 1) * tabW, h * 0.56f + h * 0.055f)
            paint.color = if (tab == i) U.withA(0xFFFF6D00.toInt(), 220) else U.withA(0xFFFFFFFF.toInt(), 30)
            c.drawRoundRect(r, 8f, 8f, paint)
            text(c, tabs[i], r.centerX(), r.centerY(), h * 0.024f,
                if (tab == i) 0xFFFFFFFF.toInt() else 0xFFDDDDDD.toInt())
            uiButtons.add(Triple("tab$i", RectF(r), true))
        }

        val top = h * 0.635f
        when (tab) {
            0 -> drawPaintTab(c, top)
            1 -> drawFinishTab(c, top)
            2 -> drawRimTab(c, top)
            3 -> drawBodyTab(c, top)
            else -> drawGlowTab(c, top)
        }

        val bh = h * 0.075f
        val bw = w * 0.30f
        button(c, "garage_save", RectF(w * 0.03f, h - bh - h * 0.015f, w * 0.03f + bw, h - h * 0.015f), "SAVE + EXIT")
        button(c, "garage_race", RectF(w / 2f - bw / 2f, h - bh - h * 0.015f, w / 2f + bw / 2f, h - h * 0.015f), "GO RACE", accent = true)
        button(c, "chassis_cycle", RectF(w - w * 0.03f - bw, h - bh - h * 0.015f, w - w * 0.03f, h - h * 0.015f), "SWAP CAR")
    }

    private fun drawPaintTab(c: Canvas, top: Float) {
        val cols = 8
        val cw = w / cols
        val chh = (h * 0.84f - top) / 2
        for (i in PAINTS.indices) {
            val cx = (i % cols) * cw + cw / 2f
            val cy = top + (i / cols) * chh + chh / 2f
            val r = min(cw, chh) * 0.30f
            paint.reset(); paint.isAntiAlias = true
            paint.shader = LinearGradient(cx - r, cy - r, cx + r, cy + r,
                U.lighten(PAINTS[i], 0.2f), U.darken(PAINTS[i], 0.25f), Shader.TileMode.CLAMP)
            c.drawCircle(cx, cy, r, paint)
            paint.shader = null
            if (previewSpec.paint == i) {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 4f
                paint.color = 0xFFFFFFFF.toInt()
                c.drawCircle(cx, cy, r + 4f, paint)
                paint.style = Paint.Style.FILL
            }
            uiButtons.add(Triple("paint$i",
                RectF(cx - cw / 2f, cy - chh / 2f, cx + cw / 2f, cy + chh / 2f), true))
        }
    }

    private fun drawFinishTab(c: Canvas, top: Float) {
        val count = 4
        val cw = (w - w * 0.06f) / count
        for (i in 0 until count) {
            val left = w * 0.03f + i * cw
            val r = RectF(left + w * 0.008f, top + h * 0.01f, left + cw - w * 0.008f, top + h * 0.14f)
            val sel = previewSpec.finish == i
            paint.reset(); paint.isAntiAlias = true
            paint.color = U.withA(if (sel) 0xFFFF6D00.toInt() else 0xFFFFFFFF.toInt(), if (sel) 200 else 36)
            c.drawRoundRect(r, 10f, 10f, paint)
            val chip = RectF(r.left + r.width() * 0.15f, r.top + r.height() * 0.15f,
                r.right - r.width() * 0.15f, r.top + r.height() * 0.55f)
            c.drawRoundRect(chip, 6f, 6f, CarArt.bodyPaint(PAINTS[previewSpec.paint], i))
            text(c, FINISH_NAMES[i], r.centerX(), r.top + r.height() * 0.78f, r.width() * 0.16f, 0xFFEEEEEE.toInt())
            uiButtons.add(Triple("finish$i", RectF(r), true))
        }
        text(c, "METALLIC SHOWS FLAKE - CHAMELEON SHIFTS IN THE LIGHT",
            w * 0.5f, top + h * 0.18f, w * 0.016f, 0xFF757575.toInt())
    }

    private fun drawRimTab(c: Canvas, top: Float) {
        val count = 5
        val cw = (w - w * 0.06f) / count
        for (i in 0 until count) {
            val left = w * 0.03f + i * cw
            val r = RectF(left + w * 0.008f, top + h * 0.01f, left + cw - w * 0.008f, top + h * 0.12f)
            val sel = previewSpec.rim == i
            paint.reset(); paint.isAntiAlias = true
            paint.color = U.withA(if (sel) 0xFFFF6D00.toInt() else 0xFFFFFFFF.toInt(), if (sel) 200 else 36)
            c.drawRoundRect(r, 10f, 10f, paint)
            drawMiniRim(c, r.centerX(), r.top + r.height() * 0.42f, r.height() * 0.26f, i, RIM_COLORS[previewSpec.rimColor])
            text(c, RIM_NAMES[i], r.centerX(), r.top + r.height() * 0.80f, r.width() * 0.15f, 0xFFEEEEEE.toInt())
            uiButtons.add(Triple("rim$i", RectF(r), true))
        }
        text(c, "RIM COLOR", w * 0.12f, top + h * 0.155f, w * 0.018f, 0xFFBDBDBD.toInt())
        val rcw = w / 8f
        for (i in RIM_COLORS.indices) {
            val cx = w * 0.12f + i * rcw
            val cy = top + h * 0.21f
            val r = min(rcw, h * 0.030f) * 0.45f
            paint.reset(); paint.isAntiAlias = true
            paint.shader = LinearGradient(cx - r, cy - r, cx + r, cy + r,
                U.lighten(RIM_COLORS[i], 0.2f), U.darken(RIM_COLORS[i], 0.2f), Shader.TileMode.CLAMP)
            c.drawCircle(cx, cy, r, paint)
            paint.shader = null
            if (previewSpec.rimColor == i) {
                paint.style = Paint.Style.STROKE; paint.strokeWidth = 4f; paint.color = 0xFFFFFFFF.toInt()
                c.drawCircle(cx, cy, r + 4f, paint)
                paint.style = Paint.Style.FILL
            }
            uiButtons.add(Triple("rimc$i", RectF(cx - rcw / 2f, cy - h * 0.03f, cx + rcw / 2f, cy + h * 0.03f), true))
        }
    }

    private fun drawMiniRim(c: Canvas, x: Float, y: Float, r: Float, style: Int, color: Int) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = 0xFF222222.toInt()
        c.drawCircle(x, y, r, p)
        val ring = Paint(Paint.ANTI_ALIAS_FLAG)
        ring.color = color
        ring.style = Paint.Style.STROKE
        ring.strokeWidth = r * 0.2f
        val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        fill.color = color
        when (style) {
            0 -> { c.drawCircle(x, y, r * 0.9f, fill); miniSpokes(c, x, y, r, 5, ring) }
            1 -> { c.drawCircle(x, y, r * 0.9f, fill); miniSpokes(c, x, y, r, 12, ring) }
            2 -> { c.drawCircle(x, y, r * 0.4f, fill); c.drawCircle(x, y, r * 0.95f, ring) }
            3 -> { miniSpokes(c, x, y, r, 8, ring); c.drawCircle(x, y, r * 0.75f, ring) }
            else -> { c.drawCircle(x, y, r * 0.35f, fill); miniSpokes(c, x, y, r, 6, ring) }
        }
    }

    private fun miniSpokes(c: Canvas, x: Float, y: Float, r: Float, n: Int, p: Paint) {
        for (i in 0 until n) {
            val a = i * (2.0 * Math.PI / n).toFloat()
            c.drawLine(x, y, x + sin(a) * r * 0.9f, y + cos(a) * r * 0.9f, p)
        }
    }

    private fun drawBodyTab(c: Canvas, top: Float) {
        val cw = (w * 0.26f - w * 0.02f)
        text(c, "CHASSIS", w * 0.13f, top + h * 0.012f, w * 0.016f, 0xFFBDBDBD.toInt())
        for (i in 0 until 3) {
            val left = w * 0.02f + i * cw
            val r = RectF(left, top + h * 0.03f, left + cw - w * 0.01f, top + h * 0.085f)
            paint.reset(); paint.isAntiAlias = true
            paint.color = U.withA(if (previewSpec.chassis == i) 0xFFFF6D00.toInt() else 0xFFFFFFFF.toInt(),
                if (previewSpec.chassis == i) 200 else 36)
            c.drawRoundRect(r, 10f, 10f, paint)
            text(c, CHASSIS_NAMES[i], r.centerX(), r.centerY(), r.height() * 0.30f, 0xFFEEEEEE.toInt())
            uiButtons.add(Triple("chassis$i", RectF(r), true))
        }
        text(c, "SPOILER", w * 0.13f, top + h * 0.105f, w * 0.016f, 0xFFBDBDBD.toInt())
        for (i in 0 until 3) {
            val left = w * 0.02f + i * cw
            val r = RectF(left, top + h * 0.123f, left + cw - w * 0.01f, top + h * 0.178f)
            paint.reset(); paint.isAntiAlias = true
            paint.color = U.withA(if (previewSpec.spoiler == i) 0xFFFF6D00.toInt() else 0xFFFFFFFF.toInt(),
                if (previewSpec.spoiler == i) 200 else 36)
            c.drawRoundRect(r, 10f, 10f, paint)
            text(c, SPOILER_NAMES[i], r.centerX(), r.centerY(), r.height() * 0.30f, 0xFFEEEEEE.toInt())
            uiButtons.add(Triple("spoiler$i", RectF(r), true))
        }
        text(c, "STRIPES", w * 0.13f, top + h * 0.198f, w * 0.016f, 0xFFBDBDBD.toInt())
        for (i in 0 until 3) {
            val left = w * 0.02f + i * cw
            val r = RectF(left, top + h * 0.216f, left + cw - w * 0.01f, top + h * 0.271f)
            paint.reset(); paint.isAntiAlias = true
            paint.color = U.withA(if (previewSpec.stripes == i) 0xFFFF6D00.toInt() else 0xFFFFFFFF.toInt(),
                if (previewSpec.stripes == i) 200 else 36)
            c.drawRoundRect(r, 10f, 10f, paint)
            text(c, STRIPE_NAMES[i], r.centerX(), r.centerY(), r.height() * 0.30f, 0xFFEEEEEE.toInt())
            uiButtons.add(Triple("stripe$i", RectF(r), true))
        }
    }

    private fun drawGlowTab(c: Canvas, top: Float) {
        val count = NEONS.size + 1
        val cw = (w - w * 0.06f) / count
        for (i in 0 until count) {
            val left = w * 0.03f + i * cw
            val r = RectF(left + w * 0.008f, top + h * 0.02f, left + cw - w * 0.008f, top + h * 0.13f)
            val sel = previewSpec.glow == i
            paint.reset(); paint.isAntiAlias = true
            paint.color = U.withA(if (sel) 0xFFFF6D00.toInt() else 0xFFFFFFFF.toInt(), if (sel) 200 else 36)
            c.drawRoundRect(r, 10f, 10f, paint)
            if (i == 0) {
                text(c, "OFF", r.centerX(), r.centerY(), r.width() * 0.22f, 0xFFEEEEEE.toInt())
            } else {
                val g = NEONS[i - 1]
                paint.shader = RadialGradient(r.centerX(), r.top + r.height() * 0.5f, r.height() * 0.5f,
                    U.withA(g, 220), U.withA(g, 30), Shader.TileMode.CLAMP)
                c.drawCircle(r.centerX(), r.top + r.height() * 0.5f, r.height() * 0.3f, paint)
                paint.shader = null
            }
            uiButtons.add(Triple("glow$i", RectF(r), true))
        }
        text(c, "NEON UNDERGLOW", w * 0.5f, top + h * 0.17f, w * 0.020f, 0xFFBDBDBD.toInt())
    }

    // ================= upgrades =================

    private fun drawUpgrades(c: Canvas) {
        paint.reset(); paint.isAntiAlias = true
        paint.shader = LinearGradient(0f, 0f, 0f, h, 0xFF1B1E2E.toInt(), 0xFF101219.toInt(), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, paint)
        paint.shader = null
        text(c, "PERFORMANCE", w / 2f, h * 0.05f, w * 0.045f, 0xFFFFFFFF.toInt())
        drawCoins(c, w - w * 0.02f - w * 0.16f, h * 0.025f, w * 0.16f)

        val entries = arrayOf(
            Triple("ENGINE", save.upg.engine, "buy_engine"),
            Triple("TURBO", save.upg.turbo, "buy_turbo"),
            Triple("TIRES", save.upg.tires, "buy_tires")
        )
        val descs = arrayOf("Top speed", "Acceleration + nitro", "Grip + steering")
        for (i in entries.indices) {
            val (name, lvl, id) = entries[i]
            val top = h * (0.12f + i * 0.17f)
            val r = RectF(w * 0.05f, top, w * 0.95f, top + h * 0.15f)
            paint.color = U.withA(0xFFFFFFFF.toInt(), 24)
            c.drawRoundRect(r, 14f, 14f, paint)
            text(c, name, r.left + r.width() * 0.10f, r.top + r.height() * 0.30f, h * 0.035f, 0xFFFFB74D.toInt())
            text(c, descs[i], r.left + r.width() * 0.10f, r.top + r.height() * 0.62f, h * 0.020f, 0xFF9E9E9E.toInt())
            for (k in 0 until 5) {
                paint.color = if (k < lvl) 0xFFFF6D00.toInt() else U.withA(0xFFFFFFFF.toInt(), 40)
                c.drawRoundRect(RectF(
                    r.left + r.width() * 0.42f + k * r.width() * 0.048f,
                    r.top + r.height() * 0.30f - h * 0.012f,
                    r.left + r.width() * 0.42f + k * r.width() * 0.048f + r.width() * 0.036f,
                    r.top + r.height() * 0.30f + h * 0.012f), 4f, 4f, paint)
            }
            val cost = if (lvl >= 5) 0 else Upg.cost(lvl)
            val canBuy = lvl < 5 && save.coins >= cost
            val bw = r.width() * 0.22f
            button(c, id,
                RectF(r.right - bw - w * 0.02f, r.top + r.height() * 0.5f - h * 0.028f,
                    r.right - w * 0.02f, r.top + r.height() * 0.5f + h * 0.028f),
                if (lvl >= 5) "MAX" else "$cost",
                enabled = canBuy, accent = canBuy)
        }

        val statsTop = h * 0.65f
        text(c, "CAR STATS", w / 2f, statsTop, w * 0.030f, 0xFFFFFFFF.toInt())
        val bars = arrayOf(
            "TOP SPEED" to (save.upg.engine / 5f),
            "ACCELERATION" to (save.upg.turbo / 5f),
            "GRIP" to (save.upg.tires / 5f)
        )
        for (i in bars.indices) {
            val (label, v) = bars[i]
            val y = statsTop + h * (0.05f + i * 0.055f)
            text(c, label, w * 0.18f, y, w * 0.020f, 0xFFBDBDBD.toInt())
            val bl = w * 0.30f
            paint.color = U.withA(0xFFFFFFFF.toInt(), 40)
            c.drawRoundRect(RectF(bl, y - h * 0.008f, bl + w * 0.45f, y + h * 0.008f), 6f, 6f, paint)
            paint.color = 0xFF00E5FF.toInt()
            c.drawRoundRect(RectF(bl, y - h * 0.008f, bl + w * 0.45f * (0.2f + v * 0.8f), y + h * 0.008f), 6f, 6f, paint)
        }
        text(c, "${save.topSpeedKmh()} km/h top speed", w / 2f, statsTop + h * 0.23f, w * 0.022f, 0xFFFFD54F.toInt())

        val bw = w * 0.4f; val bh = h * 0.08f
        button(c, "menu_back", RectF(w / 2f - bw / 2f, h - bh - h * 0.02f, w / 2f + bw / 2f, h - h * 0.02f), "BACK")
    }

    // ================= results =================

    private fun drawResults(c: Canvas) {
        paint.reset(); paint.isAntiAlias = true
        paint.color = U.withA(0xFF000000.toInt(), 130)
        c.drawRect(0f, 0f, w, h, paint)
        val r = RectF(w * 0.1f, h * 0.12f, w * 0.9f, h * 0.80f)
        paint.shader = LinearGradient(r.left, r.top, r.left, r.bottom,
            0xFF26283C.toInt(), 0xFF15161F.toInt(), Shader.TileMode.CLAMP)
        c.drawRoundRect(r, 20f, 20f, paint)
        paint.shader = null

        text(c, if (resultPos == 1) "VICTORY!" else "FINISH", r.centerX(), r.top + r.height() * 0.10f, w * 0.055f,
            if (resultPos == 1) 0xFFFFD54F.toInt() else 0xFFFFFFFF.toInt())
        text(c, ordinal(resultPos) + " PLACE", r.centerX(), r.top + r.height() * 0.24f, w * 0.040f, 0xFF00E5FF.toInt())
        text(c, "TIME  " + U.fmtTime(raceTime), r.centerX(), r.top + r.height() * 0.36f, w * 0.030f, 0xFFEEEEEE.toInt())
        if (newRecord) text(c, "NEW TRACK RECORD!", r.centerX(), r.top + r.height() * 0.44f, w * 0.024f, 0xFFFF6D00.toInt())
        text(c, "+$resultCoins COINS", r.centerX(), r.top + r.height() * 0.56f, w * 0.035f, 0xFFFFD54F.toInt())

        val bw = r.width() * 0.40f; val bh = h * 0.07f
        button(c, "results_retry", RectF(r.left + r.width() * 0.06f, r.bottom - bh * 1.9f,
            r.left + r.width() * 0.06f + bw, r.bottom - bh * 1.9f + bh), "RETRY", accent = true)
        button(c, "results_garage", RectF(r.right - r.width() * 0.06f - bw, r.bottom - bh * 1.9f,
            r.right - r.width() * 0.06f, r.bottom - bh * 1.9f + bh), "GARAGE")
        button(c, "results_menu", RectF(r.centerX() - bw / 2f, r.bottom - bh * 0.8f,
            r.centerX() + bw / 2f, r.bottom - bh * 0.8f + bh * 0.8f), "MENU")
    }
}
