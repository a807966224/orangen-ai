package xyz.chengzi.backendv2

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.data.jpa.repository.config.EnableJpaRepositories

@SpringBootApplication
@EnableJpaRepositories(basePackages = ["xyz.chengzi.backendv2.repository"])
class BackendV2Application

fun main(args: Array<String>) {
    runApplication<BackendV2Application>(*args)
}
