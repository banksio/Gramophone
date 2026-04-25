/*
 *     Copyright (C) 2024 Akane Foundation
 *
 *     Gramophone is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     Gramophone is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.akanework.gramophone.logic

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.media3.common.util.Log
import org.akanework.gramophone.logic.utils.GramophoneArtResolver
import java.io.File

/**
 * ContentProvider that serves album artwork to external processes (e.g. Android Auto).
 *
 * External processes cannot resolve Gramophone's internal URI schemes
 * (`gramophoneSongCover://`, `gramophoneAlbumCover://`). This provider acts as a bridge,
 * using the shared [GramophoneArtResolver] to locate and serve the artwork over a
 * standard `content://` URI.
 *
 * URI format: `content://org.akanework.gramophone.albumart/{type}/{id}/{encodedPath}`
 * where `type` is "song" or "album".
 *
 * The provider writes artwork to a temporary cache file and returns a read-only
 * [ParcelFileDescriptor] for it. Cache files are keyed by a hash of the URI to
 * avoid redundant work on repeated requests.
 */
class GramophoneAlbumArtProvider : ContentProvider() {

    private val TAG = "GramophoneArtProvider"

    override fun onCreate() = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val context = context ?: return null
        val segments = uri.pathSegments

        if (segments.size < 3) {
            Log.w(TAG, "Invalid URI format, expected 3 path segments: $uri")
            return null
        }

        val type = segments[0]       // "song" or "album"
        val id = segments[1]         // songId or albumId
        val encodedPath = segments[2]
        val realPath = Uri.decode(encodedPath)

        // Use URI hash as cache key to avoid re-extracting on repeated requests
        val cacheFile = File(context.cacheDir, "art_${uri.toString().hashCode()}")

        if (!cacheFile.exists()) {
            val inputStream = when (type) {
                "song" -> GramophoneArtResolver.openSongArtwork(context, id, realPath)
                "album" -> GramophoneArtResolver.openAlbumArtwork(context, id, realPath)
                else -> {
                    Log.w(TAG, "Unknown artwork type: $type")
                    null
                }
            }

            if (inputStream != null) {
                try {
                    inputStream.use { input ->
                        cacheFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to write artwork cache for $uri", e)
                    cacheFile.delete()
                    return null
                }
            }
        }

        if (!cacheFile.exists()) return null

        return ParcelFileDescriptor.open(cacheFile, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? = null

    override fun getType(uri: Uri): String = "image/jpeg"

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0
}
