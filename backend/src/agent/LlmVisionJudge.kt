package com.pgigi.agentclouddisk.agent

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path

/**
 * 图片内容的视觉判定。
 *
 * 只用 OCR 会出问题：白底截图、表情包、空白模板、纯装饰图也可能 OCR 出少量文字，
 * 按文本关键词就会误判为高匹配。这里把图片本体交给多模态模型，要求它描述画面，
 * 并明确区分"画面里真的有相关内容"与"只是有字"。
 *
 * 模型不可用（未配置 / 不支持多模态 / 调用失败）时返回 null，由调用方把该文件
 * 降级为"待视觉确认"，绝不假装确认过。
 */
@Service
class LlmVisionJudge(private val client: LlmClient) {

    private val log = LoggerFactory.getLogger(LlmVisionJudge::class.java)

    data class VisionVerdict(
        val relevant: Boolean,
        val sceneDescription: String?,
        val reason: String?,
        val degraded: Boolean = false,
    )

    val available: Boolean get() = client.available

    fun judge(requirement: BountyStructureParser.Requirement, fileName: String, imagePath: String?, ocrText: String): VisionVerdict? {
        if (imagePath.isNullOrBlank()) return null
        val path = Path.of(imagePath)
        if (!Files.exists(path)) return null

        val system = "你是网盘图片内容审核员。只输出单个 JSON 对象，不要输出解释文字。"
        val user = buildString {
            appendLine("悬赏想要：")
            appendLine("领域：" + requirement.domainLabel)
            appendLine("要的内容：" + requirement.wants.joinToString("、"))
            appendLine("关键词：" + requirement.matchKeywords.joinToString("、"))
            appendLine("")
            appendLine("这张图片的 OCR 文字是：" + (ocrText.take(300).ifBlank { "（没有识别出文字）" }))
            appendLine("")
            appendLine("请直接看图，判断画面本身是否真的和悬赏内容相关。注意：")
            appendLine("1. 只有文字、但画面是白板/空白模板/聊天截图/表情包/菜单/宣传海报的，判 relevant=false；")
            appendLine("2. 画面是用户自己拍的实物（饭菜、风景、宠物、手写笔记等）且与需求相关，判 relevant=true；")
            appendLine("3. 不要被文件名或 OCR 文字误导，只依据你看到的画面。")
            appendLine("")
            appendLine("""输出 JSON：{"relevant":true,"scene":"画面内容简述，30字内","reason":"一句话理由"}""")
        }

        val json = client.chatVisionJson(system, user, path) ?: return null
        val relevant = when (val raw = json["relevant"]) {
            is Boolean -> raw
            else -> Json.asString(raw)?.equals("true", ignoreCase = true) == true
        }
        val scene = Json.asString(json["scene"])?.trim()?.take(120)
        val reason = Json.asString(json["reason"])?.trim()?.take(200)
        log.info("vision judged {} -> relevant={} scene={}", fileName, relevant, scene)
        return VisionVerdict(relevant, scene, reason)
    }
}
