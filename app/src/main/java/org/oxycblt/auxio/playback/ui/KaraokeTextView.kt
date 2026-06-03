/*
 * Copyright (c) 2024 Auxio Project
 * KaraokeTextView.kt is part of Auxio.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.oxycblt.auxio.playback.ui

import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.text.TextPaint
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.animation.AnimationUtils
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.graphics.withClip
import androidx.core.graphics.withSave
import org.oxycblt.auxio.playback.LyricLine
import org.oxycblt.auxio.playback.LyricSpan
import org.oxycblt.auxio.playback.TimedLyrics
import kotlin.math.max
import timber.log.Timber as L

class KaraokeTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatTextView(context, attrs, defStyleAttr) {

    private var timedLyrics: TimedLyrics? = null
    private var lyricLineId: Int = -1

    private var prevLyricLineId: Int = -1;
    private var lastLyricLineIdChangeTime: Long = 0;
    private var lastLyricLineIdTime: Float = 0.0f;
    private var verticalOffset: Float = 0.0f;
    private var lastVerticalOffset: Float = 0.0f;

    private var positionMs: Long = 0

    private var lastFrameTime: Long = 0
    private var isAnimating = false
    private var isPlaying = false

    fun startAnimation(isPlaying: Boolean) {
        this.isPlaying = isPlaying
        if (isAnimating) {
            return
        }
        isAnimating = true
        lastFrameTime = AnimationUtils.currentAnimationTimeMillis()
        postOnAnimation(animationRunnable)
    }

    fun stopAnimation() {
        isAnimating = false
        isPlaying = false
        removeCallbacks(animationRunnable)
    }

    private val animationRunnable = object : Runnable {
        override fun run() {
            if (!isAnimating) return

            // 1. Calculate how much time has passed since the last frame
            val now = AnimationUtils.currentAnimationTimeMillis()
            val elapsed = now - lastFrameTime
            lastFrameTime = now

            // 2. Manually advance the internal positionMs
            // (Assuming the music is playing)
            positionMs += elapsed
            //L.e("delta time: $elapsed")

            // 3. Redraw immediately
            invalidate()

            // 4. Schedule next frame
            postOnAnimation(this)
        }
    }

    private val activePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.LEFT
    }

    private val inactivePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.LEFT
        //maskFilter = BlurMaskFilter(8.0f, BlurMaskFilter.Blur.NORMAL)
    }

    fun setTimedLyrics(timedLyrics: TimedLyrics?) {
        L.d("AUXIOKE: Setting timed lyrics")
        this.timedLyrics = timedLyrics
        userScrollOffset = 0f
        postInvalidateOnAnimation()
    }

    fun setPosition(positionMs: Long) {
        this.positionMs = positionMs
        postInvalidateOnAnimation()
    }

    fun lerp(a: Float, b: Float, x: Float): Float {
        var t: Float = x
        if (t > 1.0f) t = 1.0f else if (t < 0.0f) t = 0.0f
        return a + (b - a) * t
    }

    private var userScrollOffset: Float = 0.0f
    private val gestureDetector =
        GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float
            ): Boolean {
                // This tells parent views not to intercept this touch stream
                parent?.requestDisallowInterceptTouchEvent(true)

                userScrollOffset -= distanceY
                invalidate()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                userScrollOffset = 0f // Reset scroll on double tap
                invalidate()
                return true
            }
        })

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (timedLyrics == null || timedLyrics!!.lines.isEmpty()) {
            return super.onTouchEvent(event)
        }
        return gestureDetector.onTouchEvent(event) || super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        // Early-out checks
        if (timedLyrics == null || timedLyrics!!.lines.isEmpty()) {
            L.e("AUXIOKE: no lyrics (weird)")
            if (timedLyrics == null)
                L.e("AUXIOKE: timedLyrics is null")
            else if (timedLyrics!!.lines.isEmpty())
                L.e("AUXIOKE: timedLyrics is empty")

            if (isAnimating && !isPlaying) {
                stopAnimation()
            }

            super.onDraw(canvas)
            return
        }

        // Init text
        activePaint.textSize = textSize
        activePaint.typeface = typeface
        activePaint.color = currentTextColor

        inactivePaint.textSize = textSize
        inactivePaint.typeface = typeface
        inactivePaint.color = currentTextColor
        inactivePaint.alpha = 128 // 50% opacity for the "background" text

        // Init lyrics
        initLyrics(canvas)

        // Cache
        val now = AnimationUtils.currentAnimationTimeMillis()

        // Get current line id
        var newLyricLineId = 0
        for (i in 1 until timedLyrics!!.lines.count()) {
            if (positionMs < timedLyrics!!.lines[i].startTime - 200.0) {
                break
            } else {
                ++newLyricLineId
            }
        }

        //var getHeightMs = 0.0
        if (newLyricLineId != lyricLineId) {
            prevLyricLineId = lyricLineId
            lyricLineId = newLyricLineId
            lastLyricLineIdChangeTime = now
            lastVerticalOffset = verticalOffset
            verticalOffset = -getLineHeight(canvas, timedLyrics!!.lines, lineHeight / 2.0f, lyricLineId)
            lastLyricLineIdTime = 0.0f;
        }

        // Stop animation (for example, when we just change the time while playing is paused)
        if (lastLyricLineIdTime >= 0.9999f && isAnimating && !isPlaying) {
            stopAnimation()
        }

        // Get time (t) from last time we changed stuff (500ms)
        lastLyricLineIdTime = ((now - lastLyricLineIdChangeTime).toDouble() / 300.0).toFloat()
        if (lastLyricLineIdTime > 1.0f)
            lastLyricLineIdTime = 1.0f
        else if (lastLyricLineIdTime < 0.0f)
            lastLyricLineIdTime = 0.0f

        // Draw a line (multi-line)
        val y = baseline.toFloat()
        val yStartScroll = userScrollOffset
        val yStartT = lastLyricLineIdTime * lastLyricLineIdTime * (3f - 2f * lastLyricLineIdTime)
        val yStart = lerp(lastVerticalOffset, verticalOffset,yStartT) + yStartScroll

         for (lineId in 0 until timedLyrics!!.lines.count()) {
            val line = timedLyrics!!.lines[lineId]
            val yOffset = y + yStart
            if (isLineVisible(canvas, yOffset, line)) {
                drawLine(canvas, lineId, yOffset, line.spans.count() == 1 && line.spans[0].isFullLine)
            }
        }
    }

    fun initLyrics(canvas: Canvas) {
        var lineY = 0.0f

        // Iterate through each line
        for (line in timedLyrics!!.lines) {
            // Setup the line's rect
            line.rect.x = 40.0f
            line.rect.y = lineY

            var skipSpanId = 0
            var skipPartId = 0

            // Iterate through each span
            var spanX = line.rect.x
            for (spanId in 0 until line.spans.size) {
                val span = line.spans[spanId]

                // Early-out if the parts have already been initialized
                if (span.partsInitialized)
                    return;

                // Setup the span's rect
                span.rect.x = spanX
                span.rect.y = lineY

                // Iterate through each parts of the span (a span can be a word to an entire line)
                var partX = spanX
                for (partId in 0 until span.parts.size) {
                    val part = span.parts[partId]

                    // Get part's width
                    val width = activePaint.measureText(part.text)

                    // Get the actual full word width
                    var currentSpanWidth = 0.0f
                    if (spanId >= skipSpanId && partId >= skipPartId) {
                        val fullWordWidth = getFullWordWidth(line.spans, spanId, partId)
                        currentSpanWidth = fullWordWidth.first
                        skipSpanId = fullWordWidth.second
                        skipPartId = fullWordWidth.third
                    } else {
                        currentSpanWidth = 0.0f
                    }

                    // Check if this is too long and if we need to return to the line
                    if (partX + currentSpanWidth > canvas.width - 80.0f) {
                        span.rect.x = line.rect.x
                        span.rect.width += spanX

                        spanX = span.rect.x
                        partX = spanX
                        lineY += lineHeight
                    }

                    // Set the rect data
                    part.rect.x = partX
                    part.rect.y = lineY
                    part.rect.width = width
                    part.rect.height = lineHeight.toFloat()

                    // Update the span's rect
                    span.rect.width = max(span.rect.width, partX + width)
                    span.rect.height = max(span.rect.height, lineY - span.rect.y)

                    // Move right
                    partX += width
                    spanX += width
                }

                // Update the line's rect
                line.rect.width = max(line.rect.width, span.rect.x + span.rect.width)
                line.rect.height = max(line.rect.height, lineY - line.rect.y)

                // Mark the span as initialized
                span.partsInitialized = true
            }

            // Return to line
            lineY += lineHeight + (lineHeight / 2.0f)
        }
    }

    fun getLineHeight(canvas: Canvas, lines: List<LyricLine>, lineOffset: Float, targetId: Int): Float {
        var verticalOffset = canvas.height / 4.0f;
        if (lyricLineId >= 0 && lyricLineId < lines.size) {
            verticalOffset += lines[lyricLineId].rect.y
        }
        return verticalOffset
    }

    fun getFullWordWidth (spans: List<LyricSpan>, spanId: Int, partId: Int): Triple<Float, Int, Int> {
        var totalWidth = 0.0f
        var spanIdOffset = spanId
        var partIdOffset = partId
        for (i in spanId until spans.count()) {
            val span = spans[i]
            partIdOffset = if (i == spanId) partId else 0
            for (j in partId until span.parts.count()) {
                val part = span.parts[j]

                totalWidth += activePaint.measureText(part.text)

                // Improved boundary check:
                // 1. Check if the part ends with whitespace or a punctuation break
                // 2. Check if the next part exists and starts with a space
                if (part.text.endsWith(" ") || part.text.endsWith("-") || part.text.endsWith("\n")) {
                    return Triple(totalWidth, spanIdOffset, partIdOffset)
                }

                ++partIdOffset
            }
            ++spanIdOffset
        }

        return Triple(totalWidth, spanIdOffset, partIdOffset)
    }

    fun isLineVisible(canvas: Canvas, yOffset: Float, line: LyricLine): Boolean {
        return line.rect.y + line.rect.height + yOffset > 0.0f && line.rect.y - line.rect.height + yOffset < canvas.height
    }

    fun drawLine(canvas: Canvas, lineId: Int, yOffset: Float, allowWordByWord: Boolean) {
        val line = timedLyrics!!.lines[lineId]

        // Calculate delta
        val delta: Int = lineId - lyricLineId

        // Draw text
        if (delta > 0) {
            drawLineFull(canvas, line, yOffset, inactivePaint) // Inactive text
        } else if (delta >= -1 && allowWordByWord) {
            drawLineWord(canvas, line, yOffset) // Currently played text
        } else {
            drawLineFull(canvas, line, yOffset, activePaint) // Already played text
        }
    }

    fun drawLineFull(canvas: Canvas, line:LyricLine, yOffset:Float, paint: Paint) {
        for(span in line.spans) {
            for (part in span.parts) {
                canvas.drawText(part.text, part.rect.x, part.rect.y + yOffset, paint)
            }
        }
    }

    fun drawLineWord(canvas: Canvas, line:LyricLine, yOffset:Float) {
        for(span in line.spans) {
            for (part in span.parts) {
                // Is full?
                val isFull = positionMs >= span.endTime || positionMs <= span.startTime

                // Draw full word as usual
                if (isFull) {
                    val paint = if (positionMs <= span.startTime) inactivePaint else activePaint
                    canvas.drawText(part.text, part.rect.x, part.rect.y + yOffset, paint)
                } else {
                    // Draw clipped word
                    canvas.withSave {
                        // Draw background inactive word
                        canvas.drawText(part.text, part.rect.x, part.rect.y + yOffset, inactivePaint)

                        // Draw clipped line
                        val progress = (positionMs - span.startTime).toFloat() / (span.endTime - span.startTime).toFloat()
                        if (part.rect.width * progress > 0) {
                            activePaint.maskFilter = null
                            withClip(
                                part.rect.x,
                                part.rect.y + yOffset - part.rect.height,
                                part.rect.x + part.rect.width * progress,
                                part.rect.y + yOffset + part.rect.height
                            ) {
                                drawText(part.text, part.rect.x, part.rect.y + yOffset, activePaint)
                            }
                        }
                    }
                }
            }
        }
    }
}
