/*
 * Copyright (c) 2024 Auxio Project
 * TimedLyrics.kt is part of Auxio.
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

package org.oxycblt.auxio.playback

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import timber.log.Timber as L

data class TimedLyrics(
    val lines: List<LyricLine>
) {
    companion object {
        fun parse(rawLyrics: String): TimedLyrics? {
            return try {
                val parser = Xml.newPullParser()
                parser.setInput(rawLyrics.reader())
                val lines = mutableListOf<LyricLine>()
                var eventType = parser.eventType

                while (eventType != XmlPullParser.END_DOCUMENT) {
                    if (eventType == XmlPullParser.START_TAG && parser.name == "p") {
                        lines.add(readParagraph(parser))
                    }
                    eventType = parser.next()
                }
                TimedLyrics(lines)
            } catch (e: Exception) {
                L.e(e, "Failed to parse lyrics")
                null
            }
        }

        private fun readParagraph(parser: XmlPullParser): LyricLine {
            val startTime = parser.getAttributeValue(null, "begin")?.parseTtmlTime() ?: 0L
            val endTime = parser.getAttributeValue(null, "end")?.parseTtmlTime() ?: 0L
            val spans = mutableListOf<LyricSpan>()

            var eventType = parser.next()
            while (!(eventType == XmlPullParser.END_TAG && parser.name == "p")) {
                if (eventType == XmlPullParser.START_TAG && parser.name == "span") {
                    spans.add(readSpan(parser))
                } else if (eventType == XmlPullParser.TEXT) {
                    val text = parser.text.trim()
                    if (text.isNotEmpty()) {
                        spans.add(LyricSpan(text, startTime, endTime, true))
                    }
                }
                eventType = parser.next()
            }
            return LyricLine(spans, startTime, endTime)
        }

        private fun readSpan(parser: XmlPullParser): LyricSpan {
            val startTime = parser.getAttributeValue(null, "begin")?.parseTtmlTime() ?: 0L
            val endTime = parser.getAttributeValue(null, "end")?.parseTtmlTime() ?: 0L
            var text = ""

            var eventType = parser.next()
            while (!(eventType == XmlPullParser.END_TAG && parser.name == "span")) {
                if (eventType == XmlPullParser.TEXT) {
                    text = parser.text
                }
                eventType = parser.next()
            }
            return LyricSpan(text, startTime, endTime, false)
        }
    }
}

data class LyricLine(
    val spans: List<LyricSpan>,
    val startTime: Long,
    val endTime: Long
) {
    val text: String = spans.joinToString("") { it.text }
}

data class LyricSpan(
    val text: String,
    val startTime: Long,
    val endTime: Long,
    val isFullLine: Boolean
)

fun String.parseTtmlTime(): Long {
    val parts = split(":")
    var hours = 0L
    var minutes = 0L
    val secondsStr: String

    when (parts.size) {
        3 -> { // HH:MM:SS.mmm
            hours = parts[0].toLongOrNull() ?: 0L
            minutes = parts[1].toLongOrNull() ?: 0L
            secondsStr = parts[2]
        }
        2 -> { // MM:SS.mmm
            minutes = parts[0].toLongOrNull() ?: 0L
            secondsStr = parts[1]
        }
        else -> return 0L
    }

    val secondsParts = secondsStr.split(".")
    val seconds = secondsParts[0].toLongOrNull() ?: 0L
    val ms = if (secondsParts.size > 1) {
        // Pad to ensure "03" becomes "030" and "3" becomes "300"
        secondsParts[1].padEnd(3, '0').take(3).toLongOrNull() ?: 0L
    } else {
        0L
    }

    return (hours * 3600000L) + (minutes * 60000L) + (seconds * 1000L) + ms
}
