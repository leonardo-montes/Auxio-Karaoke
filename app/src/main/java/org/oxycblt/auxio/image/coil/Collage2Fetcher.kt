/*
 * Copyright (c) 2024 Auxio Project
 * CollageCoverCollectionFetcher.kt is part of Auxio.
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
 
package org.oxycblt.auxio.image.coil

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.toDrawable
import coil3.Extras
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.fetch.SourceFetchResult
import coil3.getExtra
import coil3.request.ImageRequest
import coil3.request.Options
import coil3.size.Size
import coil3.size.pxOrElse
import java.io.InputStream
import javax.inject.Inject
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.buffer
import okio.source
import org.oxycblt.auxio.R
import org.oxycblt.auxio.util.getColorCompat
import org.oxycblt.musikr.covers.CoverCollection

private const val DEFAULT_CARD_SIZE_PERCENT = 0.60f
private const val DEFAULT_INSET_PERCENT = 0.10f
private const val DEFAULT_GAP_RATIO = 0.04f
private const val DEFAULT_CORNER_RATIO = 0.08f

private fun List<Int>.normalizedZOrder(): List<Int> {
    val expected = listOf(0, 1, 2, 3)
    return if (size == expected.size && distinct().size == expected.size && containsAll(expected)) {
        this
    } else {
        expected
    }
}

data class Collage2Config(
    val cardSizePercent: Float = DEFAULT_CARD_SIZE_PERCENT,
    val insetPercent: Float = DEFAULT_INSET_PERCENT,
    val gapWidthRatio: Float = DEFAULT_GAP_RATIO,
    val cornerRadiusRatio: Float = DEFAULT_CORNER_RATIO,
    val zOrder: List<Int> = listOf(0, 1, 2, 3),
) {
    fun normalized(): Collage2Config {
        val normalizedZOrder = zOrder.normalizedZOrder()
        return copy(
            cardSizePercent = cardSizePercent.coerceIn(0.5f, 1f),
            insetPercent = insetPercent.coerceIn(0f, 0.25f),
            gapWidthRatio = gapWidthRatio.coerceIn(0f, 0.1f),
            cornerRadiusRatio = cornerRadiusRatio.coerceIn(0f, 0.25f),
            zOrder = normalizedZOrder,
        )
    }

    fun cacheKey(): String {
        val config = normalized()
        return buildString {
            append("card=")
            append(config.cardSizePercent)
            append("&inset=")
            append(config.insetPercent)
            append("&gap=")
            append(config.gapWidthRatio)
            append("&corner=")
            append(config.cornerRadiusRatio)
            append("&order=")
            append(config.zOrder.joinToString("-"))
        }
    }
}

internal object CoverCollectionExtras {
    val COLLAGE = Extras.Key(false)
    val COLLAGE_CONFIG = Extras.Key<Collage2Config?>(null)
}

fun ImageRequest.Builder.enableCoverCollectionCollage(
    config: Collage2Config = Collage2Config(),
): ImageRequest.Builder = apply {
    extras[CoverCollectionExtras.COLLAGE] = true
    extras[CoverCollectionExtras.COLLAGE_CONFIG] = config
}

class Collage2Fetcher
private constructor(
    private val context: Context,
    private val covers: CoverCollection,
    private val size: Size,
    private val config: Collage2Config,
) : Fetcher {
    override suspend fun fetch(): FetchResult? {
        val streams = covers.covers.asFlow().mapNotNull { it.open() }.take(4).toList()
        if (streams.size == 4) {
            return createCollage(streams, size).also {
                withContext(Dispatchers.IO) { streams.forEach(InputStream::close) }
            }
        }

        val first = streams.firstOrNull() ?: return null

        withContext(Dispatchers.IO) {
            for (i in 1 until streams.size) {
                streams[i].close()
            }
        }

        return SourceFetchResult(
            source = ImageSource(first.source().buffer(), FileSystem.SYSTEM, null),
            mimeType = null,
            dataSource = DataSource.DISK,
        )
    }

    private fun createCollage(streams: List<InputStream>, size: Size): FetchResult? {
        val outputSize = size.collageSize()
        val bitmaps = streams.mapNotNull { BitmapFactory.decodeStream(it) }
        if (bitmaps.size != streams.size) {
            return null
        }
        val normalizedConfig = config.normalized()
        val collageBitmap =
            CollageGenerator.generate(
                bitmaps,
                CollageGenerator.Config(
                    outputSizePx = outputSize,
                    cardSizePercent = normalizedConfig.cardSizePercent,
                    insetPercent = normalizedConfig.insetPercent,
                    gapWidthPx = outputSize * normalizedConfig.gapWidthRatio,
                    cornerRadiusPx = outputSize * normalizedConfig.cornerRadiusRatio,
                    backgroundColor = context.getColorCompat(R.color.sel_cover_bg).defaultColor,
                    zOrder = normalizedConfig.zOrder,
                ),
            )

        return ImageFetchResult(
            image = collageBitmap.toDrawable(context.resources).asImage(),
            isSampled = true,
            dataSource = DataSource.DISK,
        )
    }

    private fun Size.collageSize(): Int {
        val widthPx = width.pxOrElse { 512 }
        val heightPx = height.pxOrElse { 512 }
        return min(widthPx, heightPx).coerceAtLeast(1)
    }

    private object CollageGenerator {
        data class Config(
            val outputSizePx: Int,
            val cardSizePercent: Float,
            val insetPercent: Float,
            val gapWidthPx: Float,
            val cornerRadiusPx: Float,
            val backgroundColor: Int = Color.parseColor("#0f111a"),
            val zOrder: List<Int> = listOf(0, 1, 2, 3),
        )

        fun generate(sourceImages: List<Bitmap>, config: Config): Bitmap {
            if (sourceImages.size != 4) {
                throw IllegalArgumentException("Collage requires exactly 4 images.")
            }

            val result = createBitmap(config.outputSizePx, config.outputSizePx)
            val canvas = Canvas(result)

            canvas.drawColor(config.backgroundColor)

            val gapPaint =
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = config.backgroundColor
                    style = Paint.Style.FILL
                }
            val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG)

            val totalSize = config.outputSizePx.toFloat()
            val cardSize = totalSize * config.cardSizePercent
            val inset = totalSize * config.insetPercent

            val p0 = RectF(inset, inset, inset + cardSize, inset + cardSize)
            val p1 =
                RectF(
                    totalSize - cardSize - inset,
                    inset,
                    totalSize - inset,
                    inset + cardSize,
                )
            val p2 =
                RectF(
                    inset,
                    totalSize - cardSize - inset,
                    inset + cardSize,
                    totalSize - inset,
                )
            val p3 =
                RectF(
                    totalSize - cardSize - inset,
                    totalSize - cardSize - inset,
                    totalSize - inset,
                    totalSize - inset,
                )

            val positions = listOf(p0, p1, p2, p3)

            for (imageIndex in config.zOrder) {
                val bitmap = sourceImages[imageIndex]
                val isTop = imageIndex < 2
                val isLeft = imageIndex % 2 == 0
                val isBottom = !isTop
                val isRight = !isLeft

                val baseRect = RectF(positions[imageIndex])

                val innerRect = RectF(baseRect)
                innerRect.left += if (isLeft) 0f else config.gapWidthPx
                innerRect.top += if (isTop) 0f else config.gapWidthPx
                innerRect.right -= if (isRight) 0f else config.gapWidthPx
                innerRect.bottom -= if (isBottom) 0f else config.gapWidthPx

                val gapRect = RectF(innerRect)
                gapRect.inset(-config.gapWidthPx, -config.gapWidthPx)

                val gapRadius = config.cornerRadiusPx
                val gapTopLeft = if (!isTop && !isLeft) gapRadius else 0f
                val gapTopRight = if (!isTop && !isRight) gapRadius else 0f
                val gapBottomRight = if (!isBottom && !isRight) gapRadius else 0f
                val gapBottomLeft = if (!isBottom && !isLeft) gapRadius else 0f
                val gapPath =
                    Path().apply {
                        addRoundRect(
                            gapRect,
                            floatArrayOf(
                                gapTopLeft,
                                gapTopLeft,
                                gapTopRight,
                                gapTopRight,
                                gapBottomRight,
                                gapBottomRight,
                                gapBottomLeft,
                                gapBottomLeft,
                            ),
                            Path.Direction.CW,
                        )
                    }
                canvas.drawPath(gapPath, gapPaint)

                if (innerRect.width() > 0 && innerRect.height() > 0) {
                    val savedLayer = canvas.saveLayer(innerRect, null)

                    val innerRadius = (config.cornerRadiusPx - config.gapWidthPx).coerceAtLeast(0f)
                    val topLeftRadius = if (!isTop && !isLeft) innerRadius else 0f
                    val topRightRadius = if (!isTop && !isRight) innerRadius else 0f
                    val bottomRightRadius = if (!isBottom && !isRight) innerRadius else 0f
                    val bottomLeftRadius = if (!isBottom && !isLeft) innerRadius else 0f
                    val maskPath =
                        Path().apply {
                            addRoundRect(
                                innerRect,
                                floatArrayOf(
                                    topLeftRadius,
                                    topLeftRadius,
                                    topRightRadius,
                                    topRightRadius,
                                    bottomRightRadius,
                                    bottomRightRadius,
                                    bottomLeftRadius,
                                    bottomLeftRadius,
                                ),
                                Path.Direction.CW,
                            )
                        }
                    canvas.drawPath(maskPath, imagePaint)

                    imagePaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
                    drawBitmapCover(canvas, bitmap, innerRect, imagePaint)
                    imagePaint.xfermode = null

                    canvas.restoreToCount(savedLayer)
                }
            }

            return result
        }

        private fun drawBitmapCover(canvas: Canvas, bitmap: Bitmap, dest: RectF, paint: Paint) {
            val bitmapRatio = bitmap.width.toFloat() / bitmap.height.toFloat()
            val destRatio = dest.width() / dest.height()

            val srcRect = Rect(0, 0, bitmap.width, bitmap.height)

            if (bitmapRatio > destRatio) {
                val newWidth = (bitmap.height * destRatio).toInt()
                val xOffset = (bitmap.width - newWidth) / 2
                srcRect.left = xOffset
                srcRect.right = xOffset + newWidth
            } else {
                val newHeight = (bitmap.width / destRatio).toInt()
                val yOffset = (bitmap.height - newHeight) / 2
                srcRect.top = yOffset
                srcRect.bottom = yOffset + newHeight
            }

            canvas.drawBitmap(bitmap, srcRect, dest, paint)
        }
    }

    class Factory @Inject constructor() : Fetcher.Factory<CoverCollection> {
        override fun create(data: CoverCollection, options: Options, imageLoader: ImageLoader) =
            if (options.getExtra(CoverCollectionExtras.COLLAGE)) {
                val config =
                    options.getExtra(CoverCollectionExtras.COLLAGE_CONFIG)
                        ?: Collage2Config()
                Collage2Fetcher(options.context, data, options.size, config)
            } else {
                null
            }
    }
}
