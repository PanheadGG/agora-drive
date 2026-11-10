package com.pgigi.agentclouddisk.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** 扫描在独立线程池里跑，接口立即返回，前端靠进度接口看过程 */
@Configuration
class ScanExecutorConfig {

    @Bean(destroyMethod = "shutdown")
    fun scanExecutor(): ExecutorService {
        val counter = AtomicInteger(0)
        return Executors.newFixedThreadPool(2) { runnable ->
            Thread(runnable, "agent-scan-" + counter.incrementAndGet()).apply { isDaemon = true }
        }
    }
}
