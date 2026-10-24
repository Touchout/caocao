package ccz.android

import android.animation.ValueAnimator
import android.app.Activity
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
import ccz.battle.BattleSession
import ccz.battle.BattleSetup
import ccz.battle.BattleUnit
import ccz.battle.Direction
import ccz.battle.Movement
import ccz.battle.Side
import ccz.battle.Sprites
import ccz.battle.UnitInfo
import ccz.data.HexzMap
import ccz.data.HexzMap.Companion.TILE
import ccz.io.GameFiles
import ccz.script.ScriptEvent
import java.util.concurrent.Executors

/**
 * 戰場畫面。先執行戰場劇本的開戰處理（對話、選擇、劇本移動武將），跑完後進入操作：
 * 點武將看資料和移動範圍；點我軍再點範圍內的格子就移動；右上角「結束回合」。
 */
@SuppressLint("ViewConstructor")
class BattleView(
    context: Context,
    private val session: BattleSession,
    private val files: GameFiles,
) : View(context) {
    private val data = session.data
    private val battle: Battle? get() = session.battle
    private var mapImage: Bitmap? = null

    private val camera = Matrix()
    private val inverse = Matrix()
    private val sprites = HashMap<Pair<Int, Sprites.Frame>, Bitmap?>()
    private val faces = HashMap<Int, Bitmap?>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val grey = Paint().apply { colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) }); alpha = 200 }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 40f }
    private val nameText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 220, 120); textSize = 40f; isFakeBoldText = true }
    private val endTurnRect = RectF()
    private val guideRect = RectF()

    /** 「歷史情報」目前顯示的頁；null 表示沒打開。 */
    private var guidePage: Int? = null
    private val guideLines: List<String>
        get() = session.vm.guide.sortedBy { it.order }.flatMap { Game.tw(it.body).split('\n') }
    private val optionRects = ArrayList<RectF>()

    /** 劇本目前要顯示的事件；null 表示開戰處理跑完、可以操作。 */
    private var event: ScriptEvent? = null
    private var scripting = true
    /** 戰鬥結束（或出錯）：再點一下回到選單。 */
    private var finished = false

    private var selected: BattleUnit? = null
    private var range: Movement.Range? = null
    private var cursor: Pair<Int, Int>? = null
    private var info: List<String> = emptyList()

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
            if (walking != null || busy) return true
            if (scripting) tapScript(e.x, e.y) else tapBattle(e.x, e.y)
            return true
        }
    })

    init {
        setBackgroundColor(Color.BLACK)
        post { advance() }
    }

    // ---------- 劇本 ----------

    /** 劇本在背景執行緒跑（事件檢查可能要一點時間），結果回到主執行緒處理。 */
    private val worker = Executors.newSingleThreadExecutor()
    /** 背景正在執行劇本／回合流程，不接受操作。 */
    private var busy = false

    /** 往下執行劇本與回合流程，直到需要畫面處理的事件。 */
    private fun advance() {
        busy = true
        worker.execute {
            val r = runCatching {
                var e = session.next()
                if (e == null && session.flow == null && session.battle != null) {
                    session.startTurns()    // 開戰處理跑完，進入回合（原版 0x44EEE5）
                    e = session.next()
                }
                e
            }
            post {
                busy = false
                r.onSuccess { handle(it) }.onFailure { e ->
                    event = ScriptEvent.Message("劇本執行錯誤：${e.message}", 0)
                    scripting = true
                    finished = true
                    invalidate()
                }
            }
        }
    }

    private fun handle(e: ScriptEvent?) {
        ensureMap()
        val b = battle
        when (e) {
            null -> {
                // 戰場事件播完回到操作；單位剛移動完（沒有事件）時保留它的資料
                if (scripting && session.playerPhase && b != null) info = listOf("第 ${b.turn} 回合　我軍行動中")
                scripting = false
                event = null
                invalidate()
            }
            is ScriptEvent.Moved -> {
                val u = b?.unitOf(e.character)
                if (u != null && mapImage != null) {
                    val path = if (e.path.size >= 2) e.path.map { (it and 0xff) to ((it shr 8) and 0xff) } else straightPath(e.fromX, e.fromY, e.toX, e.toY)
                    animateWalk(u, path) { advance() }
                }
                else advance()
            }
            is ScriptEvent.TurnStart -> {
                scripting = true
                event = ScriptEvent.Message("第 ${e.turn} 回合　" + (if (e.side == 0) "我軍" else "敵軍") + "階段", 0)
                invalidate()
                val shown = event
                postDelayed({ if (event === shown && !busy) advance() }, BANNER_MS)
            }
            ScriptEvent.PlayerPhase -> {
                b?.startPlayerPhase()
                select(null)
                scripting = false
                event = null
                info = listOf(Game.tw(data.stageNames.getOrElse(session.stage) { "" }) + "　第 ${b?.turn ?: 1} 回合：我軍行動",
                    "點選武將移動，完成後按「結束回合」")
                invalidate()
            }
            is ScriptEvent.BattleEnd -> {
                scripting = true
                finished = true
                event = ScriptEvent.Message(when (e.result) {
                    2 -> "戰鬥結束（勝利）"
                    3 -> "戰鬥失敗"
                    else -> "戰鬥結束"
                } + "\n點一下回到選單", 0)
                invalidate()
            }
            else -> {
                scripting = true
                event = e
                invalidate()
            }
        }
    }

    private fun tapScript(x: Float, y: Float) {
        if (busy) return
        if (finished) { (context as? Activity)?.finish(); return }
        when (val e = event) {
            is ScriptEvent.Choice, is ScriptEvent.YesNo -> {
                val i = optionRects.indexOfFirst { it.contains(x, y) }
                if (i < 0) return
                session.answer(if (e is ScriptEvent.YesNo) (if (i == 0) 1 else 0) else i + 1)
                advance()
            }
            else -> advance()
        }
    }

    private fun ensureMap() {
        val b = battle ?: return
        if (mapImage != null) return
        mapImage = BitmapFactory.decodeFile(files.find(HexzMap.imagePath(b.map.id))!!.absolutePath)
        fitHeight()
    }

    private fun straightPath(fx: Int, fy: Int, tx: Int, ty: Int): List<Pair<Int, Int>> {
        val out = arrayListOf(fx to fy)
        var x = fx
        var y = fy
        while (x != tx) { x += if (tx > x) 1 else -1; out += x to y }
        while (y != ty) { y += if (ty > y) 1 else -1; out += x to y }
        return out
    }

    // ---------- 操作 ----------

    private fun tapBattle(sx: Float, sy: Float) {
        val b = battle ?: return
        guidePage?.let { page ->
            guidePage = if ((page + 1) * linesPerPage() < guideLines.size) page + 1 else null
            invalidate()
            return
        }
        if (guideRect.contains(sx, sy)) {
            if (guideLines.isEmpty()) info = listOf("這一關沒有歷史情報") else guidePage = 0
            invalidate()
            return
        }
        if (endTurnRect.contains(sx, sy)) {
            if (!session.playerPhase || busy) return
            select(null)
            session.endPlayerPhase()
            advance()
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
        if (sel != null && r != null && sel.side == Side.PLAYER && !sel.moved && r.reachable(x, y) && b.unitAt(x, y).let { it == null || it === sel }) {
            val path = b.move(sel, x, y) ?: return
            range = null
            animateWalk(sel, path) {
                selected = null
                info = UnitInfo.unit(data, b.tables, b.map, sel, null)
                session.afterAction()    // 原版 0x4065D9：單位行動後檢查戰場事件
                advance()
            }
            return
        }
        select(b.unitAt(x, y))
        if (selected == null) info = UnitInfo.tile(data, b.map, x, y)
        invalidate()
    }

    private fun select(u: BattleUnit?) {
        val b = battle ?: return
        selected = u
        range = u?.takeIf { !(it.side == Side.PLAYER && it.moved) }?.let { b.range(it) }
        if (u != null) info = UnitInfo.unit(data, b.tables, b.map, u, b.aiOf[u.character])
    }

    private fun animateWalk(unit: BattleUnit, path: List<Pair<Int, Int>>, done: () -> Unit) {
        if (path.size < 2) {
            done()
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
                    done()
                    invalidate()
                }
            })
            start()
        }
    }

    // ---------- 繪製 ----------

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        // 直向螢幕比較窄，字跟著縮，一行對話才放得下
        val size = minOf(40f, minOf(w, h) / 27f)
        text.textSize = size
        nameText.textSize = size
        fitHeight()
    }

    /** 一開始讓地圖塞進畫面：橫向對齊高度（左右可以拖），直向對齊寬度（上下可以拖）。 */
    private fun fitHeight() {
        val img = mapImage ?: return
        if (height == 0) return
        val s = if (width >= height) height.toFloat() / img.height else width.toFloat() / img.width
        camera.setScale(s, s)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        if (!scaleDetector.isInProgress) gestureDetector.onTouchEvent(event)
        return true
    }

    override fun onDraw(canvas: Canvas) {
        val b = battle
        val img = mapImage
        if (b != null && img != null) drawField(canvas, b, img)
        if (scripting) drawScript(canvas) else drawHud(canvas)
    }

    private fun drawField(canvas: Canvas, b: Battle, img: Bitmap) {
        canvas.save()
        canvas.concat(camera)
        canvas.drawBitmap(img, 0f, 0f, null)

        paint.style = Paint.Style.FILL
        range?.let { r ->
            paint.color = if (selected?.side == Side.ENEMY) Color.argb(90, 255, 60, 60) else Color.argb(100, 60, 120, 255)
            for ((x, y) in r.tiles()) canvas.drawRect(tileRect(x, y), paint)
        }

        val walk = walking
        for (u in b.units) {
            if (u.hidden || (walk != null && walk.first === u)) continue
            val bmp = sprite(b, u, Sprites.standFrame(u.direction)) ?: continue
            val p = if (u.side == Side.PLAYER && u.moved) grey else paint.apply { alpha = 255 }
            canvas.drawBitmap(bmp, (u.x * TILE).toFloat(), (u.y * TILE).toFloat(), p)
            drawSideBar(canvas, u, u.x * TILE.toFloat(), u.y * TILE.toFloat())
        }
        walk?.let { (u, path, t) -> drawWalking(canvas, b, u, path, t) }

        cursor?.let { (x, y) ->
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 3f
            paint.color = Color.YELLOW
            canvas.drawRect(tileRect(x, y), paint)
        }
        canvas.restore()
    }

    private fun drawWalking(canvas: Canvas, b: Battle, u: BattleUnit, path: List<Pair<Int, Int>>, t: Float) {
        val i = t.toInt().coerceAtMost(path.size - 2)
        val f = t - i
        val (ax, ay) = path[i]
        val (bx, by) = path[i + 1]
        val d = Direction.between(ax, ay, bx, by)
        val x = (ax + (bx - ax) * f) * TILE
        val y = (ay + (by - ay) * f) * TILE
        sprite(b, u, Sprites.walkFrame(d, (t * 4).toInt()))?.let { canvas.drawBitmap(it, x, y, null) }
        drawSideBar(canvas, u, x, y)
    }

    private fun drawSideBar(canvas: Canvas, u: BattleUnit, x: Float, y: Float) {
        paint.style = Paint.Style.FILL
        paint.color = SIDE_COLORS.getValue(u.side)
        canvas.drawRect(x + 2f, y + TILE - 6f, x + TILE - 2f, y + TILE - 2f, paint)
    }

    /** 劇本事件：對話框、訊息、選擇題。 */
    private fun drawScript(canvas: Canvas) {
        val e = event ?: return
        optionRects.clear()
        val lineH = text.textSize * 1.35f
        paint.style = Paint.Style.FILL
        when (e) {
            is ScriptEvent.Dialogue -> {
                val lines = Game.tw(e.text).split('\n')
                val boxH = maxOf(FACE + 48f, lineH * (lines.size + 1) + 48f)
                val top = height - boxH
                paint.color = Color.argb(215, 10, 10, 30)
                canvas.drawRect(0f, top, width.toFloat(), height.toFloat(), paint)
                var x = 24f
                e.speaker?.let { face(session.face(it)) }?.let {
                    canvas.drawBitmap(it, null, RectF(24f, top + 24f, 24f + FACE, top + 24f + FACE), null)
                    x += FACE + 24f
                }
                val name = Game.tw(e.speaker?.let { data.characters.getOrNull(it)?.name } ?: e.name)
                canvas.drawText(name, x, top + 24f + nameText.textSize, nameText)
                lines.forEachIndexed { i, l -> canvas.drawText(l.trim(), x, top + 24f + nameText.textSize + lineH * (i + 1), text) }
            }
            is ScriptEvent.Message -> {
                val lines = Game.tw(e.text).split('\n')
                val w = (lines.maxOf { text.measureText(it) } + 96f).coerceAtMost(width - 48f)
                val h = lineH * lines.size + 64f
                val r = RectF((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2)
                paint.color = Color.argb(225, 20, 20, 40)
                canvas.drawRoundRect(r, 20f, 20f, paint)
                lines.forEachIndexed { i, l -> canvas.drawText(l, r.left + 48f, r.top + 32f + text.textSize + lineH * i, text) }
            }
            is ScriptEvent.Choice -> drawOptions(canvas, e.options.map { Game.tw(it) }, lineH)
            is ScriptEvent.YesNo -> drawOptions(canvas, listOf("是", "否"), lineH)
            else -> {}
        }
    }

    private fun drawOptions(canvas: Canvas, options: List<String>, lineH: Float) {
        val w = (options.maxOf { text.measureText(it) } + 120f).coerceAtLeast(360f)
        val h = lineH + 40f
        val total = options.size * (h + 20f)
        var y = (height - total) / 2
        for (o in options) {
            val r = RectF((width - w) / 2, y, (width + w) / 2, y + h)
            paint.color = Color.argb(230, 40, 40, 80)
            canvas.drawRoundRect(r, 20f, 20f, paint)
            canvas.drawText(o, r.left + 60f, r.centerY() + text.textSize / 3, text)
            optionRects += r
            y += h + 20f
        }
    }

    private fun drawHud(canvas: Canvas) {
        val lineH = text.textSize * 1.3f
        if (info.isNotEmpty()) {
            paint.style = Paint.Style.FILL
            paint.color = Color.argb(190, 0, 0, 0)
            canvas.drawRect(0f, height - lineH * info.size - 24f, width.toFloat(), height.toFloat(), paint)
            info.forEachIndexed { i, line -> canvas.drawText(line, 24f, height - lineH * (info.size - i - 1) - 24f, text) }
        }
        button(canvas, endTurnRect, "結束回合", width - 24f, lineH)
        button(canvas, guideRect, "情報", endTurnRect.left - 24f, lineH)
        guidePage?.let { drawGuide(canvas, it) }
    }

    /** 右上角的按鈕，right 是右邊界。 */
    private fun button(canvas: Canvas, r: RectF, label: String, right: Float, lineH: Float) {
        val w = text.measureText(label) + 48f
        val top = topInset() + 24f
        r.set(right - w, top, right, top + lineH + 24f)
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(200, 40, 40, 60)
        canvas.drawRoundRect(r, 16f, 16f, paint)
        canvas.drawText(label, r.left + 24f, r.bottom - 30f, text)
    }

    /** 狀態列（含瀏海）的高度：按鈕要放在它下面才按得到。 */
    @Suppress("DEPRECATION")
    private fun topInset(): Float {
        val insets = rootWindowInsets ?: return 0f
        val cut = if (android.os.Build.VERSION.SDK_INT >= 28) insets.displayCutout?.safeInsetTop ?: 0 else 0
        return maxOf(insets.systemWindowInsetTop, insets.stableInsetTop, cut).toFloat()
    }

    private fun linesPerPage() = maxOf(1, ((height - 160f - topInset()) / (text.textSize * 1.3f)).toInt())

    /** 歷史情報（劇本用 0x16 寫進來的攻略），點一下換頁，最後一頁再點關閉。 */
    private fun drawGuide(canvas: Canvas, page: Int) {
        val lines = guideLines
        val per = linesPerPage()
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(235, 10, 10, 30)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        canvas.drawText("歷史情報　（${page + 1} / ${(lines.size + per - 1) / per}）點一下繼續", 48f, topInset() + 72f, nameText)
        lines.drop(page * per).take(per).forEachIndexed { i, l ->
            canvas.drawText(l, 48f, topInset() + 140f + text.textSize * 1.3f * i, text)
        }
    }

    private fun tileRect(x: Int, y: Int) = RectF((x * TILE).toFloat(), (y * TILE).toFloat(), ((x + 1) * TILE).toFloat(), ((y + 1) * TILE).toFloat())

    /** Unit_mov.e5 的一格動作（48×48），洋紅 (247,0,255) 轉透明，向右的動作用水平鏡像。 */
    private fun sprite(b: Battle, u: BattleUnit, frame: Sprites.Frame): Bitmap? {
        val index = BattleSetup.spriteIndex(data, u.character, u.unitClass, u.side, b.imageOverrides)
        return sprites.getOrPut(index to frame) {
            runCatching {
                val strip = files.archive("Unit_mov.e5")[index]
                val full = BitmapFactory.decodeByteArray(strip, 0, strip.size)
                val m = Matrix().apply { if (frame.mirror) setScale(-1f, 1f) }
                keyOut(Bitmap.createBitmap(full, 0, frame.index * TILE, TILE, TILE, m, false))
            }.getOrNull()
        }
    }

    /** E5/Face.e5 的頭像（120×120）。 */
    private fun face(index: Int): Bitmap? = faces.getOrPut(index) {
        runCatching {
            val b = files.archive("E5/Face.e5")[index]
            keyOut(BitmapFactory.decodeByteArray(b, 0, b.size))
        }.getOrNull()
    }

    private fun keyOut(src: Bitmap): Bitmap {
        val bmp = src.copy(Bitmap.Config.ARGB_8888, true)
        val px = IntArray(bmp.width * bmp.height)
        bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        for (i in px.indices) if (px[i] and 0xffffff == TRANSPARENT) px[i] = 0
        bmp.setPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        return bmp
    }

    companion object {
        private const val MS_PER_TILE = 140L
        /** 回合提示自動消失的時間。 */
        private const val BANNER_MS = 1200L
        private const val FACE = 180f
        private const val TRANSPARENT = 0xF700FF
        private val SIDE_COLORS = mapOf(
            Side.PLAYER to Color.rgb(230, 60, 60),
            Side.ALLY to Color.rgb(240, 170, 40),
            Side.ENEMY to Color.rgb(60, 120, 240),
        )
    }
}
