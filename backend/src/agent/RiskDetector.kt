package com.pgigi.agentclouddisk.agent

/** 敏感信息与"网络搬运"判定：规则命中即标记，Demo 不追求合规检测全量覆盖 */
object RiskDetector {

    data class RiskInfo(val tag: String, val desc: String)

    internal val ID_CARD = Regex("""(?<!\d)(\d{17}[\dXx]|\d{15})(?!\d)""")
    internal val PHONE = Regex("""(?<!\d)(1[3-9]\d{9})(?!\d)""")
    internal val BANK_CARD = Regex("""(?<!\d)(\d{16,19})(?!\d)""")
    internal val WEB_COPY = listOf("小红书", "公众号", "转载", "来源网络", "搬运", "知乎", "微博", "侵权删", "下载自", "攻略网")

    fun detect(text: String): RiskInfo? {
        ID_CARD.find(text)?.let { return RiskInfo("身份证号", "正文中出现身份证号：" + mask(it.value)) }
        PHONE.find(text)?.let { return RiskInfo("手机号", "正文中出现手机号：" + mask(it.value)) }
        BANK_CARD.find(text)?.let { return RiskInfo("银行卡号", "正文中出现疑似银行卡号：" + mask(it.value)) }
        return null
    }

    fun isWebCopied(text: String): Boolean = WEB_COPY.any { text.contains(it) }

    fun copiedMarkers(text: String): List<String> = WEB_COPY.filter { text.contains(it) }

    internal fun mask(value: String): String =
        if (value.length <= 6) "*".repeat(value.length) else value.take(3) + "*".repeat(value.length - 6) + value.takeLast(3)
}
