package com.pgigi.agentclouddisk.agent

import com.pgigi.agentclouddisk.common.Roles
import com.pgigi.agentclouddisk.mapper.BountyMapper
import com.pgigi.agentclouddisk.mapper.UserFileMapper
import com.pgigi.agentclouddisk.mapper.UserMapper
import com.pgigi.agentclouddisk.model.BountyRequest
import com.pgigi.agentclouddisk.service.FileService
import com.pgigi.agentclouddisk.service.StorageService
import com.pgigi.agentclouddisk.service.UploadService
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.core.annotation.Order
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.stereotype.Component
import java.nio.file.Files

/**
 * 演示数据：
 * - enterprise / ent12345：企业需求方（role=2），发布 5 条示例悬赏（配了 DeepSeek Key 时走 LLM 解析）
 * - demo / demo1234：个人用户，个人整理 + 随手拍 两个文件夹共 13 个**真实文件**
 *   （docx/xlsx 是 OOXML、pdf 带 xref 可抽文本、png 真实绘制可 OCR、zip 内含真实图片）
 * 内容版本变化时会重建 demo 的样本文件。
 */
@Component
@Order(20)
class AgentDemoSeeder(
    private val userMapper: UserMapper,
    private val userFileMapper: UserFileMapper,
    private val bountyMapper: BountyMapper,
    private val fileService: FileService,
    private val uploadService: UploadService,
    private val storage: StorageService,
    private val bountyService: BountyService,
    private val encoder: BCryptPasswordEncoder,
) : ApplicationRunner {

    private val log = LoggerFactory.getLogger(AgentDemoSeeder::class.java)

    override fun run(args: ApplicationArguments) {
        seedPersonalFiles()
        // 悬赏式演示数据已由 MarketDemoSeeder（卖方/买方模型）取代，不再播种
    }

    @Suppress("unused")
    private fun seedEnterprise(): Long {
        val existing = userMapper.findByUsername("enterprise")
        if (existing != null) {
            val id = existing.id ?: 0
            if (existing.role != Roles.ENTERPRISE) userMapper.updateRole(id, Roles.ENTERPRISE)
            return id
        }
        val id = userMapper.insert("enterprise", encoder.encode("ent12345")!!, Roles.ENTERPRISE)
        log.info("seeded enterprise account (id={})", id)
        return id
    }

    private fun seedPersonalFiles() {
        val demoId = userMapper.findByUsername("demo")?.id ?: return
        val marker = storage.root.resolve(DEMO_CONTENT_MARKER)
        if (Files.exists(marker)) return

        if (userFileMapper.countByUser(demoId) > 0) {
            userFileMapper.deleteAllByUser(demoId)
            userMapper.resetUsedSize(demoId, 0)
            log.info("replacing demo sample files with real content (v2)")
        }

        val tidy = fileService.createFolder(demoId, null, "个人整理").id
        val shots = fileService.createFolder(demoId, null, "随手拍").id

        fun upload(parentId: Long, name: String, bytes: ByteArray) {
            uploadService.saveSmall(demoId, parentId, name, bytes.inputStream())
        }

        // ---- 个人整理 ----
        upload(
            tidy, "做菜心得.docx", DemoContentFactory.docx(
                listOf(
                    "家常菜做法记录",
                    "一、番茄炒蛋：食材 番茄两个、鸡蛋三个；步骤：先把蛋液炒散盛出，再下番茄翻炒出汁，调料只放盐和少许糖，中火翻炒，出锅撒葱花。",
                    "二、红烧土豆：食材 土豆、五花肉；做法：五花肉煸出油后下土豆块，加生抽老抽和冰糖，小火焖二十分钟收汁。",
                    "三、清蒸鲈鱼：食材 鲈鱼一条、姜丝；步骤：水开后上锅蒸八分钟，倒掉腥水再淋蒸鱼豉油，火候别过头。",
                )
            )
        )
        upload(
            tidy, "通勤记录.xlsx", DemoContentFactory.xlsx(
                listOf("日期", "线路", "出发时间", "到达时间", "耗时(分钟)", "换乘"),
                listOf(
                    listOf("2026-02-03", "地铁2号线转1号线", "07:40", "08:25", "45", "五一广场"),
                    listOf("2026-02-04", "地铁2号线", "07:35", "08:20", "45", "无"),
                    listOf("2026-02-05", "公交202路", "07:20", "08:30", "70", "换乘一次"),
                    listOf("2026-02-06", "地铁2号线", "07:38", "08:22", "44", "无"),
                )
            )
        )
        upload(
            tidy, "猫猫喂养表.xlsx", DemoContentFactory.xlsx(
                listOf("日期", "猫名", "体重(kg)", "驱虫", "换粮", "备注"),
                listOf(
                    listOf("2026-06-01", "橘猫", "4.2", "已驱虫", "换粮 A 品牌", "食欲正常"),
                    listOf("2026-06-15", "橘猫", "4.4", "未驱虫", "无", "换粮后软便两天"),
                    listOf("2026-07-01", "橘猫", "4.5", "已驱虫", "混粮过渡", "精神状态好"),
                )
            )
        )
        upload(
            tidy, "平凡的世界读后感.docx", DemoContentFactory.docx(
                listOf(
                    "平凡的世界 读后感",
                    "书中孙少平的经历让人想起自己刚工作那几年，作者路遥写得很克制，没有刻意煽情。",
                    "印象最深的是他对读书的坚持，我在读书时摘抄了几段，感想写在后面。",
                )
            )
        )
        upload(
            tidy, "读书摘抄.docx", DemoContentFactory.docx(
                listOf(
                    "读书摘抄：平凡的世界",
                    "1. 生活不能等待别人来安排，要自己去争取和奋斗。",
                    "2. 只有劳动才可能使人在生活中强大。",
                )
            )
        )
        upload(
            tidy, "徒步手记.txt", DemoContentFactory.text(
                "徒步路线记录：周六从龙脊山北门出发，全程约 12.6 公里，海拔累计上升 620 米。" +
                    "中途在 5 公里处的凉亭有补给点，岔路口要选左侧石阶路，右侧是下山近道容易走错。" +
                    "返程走环线，全程用时 4 小时 20 分钟。"
            )
        )
        upload(
            tidy, "微信账单导出.xlsx", DemoContentFactory.xlsx(
                listOf("姓名", "手机号", "金额", "时间", "备注"),
                listOf(
                    listOf("张三", "13812345678", "128.50", "2026-07-01 12:30", "转账"),
                    listOf("李四", "13987654321", "66.00", "2026-07-02 09:15", "红包"),
                )
            )
        )
        upload(
            tidy, "长沙攻略下载.docx", DemoContentFactory.docx(
                listOf(
                    "长沙 3 天 2 晚攻略",
                    "本攻略来源：小红书 @旅行日记，公众号转载，侵权删。",
                    "第一天 橘子洲头，第二天 岳麓山，第三天 太平街。",
                )
            )
        )

        // ---- 随手拍（真实图片/PDF/压缩包）----
        upload(
            shots, "IMG_20250314.png",
            DemoContentFactory.photo("番茄炒蛋 家常菜", "2025-03-14 自己做的一顿饭", 0xD97706)
        )
        upload(
            shots, "微信图片_20250402.png",
            DemoContentFactory.photo("周末爬山 岔路口", "补给点在 5 公里处", 0x1F7A5A)
        )
        upload(
            shots, "小红书菜谱保存.png",
            DemoContentFactory.photo("小红书 网红菜谱", "保存自网络 转载 侵权删", 0xB91C1C)
        )
        upload(
            shots, "爬山照片合集.zip",
            DemoContentFactory.zipOf(
                "岭脚岔路口.png" to DemoContentFactory.photo("徒步路线 岔路口", "海拔 320 米 左侧石阶", 0x2563EB),
                "山顶补给点.png" to DemoContentFactory.photo("山顶补给点", "海拔 940 米 用时 3 小时", 0x7C3AED),
            )
        )
        upload(
            shots, "体检单扫描件.pdf",
            DemoContentFactory.pdf(
                "Physical Examination Report",
                listOf(
                    "Name: ZHANG SAN",
                    "ID No: 43010519900101123X",
                    "Mobile: 13812345678",
                    "Hospital: Xiangya Hospital",
                    "Item: Blood routine, Liver function",
                )
            )
        )

        Files.writeString(marker, "v2", Charsets.UTF_8)
        log.info("seeded demo personal sample files (real docx/xlsx/pdf/png/zip)")
    }

    @Suppress("unused")
    private fun seedBounties(enterpriseId: Long) {
        if (bountyMapper.countAll() > 0) return

        val items = listOf(
            "悬赏 R-01｜家常菜做法素材" to """
                发起人：喜欢做饭，想攒点家常做法。
                需求：家常菜做法素材
                要的内容：自己拍的美食成品照片、手写菜谱照片、自己打的菜谱文档、记录做法的表格
                文件类型：图片、Word、表格、PDF 都可以
                时间：不限年份，近一两年的优先
                不要：网上直接下载/复制的网红菜谱文章、外卖菜单截图、美食博主视频截图
            """.trimIndent(),
            "悬赏 R-02｜本地徒步爬山路线素材" to """
                发起人：周末想周边爬山，找靠谱路线。
                需求：本地徒步/爬山路线素材
                要的内容：自己拍的路线沿途照片（岔路口、补给点、地标）、自己整理的路线文档/表格
                文件类型：图片为主，Word/表格/PDF 辅助
                不要：小红书、旅游网站下载的现成攻略、旅行社宣传图
            """.trimIndent(),
            "悬赏 R-03｜家养猫咪日常记录素材" to """
                发起人：家里养猫，参考喂养经验。
                需求：家养猫咪日常记录素材
                要的内容：自己记的喂养表格（体重、驱虫、换粮）、拍的猫的状态照片、文字记录
                文件类型：表格、图片、文档
                不要：宠物店/医院宣传、卖猫信息、收费单据照片
            """.trimIndent(),
            "悬赏 R-04｜个人读书笔记读后感素材" to """
                发起人：平时爱看书，想看真实读后感。
                需求：个人读书笔记/读后感素材
                要的内容：自己写的读后感想文档、摘抄笔记
                文件类型：Word、文本、手写笔记照片
                不要：电子书原文、网上复制的范文、教辅资料
            """.trimIndent(),
            "悬赏 R-05｜日常通勤情况记录素材" to """
                发起人：想了解日常通勤情况。
                需求：通勤情况记录素材
                要的内容：自己记的通勤记录表格/文档、拍的通勤时间表照片
                文件类型：表格、图片、文档
                不要：含身份证/手机号/住址的信息、新闻报道、官方统计文件
            """.trimIndent(),
        )

        items.forEach { pair ->
            val created = bountyService.create(enterpriseId, Roles.ENTERPRISE, BountyRequest(pair.first, pair.second))
            log.info("seeded bounty {} (structuredBy={})", created.title, created.structuredBy)
        }
    }

    companion object {
        private const val DEMO_CONTENT_MARKER = ".demo-content-v2"
    }
}
