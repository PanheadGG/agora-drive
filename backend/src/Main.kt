package com.pgigi.agentclouddisk

import org.mybatis.spring.annotation.MapperScan
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
@MapperScan("com.pgigi.agentclouddisk.mapper")
class Application

fun main(args: Array<String>) {
    runApplication<Application>(*args)
}
