package com.flino.nlapi

import com.flino.nlapi.config.AskProperties
import com.flino.nlapi.config.ClaudeProperties
import com.flino.nlapi.config.TrinoProperties
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan(basePackageClasses = [TrinoProperties::class, ClaudeProperties::class, AskProperties::class])
class NlApiApplication

fun main(args: Array<String>) {
    runApplication<NlApiApplication>(*args)
}
