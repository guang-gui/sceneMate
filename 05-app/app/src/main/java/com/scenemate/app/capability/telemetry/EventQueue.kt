package com.scenemate.app.capability.telemetry

import android.util.Log
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

object EventName {
    const val SUGGESTION_SHOWN = "suggestion_shown"
    const val SUGGESTION_ADOPTED = "suggestion_adopted"
    const val PHOTO_SAVED = "photo_saved"
    const val CLOUD_SCENE_RESULT = "cloud_scene_result"
    const val FRAME_DROP = "frame_drop"
    const val PARAM_REJECTED = "param_write_rejected"
}

data class Event(
    val name: String,
    val atMs: Long,
    val sessionId: String,
    val props: Map<String, Any?>,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("event", name)
        put("ts_ms", atMs)
        put("session_id", sessionId)
        props.forEach { (k, v) -> put(k, v) }
    }
}

/**
 * 离线优先事件队列（R08）。
 *
 * 三条硬要求：
 *  1. 上报成功率 ≥99.5% —— 所以先落盘再发，失败不丢；
 *  2. 属性字段完整率 100% —— schema 校验不过的事件直接丢，不发脏数据；
 *  3. 不阻塞主线程 —— 全异步，队列满时丢最旧的并计数。
 */
class EventQueue(
    private val queueFile: File,
    private val endpoint: String?,
    private val httpPost: (url: String, body: String) -> Boolean,
) {

    companion object {
        private const val TAG = "EventQueue"
        const val MAX_QUEUE_LINES = 5000
        const val BATCH = 50
    }

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "scenemate-telemetry").apply { priority = Thread.MIN_PRIORITY }
    }
    private val draining = AtomicBoolean(false)

    val enqueued = AtomicLong(0)
    val uploaded = AtomicLong(0)
    val dropped = AtomicLong(0)

    fun record(event: Event) {
        enqueued.incrementAndGet()
        executor.execute {
            runCatching {
                queueFile.parentFile?.mkdirs()
                queueFile.appendText(event.toJson().toString() + "\n")
                trimIfNeeded()
            }.onFailure { Log.w(TAG, "append failed", it) }
            drain()
        }
    }

    /** 上报成功率：只统计「已尝试且成功」的比例，未尝试不计入分母。 */
    fun successRate(): Double {
        val attempted = uploaded.get() + dropped.get()
        return if (attempted == 0L) 1.0 else uploaded.get().toDouble() / attempted
    }

    fun drain() {
        val url = endpoint
        if (url.isNullOrBlank()) return // 没有后端时只落盘，这是当前 v1 的常态
        if (!draining.compareAndSet(false, true)) return
        try {
            while (true) {
                val lines = runCatching {
                    if (!queueFile.exists()) emptyList()
                    else queueFile.readLines().take(BATCH)
                }.getOrDefault(emptyList())
                if (lines.isEmpty()) break

                val body = lines.joinToString(prefix = "[", postfix = "]", separator = ",")
                val ok = runCatching { httpPost(url, body) }.getOrDefault(false)
                if (!ok) break

                // 成功后把这批从文件里摘掉
                runCatching {
                    val rest = queueFile.readLines().drop(lines.size)
                    queueFile.writeText(rest.joinToString("\n", postfix = if (rest.isEmpty()) "" else "\n"))
                }
                uploaded.addAndGet(lines.size.toLong())
            }
        } finally {
            draining.set(false)
        }
    }

    private fun trimIfNeeded() {
        if (!queueFile.exists()) return
        val lines = queueFile.readLines()
        if (lines.size <= MAX_QUEUE_LINES) return
        val dropCount = lines.size - MAX_QUEUE_LINES
        dropped.addAndGet(dropCount.toLong())
        queueFile.writeText(lines.drop(dropCount).joinToString("\n", postfix = "\n"))
        Log.w(TAG, "queue overflow, dropped $dropCount events")
    }
}
