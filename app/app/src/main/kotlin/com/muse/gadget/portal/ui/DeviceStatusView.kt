package com.muse.gadget.portal.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * A readable status surface for a shared-room display. Its ring and meter describe only local,
 * observed device state; they never imply that Muse is thinking, listening, or speaking elsewhere.
 */
class DeviceStatusView(context: Context) : View(context) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG)
    private var phase = "CONNECTING"
    private var caption = "Opening the secure connection"
    private var level = 0f
    private var progress = 0f

    init {
        contentDescription = "$phase. $caption"
        minimumHeight = context.dp(108)
    }

    fun show(phase: String, caption: String, level: Float = 0f, progress: Float = 0f) {
        this.phase = phase
        this.caption = caption
        this.level = level.coerceIn(0f, 1f)
        this.progress = progress.coerceIn(0f, 1f)
        contentDescription = "$phase. $caption"
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            resolveSize(context.dp(300), widthMeasureSpec),
            resolveSize(context.dp(118), heightMeasureSpec),
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val width = width.toFloat()
        val height = height.toFloat()
        val radius = min(height * 0.3f, context.dp(35).toFloat())
        val centerX = context.dp(18).toFloat() + radius
        val centerY = height / 2f
        val accent = when (phase) {
            "LISTENING" -> PortalPalette.ACCENT
            "SPEAKING" -> PortalPalette.LILAC
            "SENT" -> PortalPalette.ACCENT
            "OFFLINE" -> PortalPalette.DANGER
            "READY" -> PortalPalette.ACCENT
            else -> PortalPalette.LILAC
        }

        fill.color = PortalPalette.SURFACE_ELEVATED
        canvas.drawRoundRect(RectF(0f, 0f, width, height), context.dp(22).toFloat(), context.dp(22).toFloat(), fill)
        stroke.color = PortalPalette.BORDER
        stroke.strokeWidth = context.dp(1).toFloat()
        canvas.drawRoundRect(RectF(0.5f, 0.5f, width - 0.5f, height - 0.5f), context.dp(22).toFloat(), context.dp(22).toFloat(), stroke)

        stroke.strokeWidth = context.dp(5).toFloat()
        stroke.color = Color.rgb(62, 79, 105)
        canvas.drawCircle(centerX, centerY, radius, stroke)
        stroke.color = accent
        val sweep = if (phase == "LISTENING") 360f * progress else 270f
        canvas.drawArc(RectF(centerX - radius, centerY - radius, centerX + radius, centerY + radius), -90f, sweep, false, stroke)

        if (phase == "LISTENING") {
            fill.color = Color.argb(34 + (level * 70).toInt(), Color.red(accent), Color.green(accent), Color.blue(accent))
            canvas.drawCircle(centerX, centerY, max(context.dp(12).toFloat(), radius * (0.34f + level * 0.38f)), fill)
        }
        drawMeter(canvas, centerX, centerY, accent)

        val startX = centerX + radius + context.dp(20)
        text.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        text.textSize = context.spPx(14f)
        text.color = accent
        text.letterSpacing = 0.08f
        canvas.drawText(phase, startX, centerY - context.dp(13), text)
        text.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        text.textSize = context.spPx(15f)
        text.color = PortalPalette.TEXT_PRIMARY
        text.letterSpacing = 0f
        drawWrappedText(canvas, caption, startX, centerY + context.dp(10), width - startX - context.dp(16), 2)
    }

    private fun drawMeter(canvas: Canvas, x: Float, y: Float, accent: Int) {
        val barWidth = context.dp(4).toFloat()
        val spacing = context.dp(7).toFloat()
        fill.color = accent
        for (index in 0 until 5) {
            val position = index - 2
            val target = if (phase == "LISTENING") {
                context.dp(9).toFloat() + level * context.dp(28) * (1f - abs(position) / 4.5f)
            } else {
                context.dp(8).toFloat()
            }
            val barHeight = max(context.dp(7).toFloat(), target)
            val barX = x + position * spacing
            canvas.drawRoundRect(
                barX - barWidth / 2f,
                y - barHeight / 2f,
                barX + barWidth / 2f,
                y + barHeight / 2f,
                barWidth,
                barWidth,
                fill,
            )
        }
    }

    private fun drawWrappedText(canvas: Canvas, value: String, x: Float, baseline: Float, maxWidth: Float, maxLines: Int) {
        if (maxWidth <= 0f) return
        val words = value.trim().split(Regex("\\s+"))
        val lines = mutableListOf<String>()
        var line = ""
        for (word in words) {
            val candidate = if (line.isEmpty()) word else "$line $word"
            if (text.measureText(candidate) <= maxWidth || line.isEmpty()) {
                line = candidate
            } else {
                lines += line
                line = word
                if (lines.size == maxLines) break
            }
        }
        if (line.isNotEmpty() && lines.size < maxLines) lines += line
        lines.take(maxLines).forEachIndexed { index, lineText ->
            canvas.drawText(lineText, x, baseline + index * context.dp(20), text)
        }
    }
}
