/*
 * Copyright (c) 2025 Auxio Project
 * KaraokeRepository.kt is part of Auxio.
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
import timber.log.Timber as L

/**
 * Repository for checking the existence of karaoke-related audio files.
 */
@Singleton
class KaraokeRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val musicSettings: MusicSettings
) {
    /**
     * Check if karaoke files exist for the given [Song].
     *
     * @param song The [Song] to check.
     * @return True if both vocals and accompaniment files are found.
     */
    suspend fun hasKaraokeFiles(song: Song): Boolean = withContext(Dispatchers.IO) {
        val path = song.path
        val fileName = path.name ?: return@withContext false
        if (!fileName.contains(".")) return@withContext false
        
        val baseName = fileName.substringBeforeLast(".")
        val extension = fileName.substringAfterLast(".")
        
        val vocalsName = "${baseName}_vocals.$extension"
        val accompanimentName = "${baseName}_accompaniment.$extension"
        
        val vocalsFound = exists(song, vocalsName)
        val accompanimentFound = exists(song, accompanimentName)

        //if (vocalsFound) L.e("Found vocals file: $vocalsName")
        //if (accompanimentFound) L.e("Found accompaniment file: $accompanimentName")
        
        vocalsFound && accompanimentFound
    }

    private fun exists(song: Song, targetFileName: String): Boolean {
        // 1. Try SAF URI
        val uri = findSafUri(song, targetFileName)
        if (uri != null) {
            try {
                context.contentResolver.openInputStream(uri)?.use {
                    return true
                }
            } catch (e: Exception) {
                // Ignore and try fallback
            }
        }

        // 2. Fallback: absolute filesystem path
        val path = song.path
        val volume = path.volume
        val volumePath = volume.components
        if (volumePath != null) {
            val absolutePath = "/" + volumePath.child(path.components.parent().child(targetFileName)).unixString
            val file = File(absolutePath)
            return file.exists() && file.canRead()
        }

        return false
    }

    private fun findSafUri(song: Song, targetFileName: String): Uri? {
        val songUri = song.uri
        val path = song.path

        // Case A: Song URI is already a document URI
        if (DocumentsContract.isDocumentUri(context, songUri)) {
            val authority = songUri.authority ?: return null
            val docId = DocumentsContract.getDocumentId(songUri)
            
            // Try to construct target docId by replacing filename part or just suffixing if it's path-like
            val targetDocId = if (docId.contains("/") && docId.contains(".")) {
                docId.substringBeforeLast("/") + "/" + targetFileName
            } else if (docId.contains(".")) {
                docId.substringBeforeLast(".") + targetFileName.substring(targetFileName.lastIndexOf("_"))
            } else {
                return null
            }

            return try {
                val treeId = DocumentsContract.getTreeDocumentId(songUri)
                val treeUri = DocumentsContract.buildTreeDocumentUri(authority, treeId)
                DocumentsContract.buildDocumentUriUsingTree(treeUri, targetDocId)
            } catch (e: Exception) {
                DocumentsContract.buildDocumentUri(authority, targetDocId)
            }
        }

        // Case B: Find matching tree from settings
        val volume = path.volume
        val volumeId = when (volume) {
            is Volume.Internal -> "primary"
            is Volume.External -> volume.id
        }

        if (volumeId != null) {
            val targetDocId = "$volumeId:${path.components.parent().child(targetFileName).unixString}"
            for (location in musicSettings.safQuery.source) {
                if (location.path.volume == volume && location.path.components.contains(path.components)) {
                    val treeUri = location.uri
                    val authority = treeUri.authority ?: return null
                    return try {
                        DocumentsContract.buildDocumentUriUsingTree(treeUri, targetDocId)
                    } catch (e: Exception) {
                        null
                    }
                }
            }
        }

        return null
    }
}
