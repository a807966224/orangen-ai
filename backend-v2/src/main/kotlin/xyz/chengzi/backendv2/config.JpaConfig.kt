package xyz.chengzi.backendv2.config

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Configuration
import org.springframework.data.jpa.repository.config.EnableJpaRepositories

/**
 * JPA configuration - only enabled when storage-type=POSTGRES
 */
@Configuration
@ConditionalOnProperty(name = ["app.storage-type"], havingValue = "POSTGRES", matchIfMissing = false)
@EnableJpaRepositories(basePackages = ["xyz.chengzi.backendv2.repository"])
class JpaConfig