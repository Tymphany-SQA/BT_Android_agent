package com.sam.btagent

import android.media.AudioRecord
import android.media.AudioTrack
import android.util.Log

/**
 * Safe teardown for AudioTrack / AudioRecord that are driven by worker threads.
 *
 * Calling release() while another thread is blocked inside read()/write() can
 * crash in native code. The safe order is:
 *   1. caller clears its "running" flag so the worker loop will exit,
 *   2. stop/pause the stream so the blocked read()/write() returns,
 *   3. join the worker threads,
 *   4. only then release.
 */
object AudioIo {
    private const val TAG = "AudioIo"

    fun shutdown(
        threads: List<Thread?>,
        track: AudioTrack? = null,
        record: AudioRecord? = null,
        joinTimeoutMs: Long = 500
    ) {
        // Unblock any pending write()/read().
        runCatching { track?.pause(); track?.flush() }
        runCatching { record?.stop() }

        val current = Thread.currentThread()
        threads.forEach { t ->
            if (t == null || t === current) return@forEach
            runCatching { t.join(joinTimeoutMs) }
            if (t.isAlive) Log.w(TAG, "Audio worker ${t.name} still alive after ${joinTimeoutMs}ms; releasing anyway")
        }

        runCatching { track?.stop() }
        runCatching { track?.release() }
        runCatching { record?.release() }
    }
}
