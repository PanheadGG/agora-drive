package com.pgigi.agentclouddisk.common

/**
 * 角色已收敛为「用户 / 管理员」：
 * 数据市场里每个用户既是潜在卖方也是潜在买方，不再有固定"企业需求方"身份。
 */
object Roles {
    const val USER = 0
    const val ADMIN = 1

    /** 历史遗留：旧数据的 enterprise 角色会迁移为 USER（见 V8 迁移） */
    @Deprecated("买卖双向后不再使用独立企业角色")
    const val ENTERPRISE = 2
}
