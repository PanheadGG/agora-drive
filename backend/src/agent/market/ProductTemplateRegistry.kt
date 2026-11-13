package com.pgigi.agentclouddisk.agent.market

import org.springframework.stereotype.Component

/**
 * 数据产品模板：定义"从原始文件里抽什么字段、怎么聚合"。
 * Demo 用固定模板，让 Agent 的产出稳定可核对——而不是自由发挥。
 */
@Component
class ProductTemplateRegistry {

    data class FieldSpec(val key: String, val label: String, val kind: String)

    data class Template(
        val key: String,
        val label: String,
        val domain: String,
        val description: String,
        val fields: List<FieldSpec>,
        /** 分组维度：聚合产物按这些字段分组，组内不足 k 条丢弃（k-匿名） */
        val groupBy: List<String>,
        /** 每组输出的度量 */
        val measures: List<String>,
        /** 用于市场召回的默认标签 */
        val tags: List<String>,
        /** 抽取该模板所需的关键词（在正文/表头中出现即视为命中该模板的一行） */
        val cues: List<String>,
        val aggregateLabel: String,
        val measureLabel: String,
    )

    private val templates = listOf(
        Template(
            key = "commute_stats",
            label = "通勤时效统计",
            domain = "通勤出行",
            description = "统计个人通勤线路的出发/到达时间与耗时，按线路聚合",
            fields = listOf(
                FieldSpec("route", "线路", "string"),
                FieldSpec("depart", "出发时间", "time"),
                FieldSpec("arrive", "到达时间", "time"),
                FieldSpec("duration", "耗时(分钟)", "number"),
                FieldSpec("transfer", "换乘", "string"),
            ),
            groupBy = listOf("route"),
            measures = listOf("avg_duration", "p90_duration", "sample_count"),
            tags = listOf("通勤", "出行", "时效", "线路", "耗时"),
            cues = listOf("通勤", "线路", "出发时间", "到达时间", "耗时", "换乘", "地铁", "公交"),
            aggregateLabel = "按线路聚合的平均/90分位耗时",
            measureLabel = "分钟",
        ),
        Template(
            key = "recipe_library",
            label = "家常菜谱库",
            domain = "餐饮菜谱",
            description = "从个人菜谱文档中抽取菜名、食材与步骤，按菜名聚合",
            fields = listOf(
                FieldSpec("dish", "菜名", "string"),
                FieldSpec("ingredients", "食材", "text"),
                FieldSpec("steps", "步骤", "text"),
            ),
            groupBy = listOf("dish"),
            measures = listOf("ingredient_count", "step_count", "sample_count"),
            tags = listOf("菜谱", "家常菜", "食材", "做法"),
            cues = listOf("菜谱", "食材", "步骤", "做法", "红烧", "清蒸", "翻炒", "出锅"),
            aggregateLabel = "按菜名聚合的食材数/步骤数",
            measureLabel = "项",
        ),
        Template(
            key = "pet_feeding",
            label = "宠物喂养记录",
            domain = "宠物健康",
            description = "抽取宠物喂养表中的体重、驱虫、换粮记录",
            fields = listOf(
                FieldSpec("pet", "宠物", "string"),
                FieldSpec("weight", "体重(kg)", "number"),
                FieldSpec("deworm", "驱虫", "string"),
                FieldSpec("food", "换粮", "string"),
            ),
            groupBy = listOf("pet"),
            measures = listOf("avg_weight", "record_count"),
            tags = listOf("宠物", "猫", "喂养", "体重", "驱虫", "换粮"),
            cues = listOf("猫", "狗", "喂养", "体重", "驱虫", "换粮", "疫苗"),
            aggregateLabel = "按宠物聚合的平均体重与记录数",
            measureLabel = "kg",
        ),
        Template(
            key = "reading_notes",
            label = "读书笔记主题",
            domain = "阅读学习",
            description = "从读后感/摘抄中抽取书目与主题词",
            fields = listOf(
                FieldSpec("book", "书目", "string"),
                FieldSpec("topic", "主题", "string"),
                FieldSpec("excerpt", "摘抄", "text"),
            ),
            groupBy = listOf("book"),
            measures = listOf("note_count", "topic_variety"),
            tags = listOf("读书", "读后感", "摘抄", "笔记", "书评"),
            cues = listOf("读后感", "读书", "摘抄", "笔记", "书中", "作者"),
            aggregateLabel = "按书目聚合的笔记数与主题多样性",
            measureLabel = "条",
        ),
    )

    fun all(): List<Template> = templates

    fun byKey(key: String): Template? = templates.firstOrNull { it.key == key }

    fun detect(text: String): Template? {
        val scored = templates.map { template ->
            template to template.cues.count { text.contains(it) }
        }
        return scored.maxByOrNull { it.second }?.takeIf { it.second > 0 }?.first
    }
}
