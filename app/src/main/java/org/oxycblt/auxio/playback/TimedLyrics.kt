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
            return LyricSpan(text, startTime, endTime)
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
    val endTime: Long
)

fun String.parseTtmlTime(): Long {
    val parts = split(":")
    if (parts.size != 3) return 0L
    val hours = parts[0].toLongOrNull() ?: 0L
    val minutes = parts[1].toLongOrNull() ?: 0L
    val secondsWithMs = parts[2]
    val secondsParts = secondsWithMs.split(".")
    val seconds = secondsParts[0].toLongOrNull() ?: 0L
    val ms = if (secondsParts.size > 1) secondsParts[1].padEnd(3, '0').take(3).toLongOrNull() ?: 0L else 0L
    return hours * 3600000 + minutes * 60000 + seconds * 1000 + ms
}
