/*
 * Copyright (c) 2024 Auxio Project
 * LyricsRepository.kt is part of Auxio.
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

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.oxycblt.auxio.music.MusicSettings
import org.oxycblt.musikr.Song
import org.oxycblt.musikr.fs.Volume

/**
 * Repository for loading lyrics from the filesystem.
 *
 * @author Alexander Capehart (OxygenCobalt)
 */
@Singleton
class LyricsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val musicSettings: MusicSettings
) {
    /**
     * Load lyrics for the given [Song].
     *
     * @param song The [Song] to load lyrics for.
     * @return The [TimedLyrics] if found and parsed successfully, null otherwise.
     */
    suspend fun loadLyrics(song: Song): TimedLyrics? = withContext(Dispatchers.IO) {
        val content = tryLoadLyrics(song) ?: return@withContext null
        TimedLyrics.parse(content)
    }

    private fun tryLoadLyrics(song: Song): String? {
        val path = song.path
        val fileName = path.name ?: return null
        if (!fileName.contains(".")) return null
        val ttmlFileName = fileName.substringBeforeLast(".") + ".ttml"

        // 1. Try deriving SAF URI
        val ttmlUri = findSafUri(song, ttmlFileName)
        if (ttmlUri != null) {
            try {
                context.contentResolver.openInputStream(ttmlUri)?.use {
                    return it.bufferedReader().readText()
                }
            } catch (e: Exception) {
                // Ignore and try fallback
            }
        }

        // 2. Fallback: try absolute filesystem path if available
        val volume = path.volume
        val volumePath = volume.components
        if (volumePath != null) {
            val absolutePath = "/" + volumePath.child(path.components.parent().child(ttmlFileName)).unixString
            val file = File(absolutePath)
            if (file.exists() && file.canRead()) {
                try {
                    return file.readText()
                } catch (e: Exception) {
                    // Ignore
                }
            }
        }

        return null
    }

    private fun findSafUri(song: Song, ttmlFileName: String): Uri? {
        val songUri = song.uri
        val path = song.path

        // Case A: Song URI is already a document URI
        if (DocumentsContract.isDocumentUri(context, songUri)) {
            val authority = songUri.authority ?: return null
            val docId = DocumentsContract.getDocumentId(songUri)
            val ttmlDocId = docId.substringBeforeLast(".") + ".ttml"
            return try {
                val treeId = DocumentsContract.getTreeDocumentId(songUri)
                val treeUri =
                    DocumentsContract.buildTreeDocumentUri(authority, treeId)
                DocumentsContract.buildDocumentUriUsingTree(treeUri, ttmlDocId)
            } catch (e: Exception) {
                DocumentsContract.buildDocumentUri(authority, ttmlDocId)
            }
        }

        // Case B: Find matching tree from settings
        val volume = path.volume
        val volumeId = when (volume) {
            is Volume.Internal -> "primary"
            is Volume.External -> volume.id
        }

        if (volumeId != null) {
            val ttmlDocId = "$volumeId:${path.components.parent().child(ttmlFileName).unixString}"
            for (location in musicSettings.safQuery.source) {
                if (location.path.volume == volume && location.path.components.contains(path.components)) {
                    val treeUri = location.uri
                    val authority = treeUri.authority ?: return null
                    return try {
                        DocumentsContract.buildDocumentUriUsingTree(treeUri, ttmlDocId)
                    } catch (e: Exception) {
                        null
                    }
                }
            }
        }

        return null
    }
}
