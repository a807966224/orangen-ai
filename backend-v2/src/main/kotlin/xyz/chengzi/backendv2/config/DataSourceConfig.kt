package xyz.chengzi.backendv2.config

import com.zaxxer.hikari.HikariDataSource
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import javax.sql.DataSource

/**
 * DataSource configuration - provides appropriate DataSource based on storage type.
 * For FILE storage, provides an in-memory H2 datasource for Hibernate to use.
 * For POSTGRES storage, provides a real PostgreSQL datasource.
 */
@Configuration
class DataSourceConfig {

    @Value("\${app.storage-type:FILE}")
    private lateinit var storageType: String

    @Bean
    fun dataSource(): DataSource {
        return if ("POSTGRES" == storageType.uppercase()) {
            // Real PostgreSQL datasource
            val ds = HikariDataSource()
            ds.jdbcUrl = "jdbc:postgresql://localhost:5432/orange_ai"
            ds.username = "postgres"
            ds.password = "postgres"
            ds.driverClassName = "org.postgresql.Driver"
            ds
        } else {
            // In-memory H2 datasource for Hibernate (not actually used for storage in FILE mode)
            val ds = HikariDataSource()
            ds.jdbcUrl = "jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1"
            ds.username = "sa"
            ds.password = ""
            ds.driverClassName = "org.h2.Driver"
            ds
        }
    }
}