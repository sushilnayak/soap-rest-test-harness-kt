package com.nayak.app

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan
//@EnableScheduling
class App

fun main(args: Array<String>) {
    runApplication<App>(*args)
}
