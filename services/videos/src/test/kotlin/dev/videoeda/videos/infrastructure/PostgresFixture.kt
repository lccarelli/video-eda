package dev.videoeda.videos.infrastructure

import org.flywaydb.core.Flyway
import org.postgresql.ds.PGSimpleDataSource
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import javax.sql.DataSource

object PostgresFixture {
    private val container = PostgreSQLContainer(DockerImageName.parse("postgres:18.6")).also { it.start() }

    val dataSource: DataSource = PGSimpleDataSource().apply {
        setURL(container.jdbcUrl)
        user = container.username
        password = container.password
    }

    val jdbcUrl: String get() = container.jdbcUrl
    val username: String get() = container.username
    val password: String get() = container.password

    val transactionManager = DataSourceTransactionManager(dataSource)
    val jdbc: JdbcClient = JdbcClient.create(dataSource)

    init {
        Flyway.configure().dataSource(dataSource).load().migrate()
    }

    fun resetTables() {
        jdbc.sql("TRUNCATE video, outbox RESTART IDENTITY").update()
    }
}
