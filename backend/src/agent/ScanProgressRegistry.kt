package com.pgigi.agentclouddisk.agent

import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** 扫描过程的实时状态：前端轮询这里，避免"干等着" */
@Component
class ScanProgressRegistry {

    data class Event(
        val seq: Int,
        val fileId: Long,
        val fileName: String,
        val stage: String,
        val parseMethod: String?,
        val ok: Boolean,
        val riskTag: String?,
        val detail: String?,
        val at: Long,
    )

    class Progress(val taskId: Long, val total: Int) {
        @Volatile var status: Int = 0
        @Volatile var processed: Int = 0
        @Volatile var success: Int = 0
        @Volatile var fail: Int = 0
        @Volatile var currentFile: String? = null
        @Volatile var stage: String = "排队中"
        @Volatile var message: String? = null
        @Volatile var startedAt: Long = System.currentTimeMillis()
        @Volatile var finishedAt: Long? = null
        val events = CopyOnWriteArrayList<Event>()
        private val seq = AtomicInteger(0)

        fun addEvent(
            fileId: Long,
            fileName: String,
            stage: String,
            parseMethod: String?,
            ok: Boolean,
            riskTag: String?,
            detail: String?,
        ) {
            if (events.size >= MAX_EVENTS) events.removeAt(0)
            events.add(Event(seq.incrementAndGet(), fileId, fileName, stage, parseMethod, ok, riskTag, detail, System.currentTimeMillis()))
        }

        fun finish(status: Int, message: String? = null) {
            this.status = status
            this.message = message
            this.currentFile = null
            this.stage = if (status == 2) "已完成" else "已结束"
            this.finishedAt = System.currentTimeMillis()
        }
    }

    private val store = ConcurrentHashMap<Long, Progress>()

    fun create(taskId: Long, total: Int): Progress = Progress(taskId, total).also { store[taskId] = it }

    fun get(taskId: Long): Progress? = store[taskId]

    fun drop(taskId: Long) {
        store.remove(taskId)
    }

    private companion object {
        const val MAX_EVENTS = 200
    }
}
