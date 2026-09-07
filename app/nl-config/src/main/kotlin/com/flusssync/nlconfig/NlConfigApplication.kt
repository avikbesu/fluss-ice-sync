package com.flusssync.nlconfig

import com.flusssync.nlconfig.config.ConfigStoreProperties
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan(basePackageClasses = [ConfigStoreProperties::class])
class NlConfigApplication

fun main(args: Array<String>) {
    runApplication<NlConfigApplication>(*args)
}
