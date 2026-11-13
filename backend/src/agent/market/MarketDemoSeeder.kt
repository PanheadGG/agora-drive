package com.pgigi.agentclouddisk.agent.market

import com.pgigi.agentclouddisk.agent.DemoContentFactory
import com.pgigi.agentclouddisk.common.Roles
import com.pgigi.agentclouddisk.mapper.UserFileMapper
import com.pgigi.agentclouddisk.mapper.UserMapper
import com.pgigi.agentclouddisk.mapper.market.DataProductMapper
import com.pgigi.agentclouddisk.mapper.market.MarketMapper
import com.pgigi.agentclouddisk.model.FileRow
import com.pgigi.agentclouddisk.service.FileService
import com.pgigi.agentclouddisk.service.UploadService
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import java.util.concurrent.TimeUnit

/**
 * 市场演示数据（多卖方 + 买方，双向角色）：
 * - seller_a / sell1234 ：通勤记录（通勤时效统计模板）
 * - seller_b / sell1234 ：另一个城市的通勤记录（用于验证"联合产品"需要两边共同分组）
 * - buyer    / buy12345 ：发布一条通勤类采购需求，演示主动推送
 * 全部账号 role=0（买卖双向）。
 */
@Component
@Order(30)
class MarketDemoSeeder(
    private val userMapper: UserMapper,
    private val userFileMapper: UserFileMapper,
    private val productMapper: DataProductMapper,
    private val marketMapper: MarketMapper,
    private val fileService: FileService,
    private val uploadService: UploadService,
    private val encoder: org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder,
    private val marketService: com.pgigi.agentclouddisk.agent.market.MarketService,
    private val sellerAgent: SellerAgentService,
) : ApplicationRunner {

    private val log = LoggerFactory.getLogger(MarketDemoSeeder::class.java)

    override fun run(args: ApplicationArguments) {
        val a = seedUser("seller_a", "sell1234")
        val b = seedUser("seller_b", "sell1234")
        val buyer = seedUser("buyer", "buy12345")

        seedCommuteFiles(a.first, "城东通勤", listOf(
            listOf("2026-03-02", "地铁2号线", "07:40", "08:25", "45", "无"),
            listOf("2026-03-03", "地铁2号线", "07:38", "08:20", "42", "无"),
            listOf("2026-03-04", "地铁2号线", "07:45", "08:31", "46", "无"),
            listOf("2026-03-05", "公交202路", "07:20", "08:35", "75", "换乘一次"),
            listOf("2026-03-06", "公交202路", "07:18", "08:30", "72", "换乘一次"),
        ))
        // 每条线路至少 3 行，才能通过 k=3 匿名门槛（否则联合产品没有共同分组）
        seedCommuteFiles(b.first, "城西通勤", listOf(
            listOf("2026-03-02", "地铁2号线", "08:10", "08:58", "48", "无"),
            listOf("2026-03-03", "地铁2号线", "08:12", "09:00", "48", "无"),
            listOf("2026-03-04", "地铁2号线", "08:08", "08:56", "48", "无"),
            listOf("2026-03-05", "地铁1号线", "07:55", "08:45", "50", "换乘一次"),
            listOf("2026-03-06", "地铁1号线", "07:58", "08:50", "52", "换乘一次"),
            listOf("2026-03-09", "地铁1号线", "07:52", "08:42", "50", "换乘一次"),
        ))

        // 预先构建两个卖方的产品，让市场一打开就有货源
        buildIfEmpty(a.first, "通勤时效统计", "commute_stats", a.second)
        buildIfEmpty(b.first, "通勤时效统计", "commute_stats", b.second)

        // 买方需求（发布时会自动匹配推送）
        if (marketMapper.listDemandsByBuyer(buyer.first).isEmpty()) {
            marketService.createDemand(
                buyer.first,
                MarketService.DemandRequest(
                    title = "需要本地通勤时效数据",
                    rawDesc = "想了解本市早晚高峰的通勤耗时情况，最好是地铁/公交线路级别的平均耗时，近一年数据，不要含个人信息。",
                    budgetCents = 20000,
                ),
            )
            log.info("seeded buyer demand for user {}", buyer.first)
        }
    }

    private fun seedUser(username: String, password: String): Pair<Long, String> {
        val existing = userMapper.findByUsername(username)
        if (existing != null) {
            if (existing.role != Roles.USER) userMapper.updateRole(existing.id ?: 0, Roles.USER)
            return (existing.id ?: 0) to username
        }
        val id = userMapper.insert(username, encoder.encode(password)!!, Roles.USER)
        log.info("seeded market account {} (id={})", username, id)
        return id to username
    }

    private fun seedCommuteFiles(userId: Long, folderName: String, rows: List<List<String>>) {
        if (userFileMapper.countByUser(userId) > 0) return
        val folderId = fileService.createFolder(userId, null, folderName).id
        val bytes = DemoContentFactory.xlsx(
            listOf("日期", "线路", "出发时间", "到达时间", "耗时(分钟)", "换乘"),
            rows,
        )
        uploadService.saveSmall(userId, folderId, "通勤记录.xlsx", bytes.inputStream())
        log.info("seeded commute file for user {} under {}", userId, folderName)
    }

    private fun buildIfEmpty(userId: Long, title: String, templateKey: String, userName: String) {
        if (productMapper.listByOwner(userId).isNotEmpty()) return
        val folders = userFileMapper.listFolders(userId)
        if (folders.isEmpty()) return
        val scope = folders.mapNotNull { it.id }
        val handle = sellerAgent.createAndBuild(
            userId,
            SellerAgentService.CreateProductRequest(templateKey, title, scope, true),
        )
        // 同步等待构建完成（Seeder 里可以等，避免市场页面空着）
        val deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(60)
        while (System.currentTimeMillis() < deadline) {
            val task = productMapper.findTask(handle.taskId)
            if (task != null && (task.taskStatus == 2 || task.taskStatus == 3)) break
            Thread.sleep(300)
        }
        val product = productMapper.findById(handle.productId)
        if (product != null && product.rowCount > 0) {
            runCatching {
                marketService.publish(
                    userId, handle.productId,
                    MarketService.PublishRequest(
                        title = title + "（" + userName + "）",
                        summary = "来自 " + userName + " 的通勤记录聚合，按线路统计平均与 90 分位耗时",
                        priceCents = if (userName == "seller_a") 5000 else 7000,
                        licenseType = "internal",
                    ),
                )
                log.info("seeded listing for {} groups={}", userName, product.rowCount)
            }.onFailure { log.warn("seed listing failed for {}: {}", userName, it.message) }
        } else {
            log.warn("seed product empty for {}: groups={}", userName, product?.rowCount)
        }
    }
}
