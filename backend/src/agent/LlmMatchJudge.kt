package com.pgigi.agentclouddisk.agent

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * 用 DeepSeek 做文件与悬赏的语义匹配判定，要求模型只引用解析出的原文作为证据。
 * 注意：已在本机检出敏感信息的文件不会送给模型（不出网），直接按本地规则判为高风险。
 */
@Service
class LlmMatchJudge(private val client: LlmClient) {

    private val log = LoggerFactory.getLogger(LlmMatchJudge::class.java)

    data class Item(
        val fileId: Long,
        val fileName: String,
        val parseMethod: String,
        val text: String,
    )

    data class Judgement(
        val matchLevel: Int,
        val evidence: String?,
        val riskTag: String?,
        val riskDesc: String?,
        val reason: String?,
        val source: String,
    )

    val available: Boolean get() = client.available

    fun judge(requirement: BountyStructureParser.Requirement, items: List<Item>): Map<Long, Judgement> {
        if (items.isEmpty()) return emptyMap()
        val result = LinkedHashMap<Long, Judgement>()
        for (chunk in items.chunked(MAX_FILES_PER_CALL)) {
            val judged = judgeChunk(requirement, chunk)
            result.putAll(judged)
        }
        return result
    }

    private fun judgeChunk(requirement: BountyStructureParser.Requirement, items: List<Item>): Map<Long, Judgement> {
        val system = "你是网盘数据要素匹配判定器。只输出单个 JSON 对象，不要输出解释文字、不要编造原文。"

        val user = buildString {
            appendLine("悬赏结构化需求：")
            appendLine("领域：" + requirement.domainLabel)
            appendLine("要的内容：" + requirement.wants.joinToString("、"))
            appendLine("匹配关键词：" + requirement.matchKeywords.joinToString("、"))
            appendLine("需排除：" + requirement.excludeKeywords.joinToString("、"))
            appendLine("")
            appendLine("待判定文件（内容均为解析出的真实正文片段）：")
            items.forEach { item ->
                appendLine("---- 文件 id=" + item.fileId + " 名称=" + item.fileName + " 解析方式=" + item.parseMethod)
                appendLine(item.text.take(MAX_CHARS_PER_FILE))
            }
            appendLine("")
            appendLine("判定规则：")
            appendLine("1. 只看正文内容，不要因为文件名相似就给高分。")
            appendLine("2. matchLevel：3=高匹配（内容直接满足需求）；2=中匹配（相关但需二次整理）；1=低匹配（沾边缺关键部分）；0=不推荐。")
            appendLine("3. evidence 必须逐字引用上面的正文片段（最多 80 字），不得改写或编造；没有可用证据时给空字符串。")
            appendLine("4. 正文出现身份证号、手机号、银行卡号、住址等敏感信息：matchLevel=0，riskTag 写 身份证号/手机号/银行卡号/住址。")
            appendLine("5. 正文出现小红书、公众号、转载、下载自等网络搬运痕迹：matchLevel=0，riskTag 写 网络搬运。")
            appendLine("")
            appendLine("输出 JSON：{\"results\":[{\"fileId\":1,\"matchLevel\":3,\"evidence\":\"原文片段\",\"riskTag\":null,\"riskDesc\":null,\"reason\":\"一句话理由\"}]}")
        }

        val json = client.chatJson(system, user, 0.1) ?: return emptyMap()
        val results = Json.asList(json["results"]) ?: return emptyMap()
        val judgements = LinkedHashMap<Long, Judgement>()
        for (raw in results) {
            val row = Json.asMap(raw) ?: continue
            val fileId = Json.asInt(row["fileId"], -1).toLong()
            if (fileId < 0) continue
            val level = Json.asInt(row["matchLevel"], 0).coerceIn(0, 3)
            val riskTag = Json.asString(row["riskTag"])?.trim()?.takeIf { it.isNotEmpty() && it != "无" && it != "null" }
            judgements[fileId] = Judgement(
                matchLevel = if (riskTag != null) 0 else level,
                evidence = Json.asString(row["evidence"])?.trim()?.takeIf { it.isNotEmpty() }?.take(200),
                riskTag = riskTag,
                riskDesc = Json.asString(row["riskDesc"])?.trim()?.takeIf { it.isNotEmpty() },
                reason = Json.asString(row["reason"])?.trim()?.take(300),
                source = "llm",
            )
        }
        log.info("llm judged {} files (requested {})", judgements.size, items.size)
        return judgements
    }

    private fun <T> List<T>.chunked(size: Int): List<List<T>> {
        val chunks = ArrayList<List<T>>()
        var index = 0
        while (index < this.size) {
            chunks.add(subList(index, minOf(index + size, this.size)))
            index += size
        }
        return chunks
    }

    companion object {
        private const val MAX_FILES_PER_CALL = 6
        private const val MAX_CHARS_PER_FILE = 1200
    }
}