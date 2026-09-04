/** Создаёт datasource и применяет миграции БД до обработки запросов. */
package speakingcharacter.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import speakingcharacter.config.AppConfig

/** Владеет небольшим пулом JDBC-соединений приложения. */
class DatabaseFactory(private val config: AppConfig) {
    /** Создаёт datasource и обновляет схему до последней версии Flyway. */
    fun connectAndMigrate(): HikariDataSource {
        val dataSource = HikariDataSource(HikariConfig().apply {
            jdbcUrl = config.databaseUrl
            username = config.databaseUser
            password = config.databasePassword
            driverClassName = "org.postgresql.Driver"
            maximumPoolSize = 5
        })
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate()
        return dataSource
    }
}
