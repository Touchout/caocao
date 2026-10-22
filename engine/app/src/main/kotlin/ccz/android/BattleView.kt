package ccz.android

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.animation.LinearInterpolator
import ccz.battle.Battle
import ccz.battle.BattleSetup
import ccz.battle.BattleUnit
import ccz.battle.Direction
import ccz.battle.Movement
import ccz.battle.Side
import ccz.battle.Sprites
import ccz.battle.UnitInfo
import ccz.data.HexzMap.Companion.TILE
import ccz.io.GameFiles

/**
 * 戰場畫面。點武將看資料和移動範圍；點我軍再點範圍內的格子就移動（走路動畫）。
 * 已行動的我軍變灰，右上角「結束回合」重設。
 */
@SuppressLint("ViewConstructor")
class BattleView(
    context: Context,
    stage: Int,
    private val battle: Battle,
    private val files: GameFiles,
    private val mapImage: Bitmap,
) : View(context) {
    private val data = battle.data
    private val camera = Matrix()
    private val inverse = Matrix()
    private val sprites = HashMap<Pair<Int, Sprites.Frame>, Bitmap?>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val grey = Paint().apply { colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) }); alpha = 200 }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 40f }
    private val endTurnRect = RectF()

    private var selected: BattleUnit? = null
    private var range: Movement.Range? = null
    private var cursor: Pair<Int, Int>? = null
    private var info: List<String> = listOf(Game.tw(data.stageNames.getOrElse(stage) { "" }) + "　點選武將查看資料與移動範圍")

    /** 移動動畫中：單位、路徑、目前進度（以格為單位）。 */
    private var walking: Triple<BattleUnit, List<Pair<Int, Int>>, Float>? = null

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            camera.postScale(d.scaleFactor, d.scaleFactor, d.focusX, d.focusY)
            invalidate()
            return true
        }
    })

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            camera.postTranslate(-dx, -dy)
            invalidate()
            return true
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            if (walking == null) tap(e.x, e.y)
            return true
        }
    })

    init {
        setBackgroundColor(Color.BLACK)
    }

    private fun tap(sx: Float, sy: Float) {
        if (endTurnRect.contains(sx, sy)) {
            battle.endTurn()
            select(null)
            info = listOf("新的回合")
            invalidate()
            return
        }
        camera.invert(inverse)
        val p = floatArrayOf(sx, sy)
        inverse.mapPoints(p)
        val x = Math.floorDiv(p[0].toInt(), TILE)
        val y = Math.floorDiv(p[1].toInt(), TILE)
        cursor = x to y

        val sel = selected
        val r = range
        if (sel != null && r != null && sel.side == Side.PLAYER && !sel.moved && r.reachable(x, y) && battle.unitAt(x, y).let { it == null || it === sel }) {
            val path = battle.move(sel, x, y) ?: return
            animateWalk(sel, path)
            return
        }
        select(battle.unitAt(x, y))
        if (selected == null) info = UnitInfo.tile(data, battle.map, x, y)
        invalidate()
    }

    private fun select(u: BattleUnit?) {
        selected = u
        range = u?.takeIf { !(it.side == Side.PLAYER && it.moved) }?.let { battle.range(it) }
        if (u != null) info = UnitInfo.unit(data, battle.tables, battle.map, u, battle.aiOf[u])
    }

    private fun animateWalk(unit: BattleUnit, path: List<Pair<Int, Int>>) {
        range = null
        if (path.size < 2) {
            select(null)
            invalidate()
            return
        }
        ValueAnimator.ofFloat(0f, (path.size - 1).toFloat()).apply {
            duration = (path.size - 1) * MS_PER_TILE
            interpolator = LinearInterpolator()
            addUpdateListener {
                walking = Triple(unit, path, it.animatedValue as Float)
                invalidate()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    walking = null
                    selected = null
                    info = UnitInfo.unit(data, battle.tables, battle.map, unit, null)
                    invalidate()
                }
            })
            start()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        // 一開始讓整張地圖塞進畫面高度
        val s = h.toFloat() / mapImage.height
        camera.setScale(s, s)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        if (!scaleDetector.isInProgress) gestureDetector.onTouchEvent(event)
        return true
    }

    override fun onDraw(canvas: Canvas) {
        canvas.save()
        canvas.concat(camera)
        canvas.drawBitmap(mapImage, 0f, 0f, null)

        paint.style = Paint.Style.FILL
        range?.let { r ->
            paint.color = if (selected?.side == Side.ENEMY) Color.argb(90, 255, 60, 60) else Color.argb(100, 60, 120, 255)
            for ((x, y) in r.tiles()) canvas.drawRect(tileRect(x, y), paint)
        }

        val walk = walking
        for (u in battle.units) {
            if (walk != null && walk.first === u) continue
            val bmp = sprite(u, Sprites.standFrame(u.direction)) ?: continue
            val p = when {
                u.side == Side.PLAYER && u.moved -> grey
                u.hidden -> paint.apply { alpha = 110 }
                else -> paint.apply { alpha = 255 }
            }
            canvas.drawBitmap(bmp, (u.x * TILE).toFloat(), (u.y * TILE).toFloat(), p)
            paint.alpha = 255
            drawSideBar(canvas, u, u.x * TILE.toFloat(), u.y * TILE.toFloat())
        }
        walk?.let { (u, path, t) -> drawWalking(canvas, u, path, t) }

        cursor?.let { (x, y) ->
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 3f
            paint.color = Color.YELLOW
            canvas.drawRect(tileRect(x, y), paint)
        }
        canvas.restore()
        drawHud(canvas)
    }

    private fun drawWalking(canvas: Canvas, u: BattleUnit, path: List<Pair<Int, Int>>, t: Float) {
        val i = t.toInt().coerceAtMost(path.size - 2)
        val f = t - i
        val (ax, ay) = path[i]
        val (bx, by) = path[i + 1]
        val d = Direction.between(ax, ay, bx, by)
        val x = (ax + (bx - ax) * f) * TILE
        val y = (ay + (by - ay) * f) * TILE
        val step = (t * 4).toInt()
        sprite(u, Sprites.walkFrame(d, step))?.let { canvas.drawBitmap(it, x, y, null) }
        drawSideBar(canvas, u, x, y)
    }

    private fun drawSideBar(canvas: Canvas, u: BattleUnit, x: Float, y: Float) {
        paint.style = Paint.Style.FILL
        paint.color = SIDE_COLORS.getValue(u.side)
        canvas.drawRect(x + 2f, y + TILE - 6f, x + TILE - 2f, y + TILE - 2f, paint)
    }

    private fun drawHud(canvas: Canvas) {
        // 資訊列
        val lineH = text.textSize * 1.3f
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(190, 0, 0, 0)
        canvas.drawRect(0f, height - lineH * info.size - 24f, width.toFloat(), height.toFloat(), paint)
        info.forEachIndexed { i, line -> canvas.drawText(line, 24f, height - lineH * (info.size - i - 1) - 24f, text) }

        // 結束回合
        val label = "結束回合"
        val w = text.measureText(label) + 48f
        endTurnRect.set(width - w - 24f, 24f, width - 24f, 24f + lineH + 24f)
        paint.color = Color.argb(200, 40, 40, 60)
        canvas.drawRoundRect(endTurnRect, 16f, 16f, paint)
        canvas.drawText(label, endTurnRect.left + 24f, endTurnRect.bottom - 30f, text)
    }

    private fun tileRect(x: Int, y: Int) = RectF((x * TILE).toFloat(), (y * TILE).toFloat(), ((x + 1) * TILE).toFloat(), ((y + 1) * TILE).toFloat())

    /** Unit_mov.e5 的一格動作（48×48），洋紅 (247,0,255) 轉透明，向右的動作用水平鏡像。 */
    private fun sprite(u: BattleUnit, frame: Sprites.Frame): Bitmap? {
        val index = BattleSetup.spriteIndex(data, u.character, u.side, battle.setup.imageOverrides)
        return sprites.getOrPut(index to frame) {
            runCatching {
                val strip = files.archive("Unit_mov.e5")[index]
                val full = BitmapFactory.decodeByteArray(strip, 0, strip.size)
                val m = Matrix().apply { if (frame.mirror) setScale(-1f, 1f) }
                val cut = Bitmap.createBitmap(full, 0, frame.index * TILE, TILE, TILE, m, false).copy(Bitmap.Config.ARGB_8888, true)
                val px = IntArray(TILE * TILE)
                cut.getPixels(px, 0, TILE, 0, 0, TILE, TILE)
                for (i in px.indices) if (px[i] and 0xffffff == TRANSPARENT) px[i] = 0
                cut.setPixels(px, 0, TILE, 0, 0, TILE, TILE)
                cut
            }.getOrNull()
        }
    }

    companion object {
        private const val MS_PER_TILE = 140L
        private const val TRANSPARENT = 0xF700FF
        private val SIDE_COLORS = mapOf(
            Side.PLAYER to Color.rgb(230, 60, 60),
            Side.ALLY to Color.rgb(240, 170, 40),
            Side.ENEMY to Color.rgb(60, 120, 240),
        )
    }
}
