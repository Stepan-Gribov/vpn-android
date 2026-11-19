package com.v2ray.ang.simple

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import kotlin.math.cos
import kotlin.math.sin

/** A single 24 dp grid replaces font-dependent Unicode symbols throughout the UI. */
internal class SimpleIconView(context: Context, private val kind: Kind, color: Int) : View(context) {
    enum class Kind { SETTINGS, PLUS, PLAY, STOP, MORE, REFRESH, DOWN, UP, CHECK, CIRCLE, REMOVE }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        strokeWidth = 1.8f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        style = Paint.Style.STROKE
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val extent = minOf(width - paddingLeft - paddingRight, height - paddingTop - paddingBottom).toFloat()
        if (extent <= 0f) return
        canvas.save()
        canvas.translate((width - extent) / 2f, (height - extent) / 2f)
        canvas.scale(extent / 24f, extent / 24f)
        paint.alpha = if (isEnabled) 255 else 100
        paint.style = Paint.Style.STROKE
        when (kind) {
            Kind.PLUS -> { canvas.drawLine(5f, 12f, 19f, 12f, paint); canvas.drawLine(12f, 5f, 12f, 19f, paint) }
            Kind.MORE -> {
                paint.style = Paint.Style.FILL
                listOf(5f, 12f, 19f).forEach { canvas.drawCircle(it, 12f, 1.6f, paint) }
            }
            Kind.PLAY -> {
                paint.style = Paint.Style.FILL
                canvas.drawPath(Path().apply { moveTo(8f, 5f); lineTo(20f, 12f); lineTo(8f, 19f); close() }, paint)
            }
            Kind.STOP -> { paint.style = Paint.Style.FILL; canvas.drawRoundRect(6f, 6f, 18f, 18f, 2f, 2f, paint) }
            Kind.REFRESH -> {
                canvas.drawArc(4f, 4f, 20f, 20f, -35f, 285f, false, paint)
                canvas.drawPath(Path().apply { moveTo(15.5f, 4f); lineTo(20f, 4f); lineTo(20f, 8.5f) }, paint)
            }
            Kind.DOWN, Kind.UP -> {
                if (kind == Kind.UP) canvas.rotate(180f, 12f, 12f)
                canvas.drawLine(12f, 4f, 12f, 20f, paint)
                canvas.drawPath(Path().apply { moveTo(6f, 14f); lineTo(12f, 20f); lineTo(18f, 14f) }, paint)
            }
            Kind.CHECK -> canvas.drawPath(Path().apply { moveTo(5f, 12f); lineTo(10f, 17f); lineTo(19f, 7f) }, paint)
            Kind.CIRCLE -> canvas.drawCircle(12f, 12f, 8f, paint)
            Kind.REMOVE -> { canvas.drawCircle(12f, 12f, 8f, paint); canvas.drawLine(8f, 12f, 16f, 12f, paint) }
            Kind.SETTINGS -> {
                val outline = Path()
                repeat(48) { i ->
                    val angle = i * Math.PI / 24
                    val radius = if (i % 6 in 1..3) 10f else 8f
                    val x = 12f + radius * cos(angle).toFloat()
                    val y = 12f + radius * sin(angle).toFloat()
                    if (i == 0) outline.moveTo(x, y) else outline.lineTo(x, y)
                }
                outline.close(); canvas.drawPath(outline, paint); canvas.drawCircle(12f, 12f, 3.5f, paint)
            }
        }
        canvas.restore()
    }
}
