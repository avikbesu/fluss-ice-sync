package com.flusssync.nlapi

import com.flusssync.nlapi.config.AskProperties
import com.flusssync.nlapi.config.ClaudeProperties
import com.flusssync.nlapi.config.TrinoProperties
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan(basePackageClasses = [TrinoProperties::class, ClaudeProperties::class, AskProperties::class])
class NlApiApplication

fun main(args: Array<String>) {
    runApplication<NlApiApplication>(*args)
}
