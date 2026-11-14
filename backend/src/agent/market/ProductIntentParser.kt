package com.pgigi.agentclouddisk.agent.market

import com.pgigi.agentclouddisk.agent.Json
import com.pgigi.agentclouddisk.agent.LlmClient
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * 听懂普通用户的一句话需求，替 ta 选好产品模板。
 *
 * 普通用户不该被迫理解"模板 / 字段 / 聚合维度"这些概念，
 * 所以这里做两件事：
 *  1. 用规则先做保底匹配（关键词命中，永远可用）；
 *  2. 有大模型时让它读描述做语义判断，输出模板 + 一句话解释 + 会不会有什么坑。
 * 无论哪条路径，最终都落到一个确定的内置模板上，保证产出稳定可核对。
 */
@Service
class ProductIntentParser(
    private val templates: ProductTemplateRegistry,
    private val llmClient: LlmClient,
) {

    private val log = LoggerFactory.getLogger(ProductIntentParser::class.java)

    data class Intent(
        val templateKey: String,
        val templateLabel: String,
        val domain: String,
        val description: String,
        val confidence: Int,
        val reason: String,
        val fields: List<String>,
        val groupBy: String,
        val measures: List<String>,
        val source: String,
        val needsConfirm: Boolean,
    )

    fun parse(text: String, aiEnabled: Boolean): Intent {
        val raw = text.trim()
        if (raw.isEmpty()) return fromTemplate(templates.all().first(), 40, "没有描述，先用最常见的通勤模板试试")

        val ruleIntent = ruleMatch(raw)
        if (!aiEnabled || !llmClient.available) return ruleIntent

        val json = llmClient.chatJson(
            "你是网盘数据产品助手。只输出单个 JSON 对象，不要输出解释文字。",
            buildString {
                appendLine("用户想把自己网盘里的数据加工成一个可出售的数据产品，原话是：")
                appendLine(raw)
                appendLine()
                appendLine("可选模板（只能从这些里选一个，key 必须完全一致）：")
                templates.all().forEach {
                    appendLine("- key=" + it.key + " | " + it.label + " | " + it.description
                        + " | 产出：" + it.aggregateLabel)
                }
                appendLine()
                appendLine("判断用户的数据最适合哪个模板，并解释理由。")
                appendLine("如果描述太模糊（无法判断数据是什么），confidence 给低分并说明还需要什么信息。")
                appendLine("""输出 JSON：{"templateKey":"commute_stats","confidence":85,"reason":"一句话中文理由","missing":"还需要用户补充什么，没有就写空字符串"}""")
            },
            0.2,
        ) ?: return ruleIntent

        val key = Json.asString(json["templateKey"])?.trim()
        val template = templates.byKey(key ?: "") ?: return ruleIntent
        val confidence = Json.asInt(json["confidence"], ruleIntent.confidence).coerceIn(0, 100)
        val reason = Json.asString(json["reason"])?.trim()?.take(160)?.ifEmpty { null } ?: ruleIntent.reason
        val missing = Json.asString(json["missing"])?.trim()?.take(80)
        val combined = if (missing.isNullOrBlank()) reason else reason + "（建议补充：" + missing + "）"

        log.info("product intent parsed by llm: template={} confidence={}", template.key, confidence)
        return fromTemplate(template, confidence, combined, "llm")
    }

    private fun ruleMatch(text: String): Intent {
        val scored = templates.all().map { template ->
            val hits = template.cues.count { text.contains(it) } +
                template.tags.count { text.contains(it) }
            template to hits
        }
        val (best, hits) = scored.maxByOrNull { it.second } ?: (templates.all().first() to 0)
        return if (hits == 0) {
            fromTemplate(
                best, 35,
                "没太看出数据属于哪一类，先按「" + best.label + "」处理；你也可以换个说法，比如提到“通勤”“菜谱”“宠物”“读书”。",
            )
        } else {
            fromTemplate(
                best, (50 + hits * 15).coerceAtMost(95),
                "描述里提到了 " + best.tags.take(3).joinToString("、") + " 相关内容，按「" + best.label + "」加工。",
            )
        }
    }

    private fun fromTemplate(
        template: ProductTemplateRegistry.Template,
        confidence: Int,
        reason: String,
        source: String = "rule",
    ): Intent = Intent(
        templateKey = template.key,
        templateLabel = template.label,
        domain = template.domain,
        description = template.description,
        confidence = confidence,
        reason = reason,
        fields = template.fields.map { it.label },
        groupBy = template.groupBy.joinToString("、"),
        measures = template.measures,
        source = source,
        needsConfirm = confidence < 55,
    )
}
