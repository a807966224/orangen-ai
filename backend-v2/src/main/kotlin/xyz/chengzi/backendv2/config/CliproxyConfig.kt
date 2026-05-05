package xyz.chengzi.backendv2.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Configuration

@Configuration
@ConfigurationProperties(prefix = "app.cliproxy")
class CliproxyConfig {
    lateinit var apiUrl: String
    lateinit var apiKey: String
}
