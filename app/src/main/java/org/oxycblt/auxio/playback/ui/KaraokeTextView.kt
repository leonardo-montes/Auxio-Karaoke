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
import android.util.AttributeSet
import android.view.animation.AnimationUtils
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.graphics.withClip
import androidx.core.graphics.withSave
import org.oxycblt.auxio.playback.LyricLine
import org.oxycblt.auxio.playback.TimedLyrics
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
        this.timedLyrics = timedLyrics
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

    override fun onDraw(canvas: Canvas) {
        if (timedLyrics == null || timedLyrics!!.lines.isEmpty()) {
            super.onDraw(canvas)
            return
        }

        activePaint.textSize = textSize
        activePaint.typeface = typeface
        activePaint.color = currentTextColor

        inactivePaint.textSize = textSize
        inactivePaint.typeface = typeface
        inactivePaint.color = currentTextColor
        inactivePaint.alpha = 128 // 50% opacity for the "background" text

        val lineOffset = lineHeight / 2.0f

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
        if (newLyricLineId != lyricLineId) {
            prevLyricLineId = lyricLineId
            lyricLineId = newLyricLineId
            lastLyricLineIdChangeTime = now
            lastVerticalOffset = verticalOffset
            verticalOffset = -getLineHeight(canvas, timedLyrics!!.lines, lineOffset, lyricLineId)
            lastLyricLineIdTime = 0.0f;
        }
        //L.e("vertical offset: $verticalOffset")

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

        //L.e("isAnimating: $isAnimating")

        val x = 40.0f
        val y = baseline.toFloat()

        // Draw a line (multi-line)
        val yStartT = lastLyricLineIdTime * lastLyricLineIdTime * (3f - 2f * lastLyricLineIdTime)
        val yStart = lerp(lastVerticalOffset, verticalOffset,yStartT)
        var yOffset = 0.0f

        //L.e("Y OFFSET: $yOffset")
        for (i in 0 until timedLyrics!!.lines.count()) {
            if (timedLyrics!!.lines[i].spans.count() == 1 && timedLyrics!!.lines[i].spans[0].isFullLine) {
                yOffset += drawFullLine(canvas, i, x, y + yStart + yOffset, lineOffset)
            } else {
                yOffset += drawLine(canvas, i, x, y + yStart + yOffset, lineOffset)
            }
        }
    }

    fun getLineHeight(canvas: Canvas, lines: List<LyricLine>, lineOffset: Float, targetId: Int): Float {
        var verticalOffset = canvas.height / 4.0f;
        for (i in 0 until lines.count()) {
            if (i == lyricLineId) {
                break
            }

            val line = lines[i]

            // Calculate line width
            var totalWidth = 0.0f;
            for (span in line.spans) {
                val parts = if (span.text.isNotBlank()) span.text.split(Regex("(?<=\\s)")) else listOf(span.text)
                for (part in parts) {
                    if (part.isEmpty())
                        continue

                    val currentSpanWidth = activePaint.measureText(part)

                    if (totalWidth + currentSpanWidth > canvas.width - 80.0f) {
                        totalWidth = currentSpanWidth
                        verticalOffset += lineHeight
                    } else {
                        totalWidth += currentSpanWidth
                    }
                }
            }
            verticalOffset += lineHeight + lineOffset
        }
        return verticalOffset
    }

    fun drawFullLine(canvas: Canvas, lineId: Int, x: Float, y: Float, lineOffset: Float): Float {
        val line = timedLyrics!!.lines[lineId]
        // Draw each line
        var totalWidth = 0.0f;
        var verticalOffset = 0.0f;
        var text = ""
        var isVisible = isLineVisible(canvas, y + verticalOffset);
        //L.e("line height: ${y} - ${line.text}")
        for (span in timedLyrics!!.lines[lineId].spans) {
            val parts = if (span.text.isNotBlank()) span.text.split(Regex("(?<=\\s)")) else listOf(span.text)
            for (part in parts) {
                if (part.isEmpty())
                    continue

                //L.e("'${span.text}' (${parts.count()}) - '$part'")
                val currentSpanWidth = activePaint.measureText(part)

                if (totalWidth + currentSpanWidth > canvas.width - 80.0f) {
                    val willBeVisible = isLineVisible(canvas, y + verticalOffset + lineHeight)
                    if (isVisible || willBeVisible) {
                        if (isVisible)
                            drawLineFull(
                                canvas,
                                lineId,
                                text,
                                x,
                                y + verticalOffset
                            )
                    }
                    text = part
                    totalWidth = currentSpanWidth
                    verticalOffset += lineHeight
                    isVisible = willBeVisible
                } else {
                    text += part
                    totalWidth += currentSpanWidth
                }
            }
        }
        if (isVisible)
            drawLineFull(canvas, lineId, text, x, y + verticalOffset)

        return verticalOffset + lineHeight + lineOffset
    }

    fun drawLine(canvas: Canvas, lineId: Int, x: Float, y: Float, lineOffset: Float): Float {
        val line = timedLyrics!!.lines[lineId]
        // Draw each line
        var totalWidth = 0.0f;
        var verticalOffset = 0.0f;
        var text = ""
        var lastClipWidth = 0.0f
        var isVisible = isLineVisible(canvas, y + verticalOffset);
        //L.e("line height: ${y} - ${line.text}")
        for (span in timedLyrics!!.lines[lineId].spans) {
            val parts = if (span.text.isNotBlank()) span.text.split(Regex("(?<=\\s)")) else listOf(span.text)
            for (part in parts) {
                if (part.isEmpty())
                    continue

                //L.e("'${span.text}' (${parts.count()}) - '$part'")
                val currentSpanWidth = activePaint.measureText(part)

                if (totalWidth + currentSpanWidth > canvas.width - 80.0f) {
                    val willBeVisible = isLineVisible(canvas, y + verticalOffset + lineHeight)
                    if (isVisible || willBeVisible) {
                        val clipWidth = when {
                            positionMs >= span.endTime -> currentSpanWidth
                            positionMs <= span.startTime -> 0f
                            else -> {
                                val progress =
                                    (positionMs - span.startTime).toFloat() / (span.endTime - span.startTime).toFloat()
                                currentSpanWidth * progress
                            }
                        }
                        if (isVisible)
                            drawLine(
                                canvas,
                                lineId,
                                text,
                                x,
                                y + verticalOffset,
                                totalWidth,
                                lastClipWidth
                            )
                        lastClipWidth = clipWidth
                    }
                    text = part
                    totalWidth = currentSpanWidth
                    verticalOffset += lineHeight
                    isVisible = willBeVisible
                } else {
                    text += part
                    totalWidth += currentSpanWidth
                    if (isVisible) {
                        lastClipWidth += when {
                            positionMs >= span.endTime -> currentSpanWidth
                            positionMs <= span.startTime -> 0f
                            else -> {
                                val progress =
                                    (positionMs - span.startTime).toFloat() / (span.endTime - span.startTime).toFloat()
                                currentSpanWidth * progress
                            }
                        }
                    }
                }
            }
        }
        if (isVisible)
            drawLine(canvas, lineId, text, x, y + verticalOffset, totalWidth, lastClipWidth)

        return verticalOffset + lineHeight + lineOffset
    }

    fun isLineVisible(canvas: Canvas, y: Float): Boolean {
        //L.e("canvas height ${canvas.height}")
        val start = 0.0f;
        return y >= start && y < start + canvas.height + lineHeight;
    }

    fun drawLine(canvas: Canvas, lineId: Int, text: String, x: Float, y: Float, width: Float, clipWidth: Float) {
        // Calculate delta
        val delta: Int = lineId - lyricLineId
        var deltaT: Float =
            lerp((lineId - prevLyricLineId).toFloat(), delta.toFloat(), lastLyricLineIdTime)

        // Draw inactive (background) text
        if (delta >= 0) {
            if (delta > 0) {
                deltaT = if (deltaT < 0.01f) 0.01f else deltaT
                inactivePaint.maskFilter = BlurMaskFilter(deltaT * 4.0f, BlurMaskFilter.Blur.NORMAL)
                canvas.drawText(text, x, y, inactivePaint)
            } else {
                canvas.withSave {
                    // Draw clipped line
                    inactivePaint.maskFilter = null
                    val totalWidth = inactivePaint.measureText(text)
                    if (clipWidth < totalWidth) {
                        withClip(
                            x + clipWidth,
                            0f,
                            x + totalWidth,
                            height.toFloat()
                        ) {
                            drawText(text, x, y, inactivePaint)
                        }
                    }
                }
            }
        }

        // Draw active text
        if (delta <= 0) {
            if (delta < 0) {
                deltaT = if (deltaT > -0.01f) -0.01f else deltaT
                //L.e("delta $deltaT")
                activePaint.maskFilter = BlurMaskFilter(-deltaT * 8.0f, BlurMaskFilter.Blur.NORMAL)
                canvas.drawText(text, x, y, activePaint)
            } else {
                canvas.withSave {
                    // Draw clipped line
                    if (clipWidth > 0) {
                        activePaint.maskFilter = null
                        withClip(
                            x,
                            0f,
                            x + clipWidth,
                            height.toFloat()
                        ) {
                            drawText(text, x, y, activePaint)
                        }
                    }
                }
            }
        }
    }

    fun drawLineFull(canvas: Canvas, lineId: Int, text: String, x: Float, y: Float) {
        // Calculate delta
        val delta: Int = lineId - lyricLineId
        var deltaT: Float =
            lerp((lineId - prevLyricLineId).toFloat(), delta.toFloat(), lastLyricLineIdTime)

        // Draw inactive (background) text
        if (delta >= 0) {
            if (delta > 0) {
                deltaT = if (deltaT < 0.01f) 0.01f else deltaT
                inactivePaint.maskFilter =
                    BlurMaskFilter(deltaT * 4.0f, BlurMaskFilter.Blur.NORMAL)
                canvas.drawText(text, x, y, inactivePaint)
            } else {
                // Draw clipped line
                inactivePaint.maskFilter = null
                canvas.drawText(text, x, y, inactivePaint)
            }
        }

        // Draw active text
        if (delta <= 0) {
            if (delta < 0) {
                deltaT = if (deltaT > -0.01f) -0.01f else deltaT
                //L.e("delta $deltaT")
                activePaint.maskFilter =
                    BlurMaskFilter(-deltaT * 8.0f, BlurMaskFilter.Blur.NORMAL)
                canvas.drawText(text, x, y, activePaint)
            } else {
                activePaint.maskFilter = null
                canvas.drawText(text, x, y, activePaint)
            }
        }
    }
}
