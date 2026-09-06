package org.dalwadi.superclock.voice

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Unpacks the bundled Vosk model out of the APK into private storage.
 *
 * Vosk's `Model` takes a filesystem path, and an asset inside an APK has none, so
 * the archive has to be materialised once on first run.
 */
object ModelInstaller {

    private const val TAG = "ModelInstaller"

    private const val ASSET = "model-en-us.zip"
    private const val ROOT_DIR = "model"
    private const val STAGING_DIR = "model.tmp"
    private const val MARKER = ".installed"

    /** Bumped whenever the on-disk layout changes, to force a re-unpack. */
    private const val LAYOUT_VERSION = 2

    private const val COPY_BUFFER = 64 * 1024

    /** Everything Vosk's `Model` needs to exist under the unpacked model directory. */
    private val REQUIRED_SUBDIRS = arrayOf("am", "graph", "conf", "ivector")

    /**
     * Unpacks `assets/model-en-us.zip` into filesDir on first run. Returns the model dir.
     * Idempotent and safe to call on every start. Blocking — call off the main thread.
     */
    @Throws(IOException::class)
    fun ensureInstalled(context: Context): File {
        val root = File(context.filesDir, ROOT_DIR)
        val marker = File(root, MARKER)
        val stamp = "$LAYOUT_VERSION:${assetSize(context)}"

        // The marker alone is not proof: a power cut mid-unpack on an earlier layout, or
        // a partially wiped install, can leave it standing over a model Vosk cannot load.
        if (marker.isFile && readStamp(marker) == stamp) {
            findModel(root)?.let { return it }
        }

        Log.i(TAG, "Unpacking speech model")
        val staging = File(context.filesDir, STAGING_DIR)
        if (staging.exists() && !staging.deleteRecursively()) {
            throw IOException("Cannot clear staging dir ${staging.absolutePath}")
        }
        if (!staging.mkdirs()) throw IOException("Cannot create ${staging.absolutePath}")

        try {
            context.assets.open(ASSET).use { unzip(it, staging) }
            if (findModel(staging) == null) {
                throw IOException("Archive did not yield a loadable model directory")
            }
            // The marker is written inside the staging tree so that it only ever becomes
            // visible together with a complete model, via the rename below.
            File(staging, MARKER).writeText(stamp)

            if (root.exists() && !root.deleteRecursively()) {
                throw IOException("Cannot replace ${root.absolutePath}")
            }
            if (!staging.renameTo(root)) {
                throw IOException("Cannot move staging dir into place")
            }
        } catch (e: IOException) {
            staging.deleteRecursively()
            throw e
        }

        return findModel(root) ?: throw IOException("Model incomplete after install")
    }

    /**
     * The directory Vosk's `Model` wants, found rather than hard-coded: the archive's own
     * top-level folder is named after the model, so pinning it here would silently break
     * the next time the bundled model is swapped.
     */
    private fun findModel(root: File): File? {
        if (looksComplete(root)) return root
        return root.listFiles()?.firstOrNull { looksComplete(it) }
    }

    /**
     * Deletes the unpacked model together with its marker, so the next
     * [ensureInstalled] rebuilds from the asset. For recovering from an install that
     * unpacked but will not load — nothing else ever invalidates the marker.
     */
    internal fun wipe(context: Context) {
        val root = File(context.filesDir, ROOT_DIR)
        if (root.exists() && !root.deleteRecursively()) {
            Log.w(TAG, "Could not fully delete ${root.absolutePath}")
        }
    }

    private fun looksComplete(model: File): Boolean =
        model.isDirectory && REQUIRED_SUBDIRS.all { File(model, it).isDirectory }

    private fun assetSize(context: Context): Long = try {
        context.assets.openFd(ASSET).use { it.length }
    } catch (e: FileNotFoundException) {
        // openFd only works while the asset is stored uncompressed; fall back to counting.
        Log.w(TAG, "Asset is compressed, measuring by stream", e)
        context.assets.open(ASSET).use { input ->
            val buf = ByteArray(COPY_BUFFER)
            var total = 0L
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
            }
            total
        }
    }

    private fun readStamp(marker: File): String? = try {
        marker.readText().trim()
    } catch (e: IOException) {
        Log.w(TAG, "Unreadable install marker", e)
        null
    }

    private fun unzip(source: InputStream, target: File) {
        val fence = target.canonicalPath + File.separator
        val buf = ByteArray(COPY_BUFFER)
        ZipInputStream(source.buffered(COPY_BUFFER)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val out = File(target, entry.name)
                // Zip-slip guard. We ship the archive ourselves, but an unpacker that
                // trusts entry names is a defect regardless of who wrote the input.
                if (!out.canonicalPath.startsWith(fence)) {
                    throw IOException("Entry escapes target: ${entry.name}")
                }
                if (entry.isDirectory) {
                    if (!out.isDirectory && !out.mkdirs()) {
                        throw IOException("Cannot create ${out.absolutePath}")
                    }
                } else {
                    val parent = out.parentFile
                    if (parent != null && !parent.isDirectory && !parent.mkdirs()) {
                        throw IOException("Cannot create ${parent.absolutePath}")
                    }
                    FileOutputStream(out).use { sink ->
                        while (true) {
                            val n = zip.read(buf)
                            if (n < 0) break
                            sink.write(buf, 0, n)
                        }
                    }
                }
                zip.closeEntry()
            }
        }
    }
}
