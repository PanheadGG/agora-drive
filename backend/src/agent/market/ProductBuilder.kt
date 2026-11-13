package com.pgigi.agentclouddisk.agent.market

import com.pgigi.agentclouddisk.agent.FileContentParser
import com.pgigi.agentclouddisk.agent.LlmClient
import com.pgigi.agentclouddisk.agent.Json
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.security.MessageDigest

/**
 * 卖方 Agent 的加工能力：Raw Data → 数据产品。
 *
 * 关键约束（对应改造方案里的硬问题）：
 * 1. 产品只包含**聚合结果**，不包含原始行，所以上架后不存在行级泄露；
 * 2. 每个分组至少 k 条才保留（k-匿名），否则丢弃并计数；
 * 3. 含 PII 的文件在构建阶段就被排除，并写进构建凭证；
 * 4. 构建凭证记录原料哈希，保证"我买到的=我看到的"。
 */
@Service
class ProductBuilder(
    private val templates: ProductTemplateRegistry,
    private val llmClient: LlmClient,
) {

    private val log = LoggerFactory.getLogger(ProductBuilder::class.java)

    data class Extraction(
        val template: ProductTemplateRegistry.Template,
        val rows: List<Map<String, String>>,
        val usedFiles: List<UsedFile>,
        val excludedFiles: List<UsedFile>,
        val rowCount: Int,
        val coverage: Pair<String?, String?>,
        val quality: Map<String, Any?>,
        val sample: List<Map<String, String>>,
        val receipt: Map<String, Any?>,
        val piiClean: Boolean,
    )

    data class UsedFile(
        val fileId: Long,
        val fileName: String,
        val sha256: String?,
        val parseMethod: String,
        val rows: Int,
        val riskTag: String? = null,
        val note: String? = null,
    )

    /** 从解析结果里按模板抽行；解析失败的图片/PDF 记账但不参与 */
    fun extract(
        templateKey: String,
        parsed: List<Triple<com.pgigi.agentclouddisk.model.FileRow, FileContentParser.ParsedFile, Int>>,
        aiEnabled: Boolean,
    ): Extraction {
        val template = templates.byKey(templateKey) ?: templates.all().first()
        val rows = ArrayList<Map<String, String>>()
        val used = ArrayList<UsedFile>()
        val excluded = ArrayList<UsedFile>()

        for ((row, file, _) in parsed) {
            val fileId = row.id ?: 0
            if (!file.ok) {
                excluded += UsedFile(fileId, row.fileName, row.sha256, file.method, 0, null, file.note ?: "未解析")
                continue
            }
            if (file.risk != null || file.copied) {
                excluded += UsedFile(
                    fileId, row.fileName, row.sha256, file.method, 0,
                    file.risk?.tag ?: "网络搬运",
                    "出厂前 PII/搬运检查命中，未进入产品",
                )
                continue
            }
            val extracted = extractRows(template, file)
            if (extracted.isEmpty()) {
                excluded += UsedFile(fileId, row.fileName, row.sha256, file.method, 0, null, "内容与模板不匹配")
                continue
            }
            rows += extracted
            used += UsedFile(fileId, row.fileName, row.sha256, file.method, extracted.size)
        }

        val aggregated = anonymizeAggregate(template, rows)
        val coverage = coverageOf(rows)
        val quality = qualityOf(rows, aggregated.kept, aggregated.suppressed, used.size)
        val receipt = buildReceipt(template, used, excluded, rows.size, aiEnabled)

        return Extraction(
            template = template,
            rows = aggregated.kept,
            usedFiles = used,
            excludedFiles = excluded,
            rowCount = rows.size,
            coverage = coverage,
            quality = quality,
            sample = aggregated.kept.take(SAMPLE_ROWS),
            receipt = receipt,
            piiClean = true,
        )
    }

    // ---------- 抽取 ----------

    private fun extractRows(template: ProductTemplateRegistry.Template, file: FileContentParser.ParsedFile): List<Map<String, String>> {
        val lines = file.text.lineSequence().map { it.trim() }.filter { it.isNotBlank() }.toList()
        if (lines.isEmpty()) return emptyList()

        return when (template.key) {
            "commute_stats" -> extractCommute(lines)
            "recipe_library" -> extractRecipes(lines)
            "pet_feeding" -> extractPet(lines)
            "reading_notes" -> extractReading(lines)
            else -> emptyList()
        }
    }

    /** 通勤表：日期 | 线路 | 出发 | 到达 | 耗时 | 换乘 */
    private fun extractCommute(lines: List<String>): List<Map<String, String>> {
        val out = ArrayList<Map<String, String>>()
        for (line in lines) {
            val cells = splitCells(line)
            if (cells.size < 4) continue
            val duration = cells.mapNotNull { it.toIntOrNull() }.firstOrNull { it in 1..600 } ?: continue
            val route = cells.firstOrNull { it.contains("地铁") || it.contains("公交") || it.contains("班车") || it.contains("号线") }
                ?: cells.getOrNull(1) ?: continue
            val depart = cells.firstOrNull { TIME.matches(it) }
            val arrive = cells.filter { TIME.matches(it) }.getOrNull(1)
            out += mapOf(
                "route" to route,
                "depart" to (depart ?: ""),
                "arrive" to (arrive ?: ""),
                "duration" to duration.toString(),
                "transfer" to (cells.lastOrNull { it.length <= 6 && !it.contains(":") } ?: ""),
            )
        }
        return out
    }

    /** 菜谱：识别"菜名：食材 … 步骤 …"的分句 */
    private fun extractRecipes(lines: List<String>): List<Map<String, String>> {
        val out = ArrayList<Map<String, String>>()
        for (line in lines) {
            if (line.length < 6) continue
            val hit = DISH_HEAD.find(line) ?: continue
            val dish = hit.groupValues[1].trim().take(20)
            if (dish.length < 2) continue
            val ingredients = INGREDIENTS.find(line)?.groupValues?.get(1)?.trim()?.take(120) ?: ""
            val steps = STEPS.find(line)?.groupValues?.get(1)?.trim()?.take(200) ?: ""
            if (ingredients.isEmpty() && steps.isEmpty()) continue
            out += mapOf("dish" to dish, "ingredients" to ingredients, "steps" to steps)
        }
        return out
    }

    /** 宠物喂养表：日期 | 猫名 | 体重 | 驱虫 | 换粮 | 备注 */
    private fun extractPet(lines: List<String>): List<Map<String, String>> {
        val out = ArrayList<Map<String, String>>()
        for (line in lines) {
            val cells = splitCells(line)
            if (cells.size < 3) continue
            val weight = cells.mapNotNull { it.replace("kg", "").trim().toDoubleOrNull() }.firstOrNull { it in 0.2..60.0 } ?: continue
            val pet = cells.firstOrNull { it.contains("猫") || it.contains("狗") || it.contains("橘") || it.contains("狸") }
                ?: cells.getOrNull(1) ?: continue
            out += mapOf(
                "pet" to pet.take(12),
                "weight" to weight.toString(),
                "deworm" to (cells.firstOrNull { it.contains("驱虫") } ?: ""),
                "food" to (cells.firstOrNull { it.contains("换粮") || it.contains("粮") } ?: ""),
            )
        }
        return out
    }

    /** 读书笔记：书名 + 主题句子 */
    private fun extractReading(lines: List<String>): List<Map<String, String>> {
        val out = ArrayList<Map<String, String>>()
        var currentBook: String? = null
        for (line in lines) {
            if (line.length < 6) continue
            val bookHit = BOOK.find(line)
            if (bookHit != null) {
                currentBook = bookHit.groupValues[1].trim().take(30)
                val topic = TOPIC.find(line)?.groupValues?.get(1)?.trim()?.take(60) ?: ""
                out += mapOf("book" to (currentBook ?: ""), "topic" to topic, "excerpt" to line.take(120))
                continue
            }
            if (currentBook != null && line.any { it in '一'..'龥' }) {
                val topic = TOPIC.find(line)?.groupValues?.get(1)?.trim()?.take(60) ?: ""
                out += mapOf("book" to currentBook, "topic" to topic, "excerpt" to line.take(120))
            }
        }
        return out
    }

    // ---------- 聚合 + k-匿名 ----------

    private data class Aggregated(val kept: List<Map<String, String>>, val suppressed: Int)

    private fun anonymizeAggregate(template: ProductTemplateRegistry.Template, rows: List<Map<String, String>>): Aggregated {
        val groupKey = template.groupBy.firstOrNull() ?: return Aggregated(emptyList(), 0)
        val groups = rows.groupBy { it[groupKey] ?: "未分类" }
        val kept = ArrayList<Map<String, String>>()
        var suppressed = 0

        for ((key, group) in groups) {
            if (group.size < K_ANONYMITY) {
                suppressed++
                continue
            }
            val entry = linkedMapOf<String, String>()
            entry[groupKey] = key
            entry["sample_count"] = group.size.toString()

            when (template.key) {
                "commute_stats" -> {
                    val durations = group.mapNotNull { it["duration"]?.toDoubleOrNull() }.sorted()
                    if (durations.isNotEmpty()) {
                        entry["avg_duration"] = "%.1f".format(durations.average())
                        entry["p90_duration"] = "%.0f".format(durations[(durations.size * 0.9).toInt().coerceAtMost(durations.size - 1)])
                    }
                }
                "recipe_library" -> {
                    entry["ingredient_count"] = group.count { !it["ingredients"].isNullOrBlank() }.toString()
                    entry["step_count"] = group.count { !it["steps"].isNullOrBlank() }.toString()
                }
                "pet_feeding" -> {
                    val weights = group.mapNotNull { it["weight"]?.toDoubleOrNull() }
                    if (weights.isNotEmpty()) entry["avg_weight"] = "%.2f".format(weights.average())
                    entry["record_count"] = group.size.toString()
                }
                "reading_notes" -> {
                    entry["note_count"] = group.size.toString()
                    entry["topic_variety"] = group.mapNotNull { it["topic"] }.filter { it.isNotBlank() }.distinct().size.toString()
                }
            }
            kept += entry
        }
        return Aggregated(kept.sortedByDescending { it["sample_count"]?.toIntOrNull() ?: 0 }, suppressed)
    }

    private fun coverageOf(rows: List<Map<String, String>>): Pair<String?, String?> {
        val dates = rows.flatMap { row ->
            DATE.findAll(row.values.joinToString(" ")).map { it.value }.toList()
        }.sorted()
        return (dates.firstOrNull() to dates.lastOrNull())
    }

    private fun qualityOf(
        rows: List<Map<String, String>>,
        aggregated: List<Map<String, String>>,
        suppressed: Int,
        fileCount: Int,
    ): Map<String, Any?> {
        val totalGroups = aggregated.size + suppressed
        return linkedMapOf(
            "rawRows" to rows.size,
            "groups" to aggregated.size,
            "suppressedGroups" to suppressed,
            "kAnonymity" to K_ANONYMITY,
            "coverageRatio" to if (totalGroups == 0) 0 else (aggregated.size * 100 / totalGroups),
            "sourceFiles" to fileCount,
            "score" to scoreOf(rows.size, aggregated.size, fileCount),
        )
    }

    /** 简单的可复现质量分：数据量 + 分组覆盖 + 来源多样性 */
    private fun scoreOf(rawRows: Int, groups: Int, files: Int): Int {
        val rowScore = (rawRows.coerceAtMost(200) * 100 / 200)
        val groupScore = (groups.coerceAtMost(20) * 100 / 20)
        val fileScore = (files.coerceAtMost(10) * 100 / 10)
        return (rowScore * 40 + groupScore * 35 + fileScore * 25) / 100
    }

    private fun buildReceipt(
        template: ProductTemplateRegistry.Template,
        used: List<UsedFile>,
        excluded: List<UsedFile>,
        rawRows: Int,
        aiEnabled: Boolean,
    ): Map<String, Any?> = linkedMapOf(
        "template" to template.key,
        "templateLabel" to template.label,
        "rawRows" to rawRows,
        "aiEnabled" to aiEnabled,
        "model" to if (aiEnabled) llmClient.activeModel else null,
        "kAnonymity" to K_ANONYMITY,
        "sources" to used.map {
            linkedMapOf(
                "fileId" to it.fileId,
                "sha256" to it.sha256,
                "parseMethod" to it.parseMethod,
                "rows" to it.rows,
            )
        },
        "excluded" to excluded.map {
            linkedMapOf("fileId" to it.fileId, "reason" to (it.riskTag ?: it.note))
        },
        "digest" to digestOf(used.mapNotNull { it.sha256 }),
    )

    private fun digestOf(hashes: List<String>): String {
        val joined = hashes.sorted().joinToString("|")
        val bytes = MessageDigest.getInstance("SHA-256").digest(joined.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }.take(32)
    }

    /** 让模型为产品生成 3-5 个市场召回标签（失败就退回模板标签） */
    fun describeTags(template: ProductTemplateRegistry.Template, aggregated: List<Map<String, String>>, aiEnabled: Boolean): List<String> {
        if (!aiEnabled || !llmClient.available) return template.tags
        val preview = aggregated.take(5).joinToString("；") { it.entries.joinToString(",") { e -> e.key + "=" + e.value } }
        val json = llmClient.chatJson(
            "你是数据产品描述生成器。只输出单个 JSON 对象。",
            buildString {
                appendLine("产品模板：" + template.label + "（" + template.description + "）")
                appendLine("聚合结果示例：" + preview)
                appendLine("请输出 3-5 个中文标签，用于买方搜索召回，不要编造数据里没有的内容。")
                appendLine("""输出 JSON：{"tags":["标签1","标签2"]}""")
            },
            0.2,
        ) ?: return template.tags
        val tags = (Json.asList(json["tags"]) ?: emptyList()).mapNotNull { Json.asString(it) }.filter { it.isNotBlank() }.take(5)
        return tags.ifEmpty { template.tags }
    }

    private fun splitCells(line: String): List<String> =
        line.split('|').map { it.trim() }.filter { it.isNotEmpty() }

    companion object {
        const val K_ANONYMITY = 3
        private const val SAMPLE_ROWS = 8
        private val TIME = Regex("""^\d{1,2}:\d{2}$""")
        private val DATE = Regex("""\d{4}-\d{2}(-\d{2})?""")
        private val DISH_HEAD = Regex("""[一二三四五六七八九十\d]+[、.．]\s*([^：:，,。]{2,12})[：:]""")
        private val INGREDIENTS = Regex("""食材[：:]\s*([^；;。]+)""")
        private val STEPS = Regex("""步骤[：:]\s*([^；;。]+)""")
        private val BOOK = Regex("""《([^》]{1,30})》|([^\s：:]{2,20})\s*(读后感|读书笔记|摘抄)""")
        private val TOPIC = Regex("""(?:主题|关于|讲的是)[：:]?\s*([^。；;]{2,40})""")
    }
}
