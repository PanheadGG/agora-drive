package com.pgigi.agentclouddisk.agent

/**
 * 把企业录入的自然语言悬赏解析成结构化需求对象。
 * Demo 采用可解释的规则解析（关键词表），对外形态与 LLM 输出一致；
 * 后续接入真实 LLM 时只需替换本类的实现。
 */
object BountyStructureParser {

    data class Requirement(
        val domain: String,
        val domainLabel: String,
        val wants: List<String>,
        val formats: List<String>,
        val matchKeywords: List<String>,
        val excludeKeywords: List<String>,
        val yearLimit: Int?,
        val understanding: String,
    ) {
        fun toJson(): String = buildString {
            append("{")
            append("\"domain\":").append(quote(domain)).append(",")
            append("\"domainLabel\":").append(quote(domainLabel)).append(",")
            append("\"wants\":").append(array(wants)).append(",")
            append("\"formats\":").append(array(formats)).append(",")
            append("\"matchKeywords\":").append(array(matchKeywords)).append(",")
            append("\"excludeKeywords\":").append(array(excludeKeywords)).append(",")
            append("\"yearLimit\":").append(yearLimit?.toString() ?: "null").append(",")
            append("\"sensitivePolicy\":").append(quote("检出身份证/手机号/银行卡/住址等敏感信息即标高风险"))

            append(",\"understanding\":").append(quote(understanding))
            append("}")
        }
    }

    /** 扫描时从库里已存储的结构化 JSON 还原需求，保证「按发布时解析出的口径」执行 */
    fun fromJson(text: String): Requirement? {
        val json = Json.parseObject(text) ?: return null
        return Requirement(
            domain = Json.asString(json["domain"]) ?: "generic",
            domainLabel = Json.asString(json["domainLabel"]) ?: "通用资料",
            wants = Json.asStringList(json["wants"]),
            formats = Json.asStringList(json["formats"]),
            matchKeywords = Json.asStringList(json["matchKeywords"]).ifEmpty { emptyList() },
            excludeKeywords = Json.asStringList(json["excludeKeywords"]),
            yearLimit = Json.asInt(json["yearLimit"], 0).takeIf { it > 0 },
            understanding = Json.asString(json["understanding"]) ?: "",
        )
    }

    private data class DomainSpec(
        val key: String,
        val label: String,
        val cues: List<String>,
        val wants: List<String>,
        val keywords: List<String>,
    )

    private val DOMAINS = listOf(
        DomainSpec(
            key = "cooking",
            label = "家常做法/菜谱",
            cues = listOf("菜谱", "做菜", "做饭", "美食", "做法", "厨房"),
            wants = listOf("自己拍的美食成品照片", "手写菜谱照片", "自己整理的菜谱文档", "记录做法的表格"),
            keywords = listOf("菜谱", "做法", "食材", "步骤", "家常", "红烧", "清蒸", "小炒", "调料", "腌制", "火候", "出锅"),
        ),
        DomainSpec(
            key = "hiking",
            label = "徒步/爬山路线",
            cues = listOf("徒步", "爬山", "路线", "登山", "山里"),
            wants = listOf("沿途照片（岔路口/补给点/地标）", "自己整理的路线文档", "路线里程表格"),
            keywords = listOf("徒步", "爬山", "路线", "公里", "岔路", "补给", "海拔", "轨迹", "登山", "徒步道"),
        ),
        DomainSpec(
            key = "pet",
            label = "家养宠物日常记录",
            cues = listOf("猫", "狗", "宠物", "喂养", "铲屎"),
            wants = listOf("喂养记录表格", "宠物状态照片", "文字喂养记录"),
            keywords = listOf("猫", "狗", "喂养", "体重", "驱虫", "换粮", "疫苗", "铲屎", "投喂", "猫粮"),
        ),
        DomainSpec(
            key = "reading",
            label = "读书笔记/读后感",
            cues = listOf("读后感", "读书", "笔记", "摘抄", "书评"),
            wants = listOf("自己写的读后感文档", "摘抄笔记", "手写笔记照片"),
            keywords = listOf("读后感", "读书", "摘抄", "笔记", "书中", "作者", "感想", "章节", "这本书"),
        ),
        DomainSpec(
            key = "commute",
            label = "日常通勤记录",
            cues = listOf("通勤", "上下班", "地铁", "公交", "班车"),
            wants = listOf("通勤记录表格", "通勤时间表照片", "通勤记录文档"),
            keywords = listOf("通勤", "地铁", "公交", "班车", "出发", "到达", "换乘", "线路", "分钟"),
        ),
    )

    private val BASE_EXCLUDE = listOf("网红", "小红书", "公众号", "转载", "搬运", "宣传", "攻略下载", "官网下载")
    private val IMAGE_CUES = listOf("图片", "照片", "拍照", "扫描件", "截图", "识图")
    private val DOC_CUES = listOf("word", "文档", "docx", "手写")
    private val SHEET_CUES = listOf("表格", "excel", "xlsx", "台账")
    private val TEXT_CUES = listOf("文本", "txt", "纯文本", "文字记录")
    private val PDF_CUES = listOf("pdf")

    fun parse(rawText: String): Requirement {
        val text = rawText.lowercase()
        val matched = DOMAINS.firstOrNull { spec -> spec.cues.any { text.contains(it) } }
        val domain = matched ?: DomainSpec(
            key = "generic",
            label = "通用资料",
            cues = emptyList(),
            wants = listOf("用户自己整理的相关文档", "用户自己整理的相关表格"),
            keywords = genericKeywords(rawText),
        )

        val formats = buildList {
            if (IMAGE_CUES.any { text.contains(it) }) add("image")
            if (DOC_CUES.any { text.contains(it) }) add("docx")
            if (SHEET_CUES.any { text.contains(it) }) add("xlsx")
            if (TEXT_CUES.any { text.contains(it) }) add("text")
            if (PDF_CUES.any { text.contains(it) }) add("pdf")
        }.ifEmpty { listOf("docx", "text") }

        val exclude = (BASE_EXCLUDE + extractExclusions(rawText)).distinct()
        val yearLimit = Regex("""近\s*([一二两三四五1-5])\s*年""").find(rawText)?.groupValues?.get(1)?.let { token ->
            mapOf("一" to 1, "二" to 2, "两" to 2, "三" to 3, "四" to 4, "五" to 5)[token] ?: token.toIntOrNull()
        }

        val understanding = buildString {
            append("这条悬赏要的是「").append(domain.label).append("」相关的")
            append(domain.wants.joinToString("、"))
            append("；可接受格式：").append(formatLabel(formats))
            append("；需排除：").append(exclude.joinToString("、"))
            if (yearLimit != null) append("；时间优先近 ").append(yearLimit).append(" 年")
        }

        return Requirement(
            domain = domain.key,
            domainLabel = domain.label,
            wants = domain.wants,
            formats = formats,
            matchKeywords = domain.keywords,
            excludeKeywords = exclude,
            yearLimit = yearLimit,
            understanding = understanding,
        )
    }

    /** 未识别到具体领域时，从"要的内容"里抽出可用于匹配的词，避免整条悬赏无法命中 */
    private fun genericKeywords(rawText: String): List<String> {
        val suffix = Regex("(表格|文档|图片|照片|记录|素材|文件|内容)$")
        return rawText.split('\n', '\r', '、', '，', ',', '；', ';', '。')
            .map { it.substringAfter('：', it.substringAfter(':')).trim() }
            .filter { it.length in 2..14 }
            .map { it.replace(suffix, "").trim() }
            .filter { it.length in 2..8 }
            .filterNot { Regex("(?i)(word|excel|pdf|txt|xlsx|docx|图片|照片|表格|文档|记录|素材)$").containsMatchIn(it) }
            .filterNot { BASE_EXCLUDE.any { word -> it.contains(word) } }
            .distinct()
            .take(8)
    }

    private fun extractExclusions(rawText: String): List<String> {
        val marker = Regex("(不要|排除|禁止|不含|不收)\\s*[:：]?")
        val bucket = mutableListOf<String>()
        for (line in rawText.split('\n', '\r')) {
            val hit = marker.find(line) ?: continue
            line.substring(hit.range.last + 1)
                .split('、', '，', ',', '；', ';', '。', '/')
                .map { it.trim() }
                .filter { it.length in 2..14 }
                .forEach { bucket += it }
        }
        return bucket.distinct().take(10)
    }

    private fun formatLabel(formats: List<String>): String = formats.joinToString("、") {
        when (it) {
            "image" -> "图片"
            "docx" -> "Word/文档"
            "xlsx" -> "表格"
            "text" -> "纯文本"
            "pdf" -> "PDF"
            else -> it
        }
    }

    private fun quote(value: String): String =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private fun array(values: List<String>): String = values.joinToString(",", "[", "]") { quote(it) }
}
