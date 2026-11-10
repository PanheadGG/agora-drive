package com.pgigi.agentclouddisk.agent

/**
 * 流式敏感信息扫描：边解析边喂片段，避免把整份文档读进内存。
 * 保留固定长度的尾部窗口（OVERLAP），跨块边界的身份证/手机号/银行卡号也能命中。
 */
class StreamingSensitiveScanner(private val maxPreview: Int = 4000) {

    data class Result(
        val risk: RiskDetector.RiskInfo?,
        val copied: Boolean,
        val copiedMarkers: List<String>,
        val hitCount: Int,
    )

    private var tail = ""
    private var idCard: String? = null
    private var phone: String? = null
    private var bankCard: String? = null
    private val copiedMarkers = LinkedHashSet<String>()
    private val preview = StringBuilder()
    private var scanned = 0L

    @Volatile
    var truncated: Boolean = false
        private set

    fun feed(chunk: String) {
        if (chunk.isEmpty()) return
        if (preview.length < maxPreview) {
            val room = maxPreview - preview.length
            preview.append(if (chunk.length <= room) chunk else chunk.substring(0, room))
            if (chunk.length > room) truncated = true
        } else {
            truncated = true
        }

        val window = if (tail.isEmpty()) chunk else tail + chunk
        if (idCard == null) RiskDetector.ID_CARD.find(window)?.let { idCard = it.value }
        if (phone == null) RiskDetector.PHONE.find(window)?.let { phone = it.value }
        if (bankCard == null) RiskDetector.BANK_CARD.find(window)?.let { bankCard = it.value }
        for (marker in RiskDetector.WEB_COPY) {
            if (marker !in copiedMarkers && window.contains(marker)) copiedMarkers += marker
        }
        tail = if (window.length > OVERLAP) window.substring(window.length - OVERLAP) else window
        scanned += chunk.length
    }

    fun feedLine(line: String) {
        feed(line)
        feed("\n")
    }

    fun previewText(): String = preview.toString()

    fun scannedChars(): Long = scanned

    fun result(): Result {
        val risk = when {
            idCard != null -> RiskDetector.RiskInfo("身份证号", "正文中出现身份证号：" + RiskDetector.mask(idCard!!))
            phone != null -> RiskDetector.RiskInfo("手机号", "正文中出现手机号：" + RiskDetector.mask(phone!!))
            bankCard != null -> RiskDetector.RiskInfo("银行卡号", "正文中出现疑似银行卡号：" + RiskDetector.mask(bankCard!!))
            else -> null
        }
        return Result(
            risk = risk,
            copied = copiedMarkers.isNotEmpty(),
            copiedMarkers = copiedMarkers.toList(),
            hitCount = listOfNotNull(idCard, phone, bankCard).size + copiedMarkers.size,
        )
    }

    companion object {
        /** 单个模式最长 19 位，留 32 字符重叠即可覆盖跨块截断 */
        private const val OVERLAP = 32
    }
}
