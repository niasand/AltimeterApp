package com.altimeter.app

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

/**
 * 圆形高度计仪表盘自定义 View
 * 包含：外圈光晕、橙红渐变圆盘、海拔数值、速度、指南针方向标
 */
class AltimeterGaugeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // ---------- 数据 ----------
    var altitude: Int = 3132
        set(value) { field = value; invalidate() }

    var speed: Int = 79
        set(value) { field = value; invalidate() }

    var compassDegree: Float = 0f
        set(value) { field = value; invalidate() }

    // ---------- 画笔 ----------
    private val outerGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val outerRingPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val innerCirclePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val altitudeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val unitTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.LEFT
        isFakeBoldText = true
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CCFFFFFF")
        textAlign = Paint.Align.CENTER
    }
    private val speedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CCFFFFFF")
        textAlign = Paint.Align.CENTER
    }
    private val compassPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    // ---------- 尺寸缓存 ----------
    private var cx = 0f
    private var cy = 0f
    private var outerRadius = 0f
    private var innerRadius = 0f

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        cx = w / 2f
        cy = h / 2f
        val size = min(w, h)
        outerRadius = size / 2f * 0.92f
        innerRadius = size / 2f * 0.68f

        // 外圈渐变 — 半透明白色到透明
        outerRingPaint.shader = RadialGradient(
            cx, cy, outerRadius,
            intArrayOf(
                Color.parseColor("#44FFFFFF"),
                Color.parseColor("#22FFFFFF"),
                Color.parseColor("#00FFFFFF")
            ),
            floatArrayOf(0.6f, 0.85f, 1f),
            Shader.TileMode.CLAMP
        )

        // 内圆渐变 — 从顶部橙色到底部红色
        innerCirclePaint.shader = LinearGradient(
            cx, cy - innerRadius, cx, cy + innerRadius,
            intArrayOf(
                Color.parseColor("#FF9F43"),  // 顶部亮橙
                Color.parseColor("#FF6B3A"),  // 中间橙红
                Color.parseColor("#EE4035")   // 底部红
            ),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )

        // 文字大小
        altitudeTextPaint.textSize = innerRadius * 0.55f
        unitTextPaint.textSize = innerRadius * 0.22f
        labelPaint.textSize = innerRadius * 0.16f
        speedPaint.textSize = innerRadius * 0.14f
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // 1) 外圈光晕
        canvas.drawCircle(cx, cy, outerRadius, outerRingPaint)

        // 外圈细线
        val ringStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f
            color = Color.parseColor("#33FFFFFF")
        }
        canvas.drawCircle(cx, cy, outerRadius, ringStrokePaint)

        // 2) 内圆（橙红渐变）
        canvas.drawCircle(cx, cy, innerRadius, innerCirclePaint)

        // 内圆顶部高光
        val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                cx, cy - innerRadius * 0.3f, innerRadius * 0.8f,
                intArrayOf(
                    Color.parseColor("#33FFFFFF"),
                    Color.parseColor("#00FFFFFF")
                ),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawCircle(cx, cy - innerRadius * 0.1f, innerRadius * 0.8f, highlightPaint)

        // 3) "当前海拔" 标签
        val labelY = cy - innerRadius * 0.28f
        canvas.drawText("当前海拔", cx, labelY, labelPaint)

        // 4) 海拔数字 + 米
        val altStr = altitude.toString()
        val altY = cy + innerRadius * 0.12f

        // 测量数字宽度
        val numberBounds = Rect()
        altitudeTextPaint.getTextBounds(altStr, 0, altStr.length, numberBounds)
        val numberWidth = altitudeTextPaint.measureText(altStr)

        // 测量"米"宽度
        val unitWidth = unitTextPaint.measureText("米")
        val gap = innerRadius * 0.02f

        // 总宽度居中
        val totalWidth = numberWidth + gap + unitWidth
        val startX = cx - totalWidth / 2f

        // 画数字（左对齐）
        val numPaint = Paint(altitudeTextPaint).apply { textAlign = Paint.Align.LEFT }
        canvas.drawText(altStr, startX, altY, numPaint)

        // 画"米"（基线对齐到数字底部）
        val unitX = startX + numberWidth + gap
        canvas.drawText("米", unitX, altY, unitTextPaint)

        // 5) 当前速度
        val speedY = cy + innerRadius * 0.38f
        canvas.drawText("当前速度 ${speed}km/h", cx, speedY, speedPaint)

        // 6) 指南针方向标记 S 和 N
        drawCompassMarkers(canvas)
    }

    private fun drawCompassMarkers(canvas: Canvas) {
        canvas.save()
        canvas.rotate(-compassDegree, cx, cy)

        val markerRadius = outerRadius * 0.97f
        val flagSize = outerRadius * 0.10f

        // S 标记 (上方偏左 — 对应南方在上)
        val sAngle = 200.0  // 大约在左上
        val sRad = Math.toRadians(sAngle)
        val sx = cx + markerRadius * Math.cos(sRad).toFloat()
        val sy = cy + markerRadius * Math.sin(sRad).toFloat()
        drawFlag(canvas, sx, sy, "S", Color.parseColor("#88FFFFFF"), flagSize)

        // N 标记 (下方偏右 — 对应北方在下)
        val nAngle = 20.0
        val nRad = Math.toRadians(nAngle)
        val nx = cx + markerRadius * Math.cos(nRad).toFloat()
        val ny = cy + markerRadius * Math.sin(nRad).toFloat()
        drawFlag(canvas, nx, ny, "N", Color.parseColor("#FFEE4035"), flagSize)

        canvas.restore()
    }

    private fun drawFlag(canvas: Canvas, x: Float, y: Float, text: String, color: Int, size: Float) {
        val path = Path().apply {
            moveTo(x, y - size * 0.5f)
            lineTo(x + size * 0.8f, y - size * 0.25f)
            lineTo(x, y)
            close()
        }
        compassPaint.apply {
            this.color = color
            style = Paint.Style.FILL
        }
        canvas.drawPath(path, compassPaint)

        // 旗杆
        compassPaint.apply {
            strokeWidth = 2f
            style = Paint.Style.STROKE
        }
        canvas.drawLine(x, y - size * 0.6f, x, y + size * 0.3f, compassPaint)

        // 字母
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            textSize = size * 0.5f
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }
        canvas.drawText(text, x, y + size * 0.75f, textPaint)
    }
}
