package com.pgigi.agentclouddisk.agent

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * 用 DeepSeek 把自然语言悬赏解析为结构化需求；与本地规则交叉校验，失败时退回规则结果。
 */
@Service
class LlmBountyAnalyzer(private val client: LlmClient) {

    private val log = LoggerFactory.getLogger(LlmBountyAnalyzer::class.java)

    data class Structured(
        val requirement: BountyStructureParser.Requirement,
        val source: String,
        val model: String?,
    )

    fun analyze(title: String, rawDesc: String): Structured {
        val fallback = BountyStructureParser.parse(title + " " + rawDesc)
        if (!client.available) return Structured(fallback, "rule", null)

        val system = "你是数据要素悬赏解析器。只输出单个 JSON 对象，不要输出解释文字。"
        val user = buildString {
            appendLine("把下面的悬赏需求解析成结构化对象。")
            appendLine("输出 JSON 字段：")
            appendLine("domain: 英文短标识，如 cooking/hiking/pet/reading/commute/generic")
            appendLine("domainLabel: 中文领域名")
            appendLine("wants: 字符串数组，用户要的具体内容（3 条以内）")
            appendLine("formats: 数组，只能取 image/docx/xlsx/text/pdf")
            appendLine("matchKeywords: 6-12 个中文关键词，用于判断文件正文是否相关，必须来自需求语义，不要写文件名")
            appendLine("excludeKeywords: 数组，明确排除的内容特征（如 小红书、公众号转载、宣传图）")
            appendLine("yearLimit: 整数或 null，近几年优先里的年数")
            appendLine("understanding: 一句话复述，说明要什么、不要什么")
            appendLine("")
            appendLine("悬赏标题：" + title)
            appendLine("悬赏原文：")
            appendLine(rawDesc)
        }

        val json = client.chatJson(system, user) ?: return Structured(fallback, "rule", null)
        val formats = Json.asStringList(json["formats"])
            .filter { it in setOf("image", "docx", "xlsx", "text", "pdf") }
            .ifEmpty { fallback.formats }
        val keywords = Json.asStringList(json["matchKeywords"]).filter { it.isNotBlank() }.ifEmpty { fallback.matchKeywords }
        val exclude = (Json.asStringList(json["excludeKeywords"]) + fallback.excludeKeywords).distinct().take(12)
        val requirement = BountyStructureParser.Requirement(
            domain = Json.asString(json["domain"]) ?: fallback.domain,
            domainLabel = Json.asString(json["domainLabel"]) ?: fallback.domainLabel,
            wants = Json.asStringList(json["wants"]).ifEmpty { fallback.wants },
            formats = formats,
            matchKeywords = keywords,
            excludeKeywords = exclude,
            yearLimit = Json.asInt(json["yearLimit"], fallback.yearLimit ?: 0).takeIf { it > 0 } ?: fallback.yearLimit,
            understanding = Json.asString(json["understanding"]) ?: fallback.understanding,
        )
        log.info("bounty structured by deepseek: domain={} keywords={}", requirement.domain, requirement.matchKeywords.size)
        return Structured(requirement, "llm", client.activeModel)
    }
}