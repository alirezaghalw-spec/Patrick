package com.personal.emo

import android.content.Context
import android.graphics.*
import android.view.View
import kotlin.math.min

class EmoView(ctx: Context) : View(ctx) {
    var mood = "normal"
    var blink = false
    var gx = 0f
    var gy = 0f
    var tilt = 0f
    var bob = 0f
    var clockTime = ""
    var clockDate = ""

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val cyan = 0xFF4DE3FF.toInt()
    private val scr = 0xFF07080A.toInt()
    private val rf = RectF()
    private val path = Path()
    private val bodyShader = LinearGradient(0f, 0f, 132f, 118f, 0xFFECEBE6.toInt(), 0xFFCFCDC6.toInt(), Shader.TileMode.CLAMP)

    override fun onDraw(c: Canvas) {
        val s = width / 132f
        c.save()
        c.scale(s, s)
        p.shader = null; p.style = Paint.Style.FILL; p.color = 0x40000000
        rf.set(18f, 122f, 114f, 132f); c.drawOval(rf, p)
        c.save()
        c.translate(0f, bob / s)
        c.rotate(tilt, 66f, 112f)
        p.color = 0xFF2A2D33.toInt()
        rf.set(22f, 112f, 56f, 130f); c.drawRoundRect(rf, 8f, 8f, p)
        rf.set(76f, 112f, 110f, 130f); c.drawRoundRect(rf, 8f, 8f, p)
        p.shader = bodyShader
        rf.set(0f, 0f, 132f, 118f); c.drawRoundRect(rf, 44f, 44f, p)
        p.shader = null
        p.color = scr
        rf.set(10f, 12f, 122f, 96f); c.drawRoundRect(rf, 30f, 30f, p)
        c.save(); c.translate(10f, 12f); c.scale(1.12f, 1.05f)
        eyes(c)
        c.restore()
        c.restore()
        c.restore()
    }

    private fun r(c: Canvas, cx: Float, w: Float, h: Float, cy: Float = 38f) {
        rf.set(cx - w / 2 + gx, cy - h / 2 + gy, cx + w / 2 + gx, cy + h / 2 + gy)
        val rad = min(w, h) * 0.35f
        c.drawRoundRect(rf, rad, rad, p)
    }

    private fun arc(c: Canvas, cx: Float) {
        p.style = Paint.Style.STROKE; p.strokeWidth = 7f; p.strokeCap = Paint.Cap.ROUND
        path.reset(); path.moveTo(cx - 11 + gx, 44 + gy); path.quadTo(cx + gx, 24 + gy, cx + 11 + gx, 44 + gy)
        c.drawPath(path, p)
        p.style = Paint.Style.FILL
    }

    private fun heart(c: Canvas, cx: Float) {
        val a = cx + gx
        val b = 36f + gy
        p.color = 0xFFFF6B8A.toInt()
        path.reset()
        path.moveTo(a, b + 14)
        path.cubicTo(a - 22, b - 2, a - 10, b - 16, a, b - 5)
        path.cubicTo(a + 10, b - 16, a + 22, b - 2, a, b + 14)
        path.close()
        c.drawPath(path, p)
    }

    private fun tri(c: Canvas, x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) {
        path.reset(); path.moveTo(x1, y1); path.lineTo(x2, y2); path.lineTo(x3, y3); path.close()
        c.drawPath(path, p)
    }

    private fun eyes(c: Canvas) {
        val m = if (blink && mood == "normal") "blink" else mood
        p.color = cyan
        when (m) {
            "blink" -> { r(c, 30f, 16f, 3f); r(c, 70f, 16f, 3f) }
            "happy" -> { arc(c, 30f); arc(c, 70f) }
            "love" -> { heart(c, 30f); heart(c, 70f) }
            "sleepy" -> {
                r(c, 30f, 16f, 4f, 42f); r(c, 70f, 16f, 4f, 42f)
                p.textSize = 14f; c.drawText("z", 78f, 22f, p)
                p.textSize = 10f; c.drawText("z", 86f, 12f, p)
            }
            "angry" -> {
                r(c, 30f, 18f, 20f); r(c, 70f, 18f, 20f)
                p.color = scr
                tri(c, 16f, 18f, 44f, 18f, 44f, 32f)
                tri(c, 84f, 18f, 56f, 18f, 56f, 32f)
            }
            "clock" -> {
                p.textAlign = Paint.Align.CENTER; p.isFakeBoldText = true
                p.textSize = 30f; c.drawText(clockTime, 50f, 42f, p)
                p.isFakeBoldText = false; p.textSize = 13f; c.drawText(clockDate, 50f, 64f, p)
                p.textAlign = Paint.Align.LEFT
            }
            "laugh" -> {
                arc(c, 30f); arc(c, 70f)
                p.style = Paint.Style.FILL
                rf.set(42f + gx, 52f + gy, 58f + gx, 66f + gy); c.drawArc(rf, 0f, 180f, true, p)
            }
            "wink" -> { r(c, 30f, 16f, 22f); arc(c, 70f) }
            "cry" -> {
                r(c, 30f, 16f, 12f, 40f); r(c, 70f, 16f, 12f, 40f)
                val ph = (System.currentTimeMillis() % 900) / 900f
                rf.set(27f, 50f + ph * 14f, 33f, 58f + ph * 14f); c.drawOval(rf, p)
                rf.set(67f, 50f + ph * 14f, 73f, 58f + ph * 14f); c.drawOval(rf, p)
            }
            "sad", "hungry" -> {
                r(c, 30f, 18f, 20f); r(c, 70f, 18f, 20f)
                p.color = scr
                tri(c, 16f, 18f, 44f, 18f, 16f, 32f)
                tri(c, 84f, 18f, 56f, 18f, 84f, 32f)
                if (m == "hungry") {
                    p.color = cyan; p.style = Paint.Style.STROKE; p.strokeWidth = 4f
                    rf.set(42f, 54f, 58f, 68f); c.drawOval(rf, p)
                    p.style = Paint.Style.FILL
                }
            }
            "dizzy" -> {
                p.style = Paint.Style.STROKE; p.strokeWidth = 3.5f; p.strokeCap = Paint.Cap.ROUND
                val a = (System.currentTimeMillis() / 4 % 360).toFloat()
                for (cx in floatArrayOf(30f, 70f)) {
                    rf.set(cx - 10 + gx, 28f + gy, cx + 10 + gx, 48f + gy); c.drawArc(rf, a, 270f, false, p)
                    rf.set(cx - 4 + gx, 34f + gy, cx + 4 + gx, 42f + gy); c.drawArc(rf, -a, 270f, false, p)
                }
                p.style = Paint.Style.FILL
            }
            "wow" -> { r(c, 30f, 20f, 28f); r(c, 70f, 20f, 28f) }
            else -> { r(c, 30f, 16f, 22f); r(c, 70f, 16f, 22f) }
        }
    }
}
