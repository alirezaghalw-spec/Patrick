package com.personal.emo

import android.content.Context
import android.graphics.*
import android.view.View
import kotlin.math.*

/**
 * Emo's face.
 *  - eyes morph smoothly between moods (lerp) and open up from closed after shape moods
 *  - eyebrows + mouth per mood
 *  - idle: random glances / look-around, yawn, stretch
 *  - NEW moods: smug, bored, confused, shy (blush), star ; cry now has tears
 *  - NEW animations: playSneeze(), playShake()
 * The service must call update(dt) once per frame.
 */
class EmoView(ctx: Context) : View(ctx) {
    companion object {
        val BODY = arrayOf(
            intArrayOf(0xFFECEBE6.toInt(), 0xFFCFCDC6.toInt()),
            intArrayOf(0xFFFFD6E0.toInt(), 0xFFF2A6BC.toInt()),
            intArrayOf(0xFFCFE8FF.toInt(), 0xFF9CC7F0.toInt()),
            intArrayOf(0xFFD5F5E3.toInt(), 0xFF9FD9B8.toInt()),
            intArrayOf(0xFFFFF1B8.toInt(), 0xFFF2D36B.toInt())
        )
        // moods drawn as special shapes (not the morphing rectangle eyes)
        private val SHAPE = setOf("happy", "love", "laugh", "dizzy", "clock", "star")

        /** every mood name this view understands (handy for validating strings from the service) */
        val MOODS = listOf(
            "normal", "wow", "sleepy", "angry", "sad", "hungry", "cry", "wink",
            "happy", "love", "laugh", "dizzy", "clock",
            "smug", "bored", "confused", "shy", "star"
        )

        private const val CLOSED = 3f
        private const val ANIM_NONE = 0
        private const val ANIM_YAWN = 1
        private const val ANIM_STRETCH = 2
        private const val ANIM_SNEEZE = 3
        private const val ANIM_SHAKE = 4
    }

    var mood = "normal"
        set(value) {
            if (field == value) return
            val wasShape = field in SHAPE
            field = value
            moodT = 0f
            // coming out of a shape mood: eyes open up from closed instead of popping
            // (FIX: wink is NOT a shape mood anymore, so the open eye no longer re-blinks after a wink)
            if (wasShape && value !in SHAPE) {
                for (i in 0..1) { eh[i] = CLOSED; elid[i] = 0f }
            }
        }
    var blink = false
    var gx = 0f
    var gy = 0f
    var tilt = 0f
    var bob = 0f
    var clockTime = ""
    var clockDate = ""

    /** true while a yawn/stretch/sneeze/shake is playing */
    val busy: Boolean get() = anim != ANIM_NONE

    // ---- current (animated) face state ----
    private val ew = floatArrayOf(16f, 16f)
    private val eh = floatArrayOf(22f, 22f)
    private val ecy = floatArrayOf(38f, 38f)
    private val elid = floatArrayOf(0f, 0f)       // >0 angry lid, <0 sad lid
    private val bA = floatArrayOf(0f, 0f)         // brow alpha
    private val bR = floatArrayOf(0f, 0f)         // brow raise
    private val bG = floatArrayOf(0f, 0f)         // brow angle (>0 inner side down = angry)
    private var mA = 0f; private var mC = 0f; private var mO = 0f; private var mW = 12f
    private var mS = 0f                           // mouth skew (>0 = smirk to the right)
    private var bl = 0f                           // blush alpha

    // ---- targets ----
    private val tw = floatArrayOf(16f, 16f)
    private val th = floatArrayOf(22f, 22f)
    private val tcy = floatArrayOf(38f, 38f)
    private val tlid = floatArrayOf(0f, 0f)
    private val tbA = floatArrayOf(0f, 0f)
    private val tbR = floatArrayOf(0f, 0f)
    private val tbG = floatArrayOf(0f, 0f)
    private var tmA = 0f; private var tmC = 0f; private var tmO = 0f; private var tmW = 12f
    private var tmS = 0f
    private var tbl = 0f

    private var moodT = 0f
    private var blinkK = 0f
    private var sq = 0f                 // squint from yawn/stretch/sneeze/shake
    private var sx = 1f; private var sy = 1f
    private var animTilt = 0f
    private var animLook = 0f           // horizontal eye offset used by the shake animation
    private var spray = 0f              // sneeze droplets (0 = none, 1..0 = fading)
    private var anim = ANIM_NONE
    private var animT = 0f
    private var onAnimEnd: (() -> Unit)? = null
    private var lookX = 0f; private var lookY = 0f
    private var lookTx = 0f; private var lookTy = 0f
    private var nextGlance = 2f
    private var ox = 0f; private var oy = 0f

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val cyan = 0xFF4DE3FF.toInt()
    private val scr = 0xFF07080A.toInt()
    private val rf = RectF()
    private val path = Path()
    private var bodyShader = LinearGradient(0f, 0f, 132f, 118f, BODY[0][0], BODY[0][1], Shader.TileMode.CLAMP)

    fun setBody(i: Int) {
        val c = BODY[i.coerceIn(0, BODY.size - 1)]
        bodyShader = LinearGradient(0f, 0f, 132f, 118f, c[0], c[1], Shader.TileMode.CLAMP)
        invalidate()
    }

    // ================= public animations =================
    fun playYawn(onDone: (() -> Unit)? = null) = startAnim(ANIM_YAWN, onDone)
    fun playStretch(onDone: (() -> Unit)? = null) = startAnim(ANIM_STRETCH, onDone)
    fun playSneeze(onDone: (() -> Unit)? = null) = startAnim(ANIM_SNEEZE, onDone)
    fun playShake(onDone: (() -> Unit)? = null) = startAnim(ANIM_SHAKE, onDone)

    private fun startAnim(kind: Int, onDone: (() -> Unit)?) {
        if (anim != ANIM_NONE) return
        // FIX: these animations draw on top of the rectangle eyes. In a shape mood
        // (happy/love/clock/...) they looked broken, so skip them but still fire the callback
        // so the service never waits forever.
        if (mood in SHAPE) { onDone?.invoke(); return }
        anim = kind; animT = 0f; onAnimEnd = onDone
    }

    // ================= per-frame update =================
    fun update(step: Float) {
        if (step <= 0f) return
        val dt = min(step, .1f)          // FIX: huge dt (after pause / lag) no longer skips whole animations
        moodT += dt
        glance(dt)

        var bell = 0f
        var t01 = 0f
        var pre = 0f
        var post = 0f
        sq = 0f; sx = 1f; sy = 1f; animTilt = 0f; animLook = 0f; spray = 0f
        val a = anim
        if (a != ANIM_NONE) {
            animT += dt
            val dur = when (a) {
                ANIM_YAWN -> 2.0f
                ANIM_STRETCH -> 1.7f
                ANIM_SNEEZE -> 1.6f
                else -> 1.2f
            }
            t01 = min(1f, animT / dur)
            bell = sin(PI.toFloat() * t01)
            when (a) {
                ANIM_YAWN -> {
                    sq = min(1f, bell * 1.8f) * .9f
                    sy = 1f + .05f * bell
                    animTilt = -4f * bell
                }
                ANIM_STRETCH -> {
                    sq = min(1f, bell * 1.6f) * .85f
                    sy = 1f + .13f * bell
                    sx = 1f - .07f * bell
                    animTilt = sin(t01 * PI.toFloat() * 6f) * 3f * bell
                }
                ANIM_SNEEZE -> {
                    pre = min(1f, t01 / .55f)
                    post = if (t01 < .55f) 0f else 1f - (t01 - .55f) / .45f
                    if (t01 < .55f) {            // build-up: head back, eyes squint
                        sq = .7f * pre
                        sy = 1f + .04f * pre
                        animTilt = -6f * pre
                    } else {                     // "achoo!": squash forward, eyes shut, droplets
                        sq = post
                        sy = 1f - .10f * post
                        sx = 1f + .08f * post
                        animTilt = 7f * post
                        spray = post
                    }
                }
                else -> {                        // ANIM_SHAKE ("no" head shake)
                    val w = sin(t01 * PI.toFloat() * 8f) * bell
                    animTilt = w * 5f
                    animLook = w * 4f
                    sq = .35f * bell
                }
            }
            if (animT >= dur) {
                val cb = onAnimEnd
                anim = ANIM_NONE; onAnimEnd = null
                cb?.invoke()
            }
        }

        target(mood)
        when (a) {
            ANIM_YAWN -> { tmA = 1f; tmC = 0f; tmO = bell; tmW = 10f + 4f * bell }
            ANIM_STRETCH -> { tmA = min(1f, bell * 2f); tmC = 0f; tmO = .5f * bell; tmW = 9f }
            ANIM_SNEEZE -> {
                if (t01 < .55f) { tmA = pre; tmC = 0f; tmO = .25f + .1f * sin(animT * 16f); tmW = 8f + 3f * pre }
                else { tmA = 1f; tmC = 0f; tmO = .15f + .75f * post; tmW = 14f }
            }
            ANIM_SHAKE -> { tmA = bell; tmC = -.3f; tmO = 0f; tmW = 10f }
            else -> {}
        }

        val k = 1f - exp(-dt * 16f)
        for (i in 0..1) {
            ew[i] = ap(ew[i], tw[i], k)
            eh[i] = ap(eh[i], th[i], k)
            ecy[i] = ap(ecy[i], tcy[i], k)
            elid[i] = ap(elid[i], tlid[i], k)
            bA[i] = ap(bA[i], tbA[i], k)
            bR[i] = ap(bR[i], tbR[i], k)
            bG[i] = ap(bG[i], tbG[i], k)
        }
        mA = ap(mA, tmA, k); mC = ap(mC, tmC, k); mO = ap(mO, tmO, k); mW = ap(mW, tmW, k)
        mS = ap(mS, tmS, k); bl = ap(bl, tbl, k)

        val bt = if (blink) 1f else 0f
        blinkK = ap(blinkK, bt, 1f - exp(-dt * (if (blink) 40f else 25f)))
    }

    private fun ap(a: Float, b: Float, k: Float) = a + (b - a) * k
    private fun rnd() = Math.random().toFloat()

    // random glances; sometimes looks to the other side before returning to center
    private fun glance(dt: Float) {
        if ((mood == "normal" || mood == "bored") && anim == ANIM_NONE) {
            nextGlance -= dt
            if (nextGlance <= 0f) {
                val away = lookTx != 0f || lookTy != 0f
                if (away && rnd() > .3f) {
                    lookTx = 0f; lookTy = 0f; nextGlance = 1.5f + rnd() * 3f
                } else if (away) {
                    lookTx = -lookTx; lookTy = (rnd() * 2f - 1f) * 3f; nextGlance = .8f + rnd() * .8f
                } else {
                    lookTx = (if (rnd() < .5f) -1f else 1f) * (3f + rnd() * 4f)
                    lookTy = (rnd() * 2f - 1f) * 3f
                    nextGlance = .8f + rnd() * 1.2f
                }
            }
        } else { lookTx = 0f; lookTy = 0f }
        val k = 1f - exp(-dt * 12f)
        lookX = ap(lookX, lookTx, k); lookY = ap(lookY, lookTy, k)
    }

    // ================= mood targets =================
    private fun eyesT(w: Float, h: Float, cy: Float = 38f, lid: Float = 0f) {
        for (i in 0..1) { tw[i] = w; th[i] = h; tcy[i] = cy; tlid[i] = lid }
    }

    private fun browsT(a: Float, raise: Float = 0f, ang: Float = 0f) {
        for (i in 0..1) { tbA[i] = a; tbR[i] = raise; tbG[i] = ang }
    }

    private fun mouthT(a: Float, curve: Float = 0f, open: Float = 0f, w: Float = 12f, skew: Float = 0f) {
        tmA = a; tmC = curve; tmO = open; tmW = w; tmS = skew
    }

    private fun target(m: String) {
        browsT(0f); mouthT(0f); tbl = 0f
        when (m) {
            "wow" -> { eyesT(20f, 28f); browsT(1f, 3f); mouthT(1f, 0f, .8f, 8f) }
            "sleepy" -> eyesT(16f, 4f, 42f)
            "angry" -> { eyesT(18f, 20f, 38f, 1f); browsT(.9f, 0f, 1f); mouthT(1f, -.5f, 0f, 14f) }
            "sad" -> { eyesT(18f, 20f, 38f, -1f); browsT(.9f, 0f, -1f); mouthT(1f, -.9f, 0f, 12f) }
            "hungry" -> { eyesT(18f, 20f, 38f, -1f); browsT(.9f, 0f, -1f); mouthT(1f, 0f, .5f, 10f) }
            "cry" -> { eyesT(16f, 12f, 40f); browsT(.9f, 0f, -1f); mouthT(1f, -1f, 0f, 14f) }
            "wink" -> {
                eyesT(16f, 22f); th[1] = CLOSED
                tbA[0] = .9f; tbR[0] = 3f
                tbA[1] = .9f; tbR[1] = -1f
                mouthT(1f, .8f, 0f, 14f)
            }
            // ---- new moods ----
            "smug" -> {                      // half-lidded eyes, one brow up, smirk
                eyesT(16f, 11f, 41f)
                tbA[0] = .9f; tbR[0] = 3f
                tbA[1] = .9f; tbR[1] = -1f; tbG[1] = .5f
                mouthT(1f, .5f, 0f, 14f, 2f)
            }
            "bored" -> {                     // heavy eyes, flat mouth, still glances around
                eyesT(16f, 9f, 41f)
                browsT(.7f, -2f, 0f)
                mouthT(1f, -.1f, 0f, 10f)
            }
            "confused" -> {                  // uneven eyes, one raised brow, wobbly mouth
                eyesT(16f, 22f); th[0] = 26f; th[1] = 17f
                tbA[0] = .9f; tbR[0] = 4f
                tbA[1] = .9f; tbR[1] = -1f; tbG[1] = .8f
                mouthT(1f, -.3f, 0f, 9f, -1.5f)
            }
            "shy" -> {                       // lowered eyes, tiny smile, blushing cheeks
                eyesT(16f, 14f, 40f)
                browsT(.8f, 0f, -.6f)
                mouthT(1f, .5f, 0f, 9f)
                tbl = 1f
            }
            "star" -> mouthT(1f, 1f, .6f, 12f)
            // ---- shape moods ----
            "happy" -> mouthT(1f, .8f, 0f, 14f)
            "love" -> mouthT(1f, .7f, 0f, 12f)
            "laugh" -> mouthT(1f, 1f, 1f, 18f)
            "dizzy" -> mouthT(.9f, 0f, .25f, 10f)
            "clock" -> {}
            else -> eyesT(16f, 22f)   // normal
        }
    }

    // ================= drawing =================
    override fun onDraw(c: Canvas) {
        val s = width / 132f
        c.save()
        c.scale(s, s)
        p.shader = null; p.style = Paint.Style.FILL; p.color = 0x40000000
        rf.set(18f, 122f, 114f, 132f); c.drawOval(rf, p)
        c.save()
        c.translate(0f, bob / s)
        c.rotate(tilt + animTilt, 66f, 112f)
        c.scale(sx, sy, 66f, 130f)          // squash/stretch, feet stay on the ground
        p.color = 0xFF2A2D33.toInt()
        rf.set(22f, 112f, 56f, 130f); c.drawRoundRect(rf, 8f, 8f, p)
        rf.set(76f, 112f, 110f, 130f); c.drawRoundRect(rf, 8f, 8f, p)
        p.shader = bodyShader
        rf.set(0f, 0f, 132f, 118f); c.drawRoundRect(rf, 44f, 44f, p)
        p.shader = null
        p.color = scr
        rf.set(10f, 12f, 122f, 96f); c.drawRoundRect(rf, 30f, 30f, p)
        c.save(); c.translate(10f, 12f); c.scale(1.12f, 1.05f)
        face(c)
        c.restore()
        c.restore()
        c.restore()
    }

    private fun face(c: Canvas) {
        ox = gx + lookX + animLook; oy = gy + lookY
        val m = mood
        p.style = Paint.Style.FILL
        p.color = cyan
        if (m in SHAPE) {
            c.save()
            val pop = easeOutBack(min(1f, moodT / .22f))
            c.scale(pop, pop, 50f, 38f)
            if (m == "laugh") c.translate(0f, sin(moodT * 28f) * 1.2f)
            shapeEyes(c, m)
            c.restore()
        } else {
            rectEyes(c)
            if (m == "sleepy") zzz(c)
            if (m == "cry") tears(c)
        }
        blush(c)
        brows(c)
        mouth(c)
        spray(c)
    }

    private fun easeOutBack(x: Float): Float {
        val c1 = 1.70158f; val c3 = c1 + 1f
        val u = x - 1f
        return 1f + c3 * u * u * u + c1 * u * u
    }

    private fun r(c: Canvas, cx: Float, w: Float, h: Float, cy: Float) {
        rf.set(cx - w / 2 + ox, cy - h / 2 + oy, cx + w / 2 + ox, cy + h / 2 + oy)
        val rad = min(w, h) * 0.35f
        c.drawRoundRect(rf, rad, rad, p)
    }

    private fun rectEyes(c: Canvas) {
        val k = max(blinkK, sq)
        p.color = cyan
        for (i in 0..1) {
            val cx = if (i == 0) 30f else 70f
            r(c, cx, ew[i], ap(eh[i], CLOSED, k), ecy[i])
        }
        // lids (angry / sad) drawn in screen color over the eyes
        // FIX: lids now follow the gaze offset (gx/gy), so they stay glued to the eyes
        p.color = scr
        c.save(); c.translate(ox, oy)
        for (i in 0..1) {
            val l = elid[i]
            if (abs(l) < .03f) continue
            val d = 14f * abs(l)
            if (i == 0) {
                if (l > 0) tri(c, 16f, 18f, 44f, 18f, 44f, 18f + d) else tri(c, 16f, 18f, 44f, 18f, 16f, 18f + d)
            } else {
                if (l > 0) tri(c, 84f, 18f, 56f, 18f, 56f, 18f + d) else tri(c, 84f, 18f, 56f, 18f, 84f, 18f + d)
            }
        }
        c.restore()
    }

    private fun zzz(c: Canvas) {
        p.color = cyan
        p.isFakeBoldText = true
        for (j in 0..1) {
            val ph = ((moodT + j * 1.2f) % 2.4f) / 2.4f
            p.alpha = ((1f - ph) * 255).toInt().coerceIn(0, 255)
            p.textSize = 9f + j * 3f + ph * 4f
            c.drawText("z", 76f + j * 6f + ph * 6f + ox, 26f - j * 6f - ph * 10f, p)
        }
        p.isFakeBoldText = false
        p.alpha = 255
    }

    // NEW: falling tears for "cry"
    private fun tears(c: Canvas) {
        val fade = min(1f, moodT / .5f)
        p.style = Paint.Style.FILL
        for (i in 0..1) {
            val cx = (if (i == 0) 30f else 70f) + ox
            for (j in 0..1) {
                val ph = ((moodT + j * .55f + i * .2f) % 1.1f) / 1.1f
                val tx = cx + (if (j == 0) -3f else 3f)
                val y = 46f + oy + ph * 24f
                p.color = 0xFF8FE8FF.toInt()
                p.alpha = ((1f - ph) * fade * 255).toInt().coerceIn(0, 255)
                rf.set(tx - 2f, y - 3f, tx + 2f, y + 3f)
                c.drawOval(rf, p)
            }
        }
        p.alpha = 255
    }

    // NEW: pink cheeks for "shy"
    private fun blush(c: Canvas) {
        if (bl < .03f) return
        p.style = Paint.Style.FILL
        p.color = 0xFFFF7A9A.toInt()
        p.alpha = (bl * 140).toInt().coerceIn(0, 255)
        val dx = ox * .6f; val dy = oy * .6f
        rf.set(9f + dx, 49f + dy, 25f + dx, 58f + dy); c.drawOval(rf, p)
        rf.set(75f + dx, 49f + dy, 91f + dx, 58f + dy); c.drawOval(rf, p)
        p.alpha = 255
    }

    // NEW: sneeze droplets
    private fun spray(c: Canvas) {
        if (spray < .03f) return
        val u = 1f - spray
        p.style = Paint.Style.FILL
        p.color = 0xFFBFEFFF.toInt()
        p.alpha = (spray * 255).toInt().coerceIn(0, 255)
        for (n in 0..2) {
            val x = 52f + ox * .6f + u * (16f + n * 7f)
            val y = 62f + oy * .6f + u * (2f + n * 6f)
            c.drawCircle(x, y, 1.8f, p)
        }
        p.alpha = 255
    }

    private fun brows(c: Canvas) {
        p.style = Paint.Style.STROKE; p.strokeWidth = 3.5f; p.strokeCap = Paint.Cap.ROUND
        for (i in 0..1) {
            val a = bA[i]
            if (a < .05f || mood in SHAPE) continue
            val cx = (if (i == 0) 30f else 70f) + ox
            val sg = if (i == 0) 1f else -1f
            val top = ecy[i] - ap(eh[i], CLOSED, sq) / 2f
            val yb = top - 9f - bR[i] + oy
            val ang = bG[i] * 3.5f
            p.color = cyan
            p.alpha = (a * 255).toInt().coerceIn(0, 255)
            c.drawLine(cx - sg * 8f, yb - ang, cx + sg * 8f, yb + ang, p)
        }
        p.alpha = 255
        p.style = Paint.Style.FILL
    }

    private fun mouth(c: Canvas) {
        if (mA < .04f) return
        val cx = 50f + ox * .6f
        val y = 60f + oy * .6f
        val w = mW
        val openK = min(1f, mO * 6f)
        p.color = cyan
        // closed smile / frown line fades out as the mouth opens
        val lineA = mA * (1f - openK)
        if (lineA > .03f) {
            p.style = Paint.Style.STROKE; p.strokeWidth = 4f; p.strokeCap = Paint.Cap.ROUND
            p.alpha = (lineA * 255).toInt().coerceIn(0, 255)
            path.reset()
            path.moveTo(cx - w / 2, y + mS)                       // mS tilts the line = smirk / confused
            path.quadTo(cx, y + mC * 10f, cx + w / 2, y - mS)
            c.drawPath(path, p)
        }
        if (openK > .03f) {
            p.style = Paint.Style.FILL
            p.alpha = (mA * openK * 255).toInt().coerceIn(0, 255)
            val hh = mO * 7f
            rf.set(cx - w / 2, y - hh, cx + w / 2, y + hh)
            if (mC > .5f) c.drawArc(rf, 0f, 180f, true, p) else c.drawOval(rf, p)
        }
        p.alpha = 255
        p.style = Paint.Style.FILL
    }

    private fun arc(c: Canvas, cx: Float) {
        p.style = Paint.Style.STROKE; p.strokeWidth = 7f; p.strokeCap = Paint.Cap.ROUND
        path.reset(); path.moveTo(cx - 11 + ox, 44 + oy); path.quadTo(cx + ox, 24 + oy, cx + 11 + ox, 44 + oy)
        c.drawPath(path, p)
        p.style = Paint.Style.FILL
    }

    private fun heart(c: Canvas, cx: Float) {
        val a = cx + ox
        val b = 36f + oy
        val pulse = 1f + .08f * sin(moodT * 10f)
        c.save(); c.scale(pulse, pulse, a, b)
        p.color = 0xFFFF6B8A.toInt()
        path.reset()
        path.moveTo(a, b + 14)
        path.cubicTo(a - 22, b - 2, a - 10, b - 16, a, b - 5)
        path.cubicTo(a + 10, b - 16, a + 22, b - 2, a, b + 14)
        path.close()
        c.drawPath(path, p)
        c.restore()
    }

    // NEW: star eyes
    private fun star(c: Canvas, cx: Float) {
        val a = cx + ox
        val b = 38f + oy
        val pulse = 1f + .1f * sin(moodT * 8f)
        c.save()
        c.scale(pulse, pulse, a, b)
        c.rotate(sin(moodT * 3f) * 12f, a, b)
        p.color = 0xFFFFD84D.toInt()
        path.reset()
        for (n in 0 until 10) {
            val rad = if (n % 2 == 0) 14f else 6f
            val ang = (-PI / 2 + n * PI / 5).toFloat()
            val x = a + cos(ang) * rad
            val y = b + sin(ang) * rad
            if (n == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        c.drawPath(path, p)
        c.restore()
    }

    private fun tri(c: Canvas, x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) {
        path.reset(); path.moveTo(x1, y1); path.lineTo(x2, y2); path.lineTo(x3, y3); path.close()
        c.drawPath(path, p)
    }

    private fun shapeEyes(c: Canvas, m: String) {
        p.color = cyan
        when (m) {
            "happy", "laugh" -> { arc(c, 30f); arc(c, 70f) }
            "love" -> { heart(c, 30f); heart(c, 70f) }
            "star" -> { star(c, 30f); star(c, 70f) }
            "clock" -> {
                p.textAlign = Paint.Align.CENTER; p.isFakeBoldText = true
                p.textSize = 30f; c.drawText(clockTime, 50f, 42f, p)
                p.isFakeBoldText = false; p.textSize = 13f; c.drawText(clockDate, 50f, 64f, p)
                p.textAlign = Paint.Align.LEFT
            }
            "dizzy" -> {
                p.style = Paint.Style.STROKE; p.strokeWidth = 3.5f; p.strokeCap = Paint.Cap.ROUND
                // FIX: no per-frame floatArrayOf allocation, angle driven by moodT instead of wall clock
                val a = (moodT * 250f) % 360f
                for (n in 0..1) {
                    val cx = if (n == 0) 30f else 70f
                    rf.set(cx - 10 + ox, 28f + oy, cx + 10 + ox, 48f + oy); c.drawArc(rf, a, 270f, false, p)
                    rf.set(cx - 4 + ox, 34f + oy, cx + 4 + ox, 42f + oy); c.drawArc(rf, -a, 270f, false, p)
                }
                p.style = Paint.Style.FILL
            }
        }
    }
}
