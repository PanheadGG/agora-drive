package com.pgigi.agentclouddisk.mapper.market

import com.pgigi.agentclouddisk.agent.market.CollabContribution
import com.pgigi.agentclouddisk.agent.market.CollabSession
import com.pgigi.agentclouddisk.agent.market.DeliveryRequest
import com.pgigi.agentclouddisk.agent.market.MarketDemand
import com.pgigi.agentclouddisk.agent.market.MarketMatch
import com.pgigi.agentclouddisk.agent.market.ProductListing
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import org.apache.ibatis.annotations.Select
import org.apache.ibatis.annotations.Update

@Mapper
interface MarketMapper {

    // ---------- 上架 ----------

    @Select(
        """INSERT INTO product_listing (product_id, seller_id, title, summary, field_list, tags_json, domain,
                  coverage_start, coverage_end, row_count, quality_score, price_cents, license_type, status)
           VALUES (#{productId}, #{sellerId}, #{title}, #{summary}, #{fieldList}::jsonb, #{tagsJson}::jsonb, #{domain},
                   #{coverageStart,jdbcType=VARCHAR}, #{coverageEnd,jdbcType=VARCHAR}, #{rowCount},
                   #{qualityScore}, #{priceCents}, #{licenseType}, 1)
           RETURNING id"""
    )
    fun insertListing(
        @Param("productId") productId: Long,
        @Param("sellerId") sellerId: Long,
        @Param("title") title: String,
        @Param("summary") summary: String,
        @Param("fieldList") fieldList: String,
        @Param("tagsJson") tagsJson: String,
        @Param("domain") domain: String,
        @Param("coverageStart") coverageStart: String?,
        @Param("coverageEnd") coverageEnd: String?,
        @Param("rowCount") rowCount: Long,
        @Param("qualityScore") qualityScore: Int,
        @Param("priceCents") priceCents: Long,
        @Param("licenseType") licenseType: String,
    ): Long

    @Select(
        """SELECT l.id, l.product_id, l.seller_id, l.title, l.summary,
                  l.field_list::text AS field_list, l.tags_json::text AS tags_json, l.domain,
                  l.coverage_start, l.coverage_end, l.row_count, l.quality_score, l.price_cents, l.license_type,
                  l.status, to_char(l.listed_at, 'YYYY-MM-DD HH24:MI:SS') AS listed_at,
                  to_char(l.delisted_at, 'YYYY-MM-DD HH24:MI:SS') AS delisted_at,
                  u.username AS seller_name
           FROM product_listing l JOIN "user" u ON u.id = l.seller_id
           WHERE l.id = #{id}"""
    )
    fun findListingById(@Param("id") id: Long): ProductListing?

    @Select(
        """SELECT l.id, l.product_id, l.seller_id, l.title, l.summary,
                  l.field_list::text AS field_list, l.tags_json::text AS tags_json, l.domain,
                  l.coverage_start, l.coverage_end, l.row_count, l.quality_score, l.price_cents, l.license_type,
                  l.status, to_char(l.listed_at, 'YYYY-MM-DD HH24:MI:SS') AS listed_at,
                  to_char(l.delisted_at, 'YYYY-MM-DD HH24:MI:SS') AS delisted_at,
                  u.username AS seller_name
           FROM product_listing l JOIN "user" u ON u.id = l.seller_id
           WHERE l.status = 1 ORDER BY l.quality_score DESC, l.id DESC"""
    )
    fun listActiveListings(): List<ProductListing>

    @Select(
        """SELECT l.id, l.product_id, l.seller_id, l.title, l.summary,
                  l.field_list::text AS field_list, l.tags_json::text AS tags_json, l.domain,
                  l.coverage_start, l.coverage_end, l.row_count, l.quality_score, l.price_cents, l.license_type,
                  l.status, to_char(l.listed_at, 'YYYY-MM-DD HH24:MI:SS') AS listed_at,
                  to_char(l.delisted_at, 'YYYY-MM-DD HH24:MI:SS') AS delisted_at,
                  u.username AS seller_name
           FROM product_listing l JOIN "user" u ON u.id = l.seller_id
           WHERE l.seller_id = #{sellerId} ORDER BY l.id DESC"""
    )
    fun listListingsBySeller(@Param("sellerId") sellerId: Long): List<ProductListing>

    @Select(
        """SELECT l.id, l.product_id, l.seller_id, l.title, l.summary,
                  l.field_list::text AS field_list, l.tags_json::text AS tags_json, l.domain,
                  l.coverage_start, l.coverage_end, l.row_count, l.quality_score, l.price_cents, l.license_type,
                  l.status, to_char(l.listed_at, 'YYYY-MM-DD HH24:MI:SS') AS listed_at,
                  to_char(l.delisted_at, 'YYYY-MM-DD HH24:MI:SS') AS delisted_at,
                  u.username AS seller_name
           FROM product_listing l JOIN "user" u ON u.id = l.seller_id
           WHERE l.product_id = #{productId} AND l.status = 1 ORDER BY l.id DESC LIMIT 1"""
    )
    fun findActiveListingByProduct(@Param("productId") productId: Long): ProductListing?

    @Update(
        """UPDATE product_listing SET status = 0, delisted_at = CURRENT_TIMESTAMP
           WHERE product_id = #{productId} AND status = 1"""
    )
    fun delistByProduct(@Param("productId") productId: Long): Int

    @Select("""SELECT COUNT(*) FROM product_listing WHERE status = 1""")
    fun countActiveListings(): Long

    // ---------- 采购需求 ----------

    @Select(
        """INSERT INTO market_demand (buyer_id, title, raw_desc, structured_json, budget_cents, status)
           VALUES (#{buyerId}, #{title}, #{rawDesc}, #{structuredJson}::jsonb, #{budgetCents}, 1)
           RETURNING id"""
    )
    fun insertDemand(
        @Param("buyerId") buyerId: Long,
        @Param("title") title: String,
        @Param("rawDesc") rawDesc: String,
        @Param("structuredJson") structuredJson: String,
        @Param("budgetCents") budgetCents: Long,
    ): Long

    @Select(
        """SELECT d.id, d.buyer_id, d.title, d.raw_desc, d.structured_json::text AS structured_json,
                  d.budget_cents, d.status, to_char(d.created_at, 'YYYY-MM-DD HH24:MI:SS') AS created_at,
                  u.username AS buyer_name
           FROM market_demand d JOIN "user" u ON u.id = d.buyer_id WHERE d.id = #{id}"""
    )
    fun findDemand(@Param("id") id: Long): MarketDemand?

    @Select(
        """SELECT d.id, d.buyer_id, d.title, d.raw_desc, d.structured_json::text AS structured_json,
                  d.budget_cents, d.status, to_char(d.created_at, 'YYYY-MM-DD HH24:MI:SS') AS created_at,
                  u.username AS buyer_name
           FROM market_demand d JOIN "user" u ON u.id = d.buyer_id
           WHERE d.buyer_id = #{buyerId} ORDER BY d.id DESC"""
    )
    fun listDemandsByBuyer(@Param("buyerId") buyerId: Long): List<MarketDemand>

    @Select(
        """SELECT d.id, d.buyer_id, d.title, d.raw_desc, d.structured_json::text AS structured_json,
                  d.budget_cents, d.status, to_char(d.created_at, 'YYYY-MM-DD HH24:MI:SS') AS created_at,
                  u.username AS buyer_name
           FROM market_demand d JOIN "user" u ON u.id = d.buyer_id
           WHERE d.status = 1 ORDER BY d.id DESC"""
    )
    fun listActiveDemands(): List<MarketDemand>

    @Update("""UPDATE market_demand SET status = #{status}, updated_at = CURRENT_TIMESTAMP WHERE id = #{id}""")
    fun updateDemandStatus(@Param("id") id: Long, @Param("status") status: Int): Int

    // ---------- 匹配 / 推送 ----------

    @Select(
        """INSERT INTO market_match (demand_id, listing_id, score, reasons_json, state)
           VALUES (#{demandId}, #{listingId}, #{score}, #{reasonsJson}::jsonb, 0)
           ON CONFLICT (demand_id, listing_id) DO UPDATE
           SET score = EXCLUDED.score, reasons_json = EXCLUDED.reasons_json
           RETURNING id"""
    )
    fun upsertMatch(
        @Param("demandId") demandId: Long,
        @Param("listingId") listingId: Long,
        @Param("score") score: Int,
        @Param("reasonsJson") reasonsJson: String,
    ): Long

    @Select(
        """SELECT m.id, m.demand_id, m.listing_id, m.score, m.reasons_json::text AS reasons_json,
                  m.state, to_char(m.pushed_at, 'YYYY-MM-DD HH24:MI:SS') AS pushed_at,
                  to_char(m.read_at, 'YYYY-MM-DD HH24:MI:SS') AS read_at, m.note,
                  l.title AS listing_title, l.summary AS listing_summary, l.price_cents AS listing_price_cents,
                  su.username AS seller_name, d.title AS demand_title
           FROM market_match m
           JOIN product_listing l ON l.id = m.listing_id
           JOIN market_demand d ON d.id = m.demand_id
           JOIN "user" su ON su.id = l.seller_id
           WHERE m.demand_id = #{demandId} ORDER BY m.score DESC, m.id DESC"""
    )
    fun listMatchesByDemand(@Param("demandId") demandId: Long): List<MarketMatch>

    @Select(
        """SELECT m.id, m.demand_id, m.listing_id, m.score, m.reasons_json::text AS reasons_json,
                  m.state, to_char(m.pushed_at, 'YYYY-MM-DD HH24:MI:SS') AS pushed_at,
                  to_char(m.read_at, 'YYYY-MM-DD HH24:MI:SS') AS read_at, m.note,
                  l.title AS listing_title, l.summary AS listing_summary, l.price_cents AS listing_price_cents,
                  su.username AS seller_name, d.title AS demand_title
           FROM market_match m
           JOIN product_listing l ON l.id = m.listing_id
           JOIN market_demand d ON d.id = m.demand_id
           JOIN "user" su ON su.id = l.seller_id
           WHERE m.id = #{id}"""
    )
    fun findMatch(@Param("id") id: Long): MarketMatch?

    @Select(
        """SELECT m.id, m.demand_id, m.listing_id, m.score, m.reasons_json::text AS reasons_json,
                  m.state, to_char(m.pushed_at, 'YYYY-MM-DD HH24:MI:SS') AS pushed_at,
                  to_char(m.read_at, 'YYYY-MM-DD HH24:MI:SS') AS read_at, m.note,
                  l.title AS listing_title, l.summary AS listing_summary, l.price_cents AS listing_price_cents,
                  su.username AS seller_name, d.title AS demand_title
           FROM market_match m
           JOIN product_listing l ON l.id = m.listing_id
           JOIN market_demand d ON d.id = m.demand_id
           JOIN "user" su ON su.id = l.seller_id
           WHERE d.buyer_id = #{buyerId}
           ORDER BY (CASE WHEN m.state = 0 THEN 0 ELSE 1 END), m.score DESC, m.id DESC"""
    )
    fun listMatchesForBuyer(@Param("buyerId") buyerId: Long): List<MarketMatch>

    @Update(
        """UPDATE market_match SET state = #{state},
                  read_at = CASE WHEN #{state} >= 1 THEN CURRENT_TIMESTAMP ELSE read_at END,
                  note = #{note,jdbcType=VARCHAR}
           WHERE id = #{id}"""
    )
    fun updateMatchState(@Param("id") id: Long, @Param("state") state: Int, @Param("note") note: String?): Int

    @Update(
        """UPDATE market_match SET state = 3, note = '货源已下架'
           WHERE listing_id IN (SELECT id FROM product_listing WHERE product_id = #{productId}) AND state IN (0, 1)"""
    )
    fun invalidateMatchesOfProduct(@Param("productId") productId: Long): Int

    @Select("""SELECT COUNT(*) FROM market_match WHERE state = 0""")
    fun countAllUnread(): Long

    // ---------- 交付 ----------

    @Select(
        """INSERT INTO delivery_request (match_id, buyer_id, seller_id, status, note)
           VALUES (#{matchId}, #{buyerId}, #{sellerId}, 0, #{note,jdbcType=VARCHAR})
           RETURNING id"""
    )
    fun insertDelivery(
        @Param("matchId") matchId: Long,
        @Param("buyerId") buyerId: Long,
        @Param("sellerId") sellerId: Long,
        @Param("note") note: String?,
    ): Long

    @Select(
        """SELECT dr.id, dr.match_id, dr.buyer_id, dr.seller_id, dr.status, dr.note,
                  to_char(dr.created_at, 'YYYY-MM-DD HH24:MI:SS') AS created_at,
                  to_char(dr.updated_at, 'YYYY-MM-DD HH24:MI:SS') AS updated_at,
                  l.title AS listing_title, bu.username AS buyer_name, su.username AS seller_name
           FROM delivery_request dr
           JOIN market_match m ON m.id = dr.match_id
           JOIN product_listing l ON l.id = m.listing_id
           JOIN "user" bu ON bu.id = dr.buyer_id
           JOIN "user" su ON su.id = dr.seller_id
           WHERE dr.id = #{id}"""
    )
    fun findDelivery(@Param("id") id: Long): DeliveryRequest?

    @Select(
        """SELECT dr.id, dr.match_id, dr.buyer_id, dr.seller_id, dr.status, dr.note,
                  to_char(dr.created_at, 'YYYY-MM-DD HH24:MI:SS') AS created_at,
                  to_char(dr.updated_at, 'YYYY-MM-DD HH24:MI:SS') AS updated_at,
                  l.title AS listing_title, bu.username AS buyer_name, su.username AS seller_name
           FROM delivery_request dr
           JOIN market_match m ON m.id = dr.match_id
           JOIN product_listing l ON l.id = m.listing_id
           JOIN "user" bu ON bu.id = dr.buyer_id
           JOIN "user" su ON su.id = dr.seller_id
           WHERE dr.buyer_id = #{buyerId} ORDER BY dr.id DESC"""
    )
    fun listDeliveriesForBuyer(@Param("buyerId") buyerId: Long): List<DeliveryRequest>

    @Select(
        """SELECT dr.id, dr.match_id, dr.buyer_id, dr.seller_id, dr.status, dr.note,
                  to_char(dr.created_at, 'YYYY-MM-DD HH24:MI:SS') AS created_at,
                  to_char(dr.updated_at, 'YYYY-MM-DD HH24:MI:SS') AS updated_at,
                  l.title AS listing_title, bu.username AS buyer_name, su.username AS seller_name
           FROM delivery_request dr
           JOIN market_match m ON m.id = dr.match_id
           JOIN product_listing l ON l.id = m.listing_id
           JOIN "user" bu ON bu.id = dr.buyer_id
           JOIN "user" su ON su.id = dr.seller_id
           WHERE dr.seller_id = #{sellerId} ORDER BY dr.id DESC"""
    )
    fun listDeliveriesForSeller(@Param("sellerId") sellerId: Long): List<DeliveryRequest>

    @Select(
        """SELECT dr.id, dr.match_id, dr.buyer_id, dr.seller_id, dr.status, dr.note,
                  to_char(dr.created_at, 'YYYY-MM-DD HH24:MI:SS') AS created_at,
                  to_char(dr.updated_at, 'YYYY-MM-DD HH24:MI:SS') AS updated_at,
                  l.title AS listing_title, bu.username AS buyer_name, su.username AS seller_name
           FROM delivery_request dr
           JOIN market_match m ON m.id = dr.match_id
           JOIN product_listing l ON l.id = m.listing_id
           JOIN "user" bu ON bu.id = dr.buyer_id
           JOIN "user" su ON su.id = dr.seller_id
           ORDER BY dr.id DESC"""
    )
    fun listAllDeliveries(): List<DeliveryRequest>

    @Update("""UPDATE delivery_request SET status = #{status}, updated_at = CURRENT_TIMESTAMP WHERE id = #{id}""")
    fun updateDeliveryStatus(@Param("id") id: Long, @Param("status") status: Int): Int

    @Select("""SELECT COUNT(*) FROM delivery_request WHERE status = 0""")
    fun countPendingDeliveries(): Long
}