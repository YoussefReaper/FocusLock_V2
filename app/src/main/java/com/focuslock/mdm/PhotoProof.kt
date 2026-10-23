package com.focuslock.mdm

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import androidx.core.content.FileProvider
import org.json.JSONArray
import java.io.File

/**
 * On-device photo proof.
 *
 * The reference implementation in this category is Habit Doom: capture a photo,
 * check it on the phone, never upload it, and let repeated failures pull a
 * credibility score down. This does the same, with one difference that matters
 * enough to be stated plainly in the UI rather than buried.
 *
 * ## What is actually verified
 *
 * A 400-label image classifier cannot tell "wrote two pages of an essay" from
 * "photographed a notebook", so leaning on content recognition alone would be
 * security theatre. What it *can* do reliably, with no model at all, is catch
 * the three cheats people actually use:
 *
 *  1. **Reusing an old photo** — every accepted proof leaves a 64-bit perceptual
 *     hash behind, and a near-identical resubmission is refused.
 *  2. **Photographing nothing** — a wall, a ceiling, a thumb over the lens and a
 *     dark room are all near-uniform frames, and are refused for lack of detail.
 *  3. **Submitting something taken earlier** — the file has to have been written
 *     during this session, which is guaranteed because FocusLock owns the
 *     capture.
 *
 * That is honest anti-cheat that works on every device with no download, and
 * nothing here ever touches the network. The photo is not read for content;
 * the UI says so plainly.
 */
object PhotoProof {

    private const val KEY_ACCEPTED_HASHES = "photo_proof_hashes"
    private const val MAX_REMEMBERED_HASHES = 200

    /** Hamming distance under which two photos count as the same picture. */
    private const val DUPLICATE_DISTANCE = 6

    /** Below this spread of brightness the frame carries no detail worth calling proof. */
    private const val MIN_DETAIL_STDDEV = 12.0

    data class Result(
        val accepted: Boolean,
        val headline: String,
        val detail: String,
        val credibilityDelta: Float
    )

    fun describeChecks(context: Context): String = context.getString(R.string.proof_checks_description)

    // ── Capture ───────────────────────────────────────────────────

    /**
     * The camera app that will handle the capture, so a kiosk session can let it
     * through. A photo task you cannot photograph is just a stuck task.
     */
    fun cameraPackage(context: Context): String? = try {
        context.packageManager
            .resolveActivity(Intent(MediaStore.ACTION_IMAGE_CAPTURE), 0)
            ?.activityInfo
            ?.packageName
    } catch (_: Exception) {
        null
    }

    fun isCaptureAvailable(context: Context): Boolean = cameraPackage(context) != null

    private fun proofDir(context: Context): File {
        val dir = File(context.filesDir, "proof")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun newProofFile(context: Context): File =
        File(proofDir(context), "proof_" + System.currentTimeMillis() + ".jpg")

    fun uriFor(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, context.packageName + ".proof", file)

    fun captureIntent(context: Context, target: Uri): Intent =
        Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
            putExtra(MediaStore.EXTRA_OUTPUT, target)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

    // ── Verification ──────────────────────────────────────────────

    fun verify(context: Context, @Suppress("UNUSED_PARAMETER") task: FocusTask, file: File, sessionStartedAt: Long): Result {
        if (!file.exists() || file.length() <= 0L) {
            return Result(
                accepted = false,
                headline = context.getString(R.string.proof_none_headline),
                detail = context.getString(R.string.proof_none_detail),
                credibilityDelta = 0f
            )
        }

        // Freshness. FocusLock owns the capture, so anything older than the
        // session is a file that arrived from somewhere else.
        if (file.lastModified() < sessionStartedAt - 60_000L) {
            return Result(
                accepted = false,
                headline = context.getString(R.string.proof_old_headline),
                detail = context.getString(R.string.proof_old_detail),
                credibilityDelta = -0.05f
            )
        }

        val bitmap = decodeSmall(file)
            ?: return Result(
                accepted = false,
                headline = context.getString(R.string.proof_not_photo_headline),
                detail = context.getString(R.string.proof_not_photo_detail),
                credibilityDelta = 0f
            )

        val grid = grayscaleGrid(bitmap)

        if (stdDev(grid) < MIN_DETAIL_STDDEV) {
            return Result(
                accepted = false,
                headline = context.getString(R.string.proof_empty_headline),
                detail = context.getString(R.string.proof_empty_detail),
                credibilityDelta = -0.05f
            )
        }

        val hash = averageHash(grid)
        if (isDuplicate(context, hash)) {
            return Result(
                accepted = false,
                headline = context.getString(R.string.proof_reused_headline),
                detail = context.getString(R.string.proof_reused_detail),
                credibilityDelta = -0.15f
            )
        }

        remember(context, hash)
        return Result(
            accepted = true,
            headline = context.getString(R.string.proof_accepted_headline),
            detail = context.getString(R.string.proof_accepted_detail),
            credibilityDelta = 0.05f
        )
    }

    /** Applies a verification result to the task's running trust score. */
    fun applyCredibility(context: Context, task: FocusTask, result: Result): FocusTask {
        val next = (task.credibility + result.credibilityDelta).coerceIn(0f, 1f)
        val updated = task.copy(credibility = next)
        FocusTaskStore.update(context, updated)
        return updated
    }

    /** Proof photos are working files; nothing keeps them once they are checked. */
    fun discard(file: File) {
        try {
            if (file.exists()) file.delete()
        } catch (_: Exception) {
            // A leftover file in private storage is not worth crashing over.
        }
    }

    fun clearStoredHashes(context: Context) {
        FocusStore.setJsonArray(context, KEY_ACCEPTED_HASHES, JSONArray())
    }

    // ── Image maths ───────────────────────────────────────────────

    /** Decodes at a small size: nothing here needs pixels, and full frames are megabytes. */
    private fun decodeSmall(file: File): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val largest = maxOf(bounds.outWidth, bounds.outHeight)
        val sample = if (largest > 512) largest / 512 else 1

        BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sample.coerceAtLeast(1) }
        )
    } catch (_: Exception) {
        null
    } catch (_: OutOfMemoryError) {
        null
    }

    private const val GRID = 8

    /** An 8x8 luminance grid: enough for both the detail test and the hash. */
    private fun grayscaleGrid(bitmap: Bitmap): IntArray {
        val scaled = try {
            Bitmap.createScaledBitmap(bitmap, GRID, GRID, true)
        } catch (_: Exception) {
            return IntArray(GRID * GRID)
        }
        val out = IntArray(GRID * GRID)
        for (y in 0 until GRID) {
            for (x in 0 until GRID) {
                val pixel = scaled.getPixel(x, y)
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                out[y * GRID + x] = ((0.299 * r) + (0.587 * g) + (0.114 * b)).toInt()
            }
        }
        if (scaled != bitmap) scaled.recycle()
        return out
    }

    private fun stdDev(values: IntArray): Double {
        if (values.isEmpty()) return 0.0
        val mean = values.sum().toDouble() / values.size
        val variance = values.sumOf { (it - mean) * (it - mean) } / values.size
        return Math.sqrt(variance)
    }

    /** Classic average hash: one bit per cell, above or below the frame mean. */
    private fun averageHash(values: IntArray): Long {
        if (values.isEmpty()) return 0L
        val mean = values.sum().toDouble() / values.size
        var hash = 0L
        values.forEachIndexed { index, value ->
            if (value > mean) hash = hash or (1L shl index)
        }
        return hash
    }

    private fun isDuplicate(context: Context, hash: Long): Boolean {
        val stored = FocusStore.getJsonArray(context, KEY_ACCEPTED_HASHES)
        for (i in 0 until stored.length()) {
            val previous = stored.optString(i, "").toLongOrNull() ?: continue
            if (java.lang.Long.bitCount(previous xor hash) <= DUPLICATE_DISTANCE) return true
        }
        return false
    }

    private fun remember(context: Context, hash: Long) {
        val stored = FocusStore.getJsonArray(context, KEY_ACCEPTED_HASHES)
        val values = FocusStore.jsonArrayToStringList(stored).toMutableList()
        values.add(hash.toString())
        while (values.size > MAX_REMEMBERED_HASHES) {
            values.removeAt(0)
        }
        FocusStore.setJsonArray(context, KEY_ACCEPTED_HASHES, FocusStore.stringListToJsonArray(values))
    }
}
