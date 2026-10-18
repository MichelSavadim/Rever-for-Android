package com.rever.reader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.LruCache
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.OverScroller
import org.json.JSONArray
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Continuous PDF pages with pinch zoom and lazy bitmap rendering. */
class PdfCanvasView(context: Context) : View(context) {
    private val background = Paint().apply { color = Color.rgb(231, 234, 239) }
    private val paper = Paint().apply { color = Color.WHITE }
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val highlightPaint = Paint().apply { color = 0x69FFD700 }
    private val searchPaint = Paint().apply { color = 0x66FF9800 }
    private val cache = object : LruCache<Int, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: Int, value: Bitmap): Int = value.byteCount
        override fun entryRemoved(evicted: Boolean, key: Int, oldValue: Bitmap, newValue: Bitmap?) {
            if (oldValue !== newValue && !oldValue.isRecycled) oldValue.recycle()
        }
    }
    private val rendererLock = Any()
    private val renderWorker = Executors.newSingleThreadExecutor()
    private var descriptor: ParcelFileDescriptor? = null
    private var renderer: PdfRenderer? = null
    private var sizes: List<Pair<Int, Int>> = emptyList()
    private var heightRatios = doubleArrayOf(0.0)
    @Volatile private var generation = 0
    private val pending = mutableSetOf<Int>()
    private val fling = OverScroller(context)
    private var scrollPixels = 0f
    private var horizontalPixels = 0f
    private var zoom = 1f
    private var lastScaleFocusX = 0f
    private var lastScaleFocusY = 0f
    private val margin = 16f * resources.displayMetrics.density
    private val gap = 14f * resources.displayMetrics.density
    var onPageTap: ((page: Int, x: Float, y: Float) -> Unit)? = null
    var onVisiblePageChanged: ((page: Int) -> Unit)? = null
    var onViewportMoved: (() -> Unit)? = null
    private var visiblePage = 0
    private var selectedPage = 0
    private var selectedBox: RectF? = null
    private var searchBoxes: Map<Int, List<RectF>> = emptyMap()

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            lastScaleFocusX = detector.focusX
            lastScaleFocusY = detector.focusY
            return true
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val old = zoom
            val newZoom = (zoom * detector.scaleFactor).coerceIn(1f, 4f)
            val oldWidth = max(1f, width - 2 * margin) * old
            val contentY = scrollPixels + lastScaleFocusY
            val anchorPage = pageIndexAt(contentY, oldWidth)
            val pageTop = pageTop(anchorPage, oldWidth)
            val pageHeight = if (sizes.isEmpty()) 1f else oldWidth *
                sizes[anchorPage].second / sizes[anchorPage].first
            val relativeY = ((contentY - pageTop) / pageHeight).coerceIn(0f, 1f)
            val oldLeft = (width - oldWidth) / 2f - horizontalPixels
            val relativeX = (lastScaleFocusX - oldLeft) / oldWidth
            zoom = newZoom
            val newWidth = max(1f, width - 2 * margin) * zoom
            val newHeight = if (sizes.isEmpty()) 1f else newWidth *
                sizes[anchorPage].second / sizes[anchorPage].first
            scrollPixels = (pageTop(anchorPage) + relativeY * newHeight - detector.focusY)
                .coerceIn(0f, maxScroll())
            horizontalPixels = ((width - newWidth) / 2f - detector.focusX + relativeX * newWidth)
                .coerceIn(-maxHorizontalScroll(), maxHorizontalScroll())
            lastScaleFocusX = detector.focusX
            lastScaleFocusY = detector.focusY
            onViewportMoved?.invoke()
            invalidate()
            return true
        }
    })
    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            fling.forceFinished(true)
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            if (!scaleDetector.isInProgress) {
                scrollPixels = (scrollPixels + distanceY).coerceIn(0f, maxScroll())
                horizontalPixels = (horizontalPixels + distanceX)
                    .coerceIn(-maxHorizontalScroll(), maxHorizontalScroll())
                onViewportMoved?.invoke()
                invalidate()
            }
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
            fling.fling(0, scrollPixels.toInt(), 0, -velocityY.toInt(), 0, 0, 0, maxScroll().toInt())
            postInvalidateOnAnimation()
            return true
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            val location = pageAt(e.x, e.y) ?: return false
            onPageTap?.invoke(location.first, location.second.first, location.second.second)
            performClick()
            return true
        }
    })
    private var hadMultiplePointers = false

    fun open(file: File) {
        closeDocument()
        val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        val pdf = try { PdfRenderer(fd) } catch (error: Exception) {
            fd.close()
            throw error
        }
        val count = pdf.pageCount
        val first = if (count > 0) pdf.openPage(0).use { it.width to it.height } else 1 to 1
        synchronized(rendererLock) {
            descriptor = fd
            renderer = pdf
        }
        // Reuse dimensions from earlier openings; large books need not be measured again.
        val sizeFile = File(file.parentFile, "page_sizes.json")
        val savedSizes = try {
            val array = JSONArray(sizeFile.readText(Charsets.UTF_8))
            if (array.length() != count) null else List(count) { index ->
                val pair = array.getJSONArray(index)
                pair.getInt(0).takeIf { it > 0 }?.let { width ->
                    pair.getInt(1).takeIf { it > 0 }?.let { height -> width to height }
                } ?: throw IllegalArgumentException("Dimensões inválidas")
            }
        } catch (_: Exception) { null }
        sizes = savedSizes ?: List(count) { first }
        rebuildHeightRatios()
        scrollPixels = 0f
        horizontalPixels = 0f
        zoom = 1f
        selectedPage = 0
        selectedBox = null
        searchBoxes = emptyMap()
        visiblePage = 0
        invalidate()
        if (count == 0 || savedSizes != null) return
        requestPage(0, max(720, width))
        val token = generation
        measurePageSizes(pdf, sizeFile, token, count, ArrayList(count), 0)
    }

    private fun measurePageSizes(pdf: PdfRenderer, sizeFile: File, token: Int, count: Int,
                                 measured: ArrayList<Pair<Int, Int>>, from: Int) {
        renderWorker.execute {
            val until = min(from + 4, count)
            for (index in from until until) {
                val dimension = synchronized(rendererLock) {
                    if (token != generation || renderer !== pdf) return@execute
                    pdf.openPage(index).use { it.width to it.height }
                }
                measured.add(dimension)
            }
            if (until < count) {
                measurePageSizes(pdf, sizeFile, token, count, measured, until)
            } else {
                if (token != generation) return@execute
                try {
                    val data = JSONArray().apply {
                        measured.forEach { (width, height) -> put(JSONArray().put(width).put(height)) }
                    }
                    val partial = File(sizeFile.parentFile, "${sizeFile.name}.tmp")
                    partial.writeText(data.toString(), Charsets.UTF_8)
                    if (!partial.renameTo(sizeFile)) partial.delete()
                } catch (_: Exception) { /* Dimensions will be measured on the next opening. */ }
                post {
                    if (token == generation) {
                        val pageToKeep = visiblePage.coerceIn(1, measured.size)
                        val offset = scrollPixels - pageTop(pageToKeep - 1)
                        sizes = measured
                        rebuildHeightRatios()
                        scrollPixels = (pageTop(pageToKeep - 1) + offset).coerceIn(0f, maxScroll())
                        invalidate()
                    }
                }
            }
        }
    }

    fun closeDocument() {
        generation++
        synchronized(rendererLock) {
            renderer?.close()
            descriptor?.close()
            renderer = null
            descriptor = null
        }
        sizes = emptyList()
        heightRatios = doubleArrayOf(0.0)
        pending.clear()
        cache.evictAll()
        invalidate()
    }

    fun clearRenderCache() {
        generation++
        pending.clear()
        cache.evictAll()
        invalidate()
    }

    fun setHighlight(page: Int, box: RectF?) {
        selectedPage = page
        selectedBox = box
        invalidate()
    }

    fun setSearchMatches(matches: List<Paragraph>, query: String) {
        searchBoxes = matches.groupBy { it.page }
            .mapValues { (_, values) -> values.flatMap { it.matchBoxes(query) } }
        invalidate()
    }

    fun setDarkMode(enabled: Boolean) {
        background.color = if (enabled) 0xFF121922.toInt() else Color.rgb(231, 234, 239)
        invalidate()
    }

    fun showPage(page: Int) {
        if (page !in 1..sizes.size) return
        scrollPixels = pageTop(page - 1).coerceIn(0f, maxScroll())
        onViewportMoved?.invoke()
        requestPage(page - 1, max(720, width))
        invalidate()
    }

    fun centerOn(page: Int, box: RectF) {
        if (page !in 1..sizes.size || box.isEmpty) {
            showPage(page)
            return
        }
        val size = sizes[page - 1]
        val fitWidth = max(1f, width - 2 * margin) * zoom
        val pageHeight = fitWidth * size.second / size.first
        val paragraphY = ((box.top + box.bottom) / 2f).coerceIn(0f, size.second.toFloat())
        val centerY = pageTop(page - 1) + paragraphY * pageHeight / size.second
        scrollPixels = (centerY - height / 2f).coerceIn(0f, maxScroll())
        onViewportMoved?.invoke()
        requestPage(page - 1, max(720, width))
        invalidate()
    }

    val pageCount: Int get() = sizes.size
    val isZoomed: Boolean get() = zoom > 1.05f

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(background.color)
        if (sizes.isEmpty()) return
        val fitWidth = max(1f, width - 2 * margin) * zoom
        val firstIndex = pageIndexAt(scrollPixels, fitWidth)
        var y = pageTop(firstIndex, fitWidth) - scrollPixels
        var firstVisible = 0
        var foundVisible = false
        for (index in firstIndex until sizes.size) {
            val size = sizes[index]
            val pageWidth = fitWidth
            val pageHeight = fitWidth * size.second / size.first
            val left = (width - pageWidth) / 2f - horizontalPixels
            val pageRect = RectF(left, y, left + pageWidth, y + pageHeight)
            if (pageRect.bottom >= 0 && pageRect.top <= height) {
                if (!foundVisible) { firstVisible = index + 1; foundVisible = true }
                canvas.drawRect(pageRect, paper)
                val bitmap = cache.get(index)
                val desiredWidth = safeBitmapWidth(index, min(2048, max(512, pageWidth.toInt())))
                if (bitmap != null && !bitmap.isRecycled) {
                    canvas.drawBitmap(bitmap, null, pageRect, bitmapPaint)
                    if (bitmap.width < desiredWidth * 0.75f) requestPage(index, desiredWidth)
                } else {
                    requestPage(index, desiredWidth)
                }
                for (box in searchBoxes[index + 1].orEmpty()) {
                    canvas.drawRect(
                        left + box.left * pageWidth / size.first,
                        y + box.top * pageHeight / size.second,
                        left + box.right * pageWidth / size.first,
                        y + box.bottom * pageHeight / size.second,
                        searchPaint
                    )
                }
                if (selectedPage == index + 1) {
                    selectedBox?.let { box ->
                        canvas.drawRect(
                            left + box.left * pageWidth / size.first,
                            y + box.top * pageHeight / size.second,
                            left + box.right * pageWidth / size.first,
                            y + box.bottom * pageHeight / size.second,
                            highlightPaint
                        )
                    }
                }
            }
            y += pageHeight + gap
            if (y > height && foundVisible) break
        }
        if (foundVisible && firstVisible != visiblePage) {
            visiblePage = firstVisible
            onVisiblePageChanged?.invoke(firstVisible)
        }
        if (fling.computeScrollOffset()) {
            scrollPixels = fling.currY.toFloat()
            onViewportMoved?.invoke()
            postInvalidateOnAnimation()
        }
    }

    private fun requestPage(index: Int, widthPixels: Int) {
        if (!pending.add(index)) return
        val token = generation
        val safeWidth = safeBitmapWidth(index, widthPixels)
        renderWorker.execute {
            var image: Bitmap? = null
            try {
                synchronized(rendererLock) {
                    val pdf = renderer
                    if (token == generation && pdf != null) {
                        pdf.openPage(index).use { page ->
                            val requestedHeight = max(1, safeWidth.toLong() * page.height / page.width)
                            val reduction = min(1.0, sqrt(4_000_000.0 /
                                (safeWidth.toDouble() * requestedHeight)))
                            val bitmapWidth = max(1, (safeWidth * reduction).toInt())
                            val heightPixels = max(1, (requestedHeight * reduction).toInt())
                            image = Bitmap.createBitmap(bitmapWidth, heightPixels, Bitmap.Config.ARGB_8888)
                            image!!.eraseColor(Color.WHITE)
                            val matrix = Matrix().apply {
                                postScale(bitmapWidth.toFloat() / page.width, heightPixels.toFloat() / page.height)
                            }
                            page.render(image!!, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        }
                    }
                }
            } catch (_: Exception) {
                image?.recycle()
                image = null
            }
            post {
                pending.remove(index)
                if (token == generation && image != null) {
                    cache.put(index, image!!)
                    invalidate()
                } else {
                    image?.recycle()
                }
            }
        }
    }

    private fun safeBitmapWidth(index: Int, requested: Int): Int {
        val dimensions = sizes.getOrNull(index) ?: return requested
        val cap = sqrt(4_000_000.0 * dimensions.first / dimensions.second).toInt()
        return min(requested, max(1, cap))
    }

    private fun rebuildHeightRatios() {
        heightRatios = DoubleArray(sizes.size + 1)
        for (index in sizes.indices) {
            heightRatios[index + 1] = heightRatios[index] +
                sizes[index].second.toDouble() / sizes[index].first
        }
    }

    private fun pageTop(index: Int, fitWidth: Float = max(1f, width - 2 * margin) * zoom): Float =
        (margin + fitWidth * heightRatios[index] + gap * index).toFloat()

    private fun pageIndexAt(contentY: Float, fitWidth: Float): Int {
        if (sizes.isEmpty()) return 0
        var low = 0
        var high = sizes.lastIndex
        while (low < high) {
            val middle = (low + high) ushr 1
            val bottom = pageTop(middle, fitWidth) +
                fitWidth * sizes[middle].second / sizes[middle].first
            if (bottom < contentY) low = middle + 1 else high = middle
        }
        return low
    }

    private fun maxScroll(): Float {
        if (sizes.isEmpty()) return 0f
        val last = sizes.last()
        val total = pageTop(sizes.lastIndex) + max(1f, width - 2 * margin) * zoom * last.second / last.first + margin
        return max(0f, total - height)
    }

    private fun maxHorizontalScroll(): Float = max(
        0f, (max(1f, width - 2 * margin) * zoom - width) / 2f + margin
    )

    private fun pageAt(x: Float, y: Float): Pair<Int, Pair<Float, Float>>? {
        if (sizes.isEmpty()) return null
        val fitWidth = max(1f, width - 2 * margin) * zoom
        val index = pageIndexAt(scrollPixels + y, fitWidth)
        val size = sizes[index]
        val top = pageTop(index, fitWidth) - scrollPixels
        val pageHeight = fitWidth * size.second / size.first
        val left = (width - fitWidth) / 2f - horizontalPixels
        if (x !in left..(left + fitWidth) || y !in top..(top + pageHeight)) return null
        return (index + 1) to ((x - left) * size.first / fitWidth to
            (y - top) * size.second / pageHeight)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) hadMultiplePointers = false
        if (event.pointerCount > 1 || event.actionMasked == MotionEvent.ACTION_POINTER_DOWN) {
            hadMultiplePointers = true
            fling.forceFinished(true)
        }
        scaleDetector.onTouchEvent(event)
        if (!hadMultiplePointers) gestureDetector.onTouchEvent(event)
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
