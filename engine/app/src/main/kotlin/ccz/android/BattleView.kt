package ccz.android

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import ccz.battle.BattleSetup
import ccz.battle.Placement
import ccz.battle.Side
import ccz.battle.UnitInfo
import ccz.data.GameData
import ccz.data.HexzMap
import ccz.data.HexzMap.Companion.TILE
import ccz.io.GameFiles

@SuppressLint("ViewConstructor")
class BattleView(
    context: Context,
    stage: Int,
    private val data: GameData,
    private val files: GameFiles,
    private val setup: BattleSetup,
    private val map: HexzMap,
    private val mapImage: Bitmap,
) : View(context) {
    private val matrix = Matrix()
    private val inverse = Matrix()
    private val sprites = HashMap<Int, Bitmap?>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 40f }
    private var selected: Pair<Int, Int>? = null
    private var info: List<String> = listOf(Game.tw(data.stageNames.getOrElse(stage) { "" }) + "　點選武將或地形查看資料")

    /** 我軍出場格：強制出場的武將依序排上去，其餘格子留空。 */
    private val players: List<Placement> = setup.playerSlots.sortedBy { it.index }.mapIndexedNotNull { i, slot ->
        setup.forcedPlayers.getOrNull(i)?.let { Placement(it, Side.PLAYER, slot.x, slot.y, slot.direction, 0, false) }
    }
    private val units = setup.units + players

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            matrix.postScale(d.scaleFactor, d.scaleFactor, d.focusX, d.focusY)
            invalidate()
            return true
        }
    })

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            matrix.postTranslate(-dx, -dy)
            invalidate()
            return true
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            matrix.invert(inverse)
            val p = floatArrayOf(e.x, e.y)
            inverse.mapPoints(p)
            val x = Math.floorDiv(p[0].toInt(), TILE)
            val y = Math.floorDiv(p[1].toInt(), TILE)
            selected = x to y
            val u = units.firstOrNull { it.x == x && it.y == y }
            info = if (u != null) UnitInfo.unit(data, map, u) else UnitInfo.tile(data, map, x, y)
            invalidate()
            return true
        }
    })

    init {
        setBackgroundColor(Color.BLACK)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        // 一開始讓整張地圖塞進畫面高度
        val s = h.toFloat() / mapImage.height
        matrix.setScale(s, s)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        if (!scaleDetector.isInProgress) gestureDetector.onTouchEvent(event)
        return true
    }

    override fun onDraw(canvas: Canvas) {
        canvas.save()
        canvas.concat(matrix)
        canvas.drawBitmap(mapImage, 0f, 0f, null)

        paint.style = Paint.Style.FILL
        paint.color = Color.argb(70, 60, 140, 255)
        for (slot in setup.playerSlots) canvas.drawRect(tileRect(slot.x, slot.y), paint)

        for (u in units) {
            val bmp = sprite(u) ?: continue
            paint.alpha = if (u.hidden) 110 else 255
            canvas.drawBitmap(bmp, (u.x * TILE).toFloat(), (u.y * TILE).toFloat(), paint)
            paint.alpha = 255
            paint.color = SIDE_COLORS.getValue(u.side)
            canvas.drawRect(u.x * TILE + 2f, u.y * TILE + TILE - 6f, u.x * TILE + TILE - 2f, u.y * TILE + TILE - 2f, paint)
        }

        selected?.let { (x, y) ->
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 3f
            paint.color = Color.YELLOW
            canvas.drawRect(tileRect(x, y), paint)
        }
        canvas.restore()

        // 資訊列（螢幕座標）
        val lineH = text.textSize * 1.3f
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(190, 0, 0, 0)
        canvas.drawRect(0f, height - lineH * info.size - 24f, width.toFloat(), height.toFloat(), paint)
        info.forEachIndexed { i, line -> canvas.drawText(line, 24f, height - lineH * (info.size - i - 1) - 24f, text) }
    }

    private fun tileRect(x: Int, y: Int) = RectF((x * TILE).toFloat(), (y * TILE).toFloat(), ((x + 1) * TILE).toFloat(), ((y + 1) * TILE).toFloat())

    /** Unit_mov.e5 的移動圖：48×48 一格，縱向 11 格；先固定取第 7 格（正面站立）。洋紅 (247,0,255) 是透明色。 */
    private fun sprite(u: Placement): Bitmap? {
        val index = BattleSetup.spriteIndex(data, u.character, u.side, setup.imageOverrides)
        return sprites.getOrPut(index) {
            runCatching {
                val strip = files.archive("Unit_mov.e5")[index]
                val full = BitmapFactory.decodeByteArray(strip, 0, strip.size)
                val frame = Bitmap.createBitmap(full, 0, FRAME_IDLE * TILE, TILE, TILE).copy(Bitmap.Config.ARGB_8888, true)
                val px = IntArray(TILE * TILE)
                frame.getPixels(px, 0, TILE, 0, 0, TILE, TILE)
                for (i in px.indices) if (px[i] and 0xffffff == TRANSPARENT) px[i] = 0
                frame.setPixels(px, 0, TILE, 0, 0, TILE, TILE)
                frame
            }.getOrNull()
        }
    }

    companion object {
        private const val FRAME_IDLE = 6
        private const val TRANSPARENT = 0xF700FF
        private val SIDE_COLORS = mapOf(
            Side.PLAYER to Color.rgb(230, 60, 60),
            Side.ALLY to Color.rgb(240, 170, 40),
            Side.ENEMY to Color.rgb(60, 120, 240),
        )
    }
}
