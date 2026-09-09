package com.flino.config

import com.flino.config.config.ConfigStoreProperties
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan(basePackageClasses = [ConfigStoreProperties::class])
class ConfigApplication

fun main(args: Array<String>) {
    runApplication<ConfigApplication>(*args)
}
