package com.example.nova.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.flywaydb.core.Flyway
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/** Schlanker JDBC-Zugriff: Transaktionen auf dem IO-Dispatcher, Parameter per Position. */
class Database(url: String, user: String, password: String) : AutoCloseable {
    private val dataSource = HikariDataSource(HikariConfig().apply {
        jdbcUrl = url
        username = user
        this.password = password
        maximumPoolSize = 10
        isAutoCommit = false
    })

    fun migrate() {
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate()
    }

    suspend fun <T> tx(block: Connection.() -> T): T = withContext(Dispatchers.IO) { txBlocking(block) }

    fun <T> txBlocking(block: Connection.() -> T): T = dataSource.connection.use { conn ->
        try {
            val result = conn.block()
            conn.commit()
            result
        } catch (e: Throwable) {
            conn.rollback()
            throw e
        }
    }

    override fun close() = dataSource.close()
}

private fun PreparedStatement.bind(params: Array<out Any?>) {
    params.forEachIndexed { i, p ->
        when (p) {
            is Instant -> setObject(i + 1, OffsetDateTime.ofInstant(p, ZoneOffset.UTC))
            else -> setObject(i + 1, p)
        }
    }
}

fun Connection.update(sql: String, vararg params: Any?): Int =
    prepareStatement(sql).use { it.bind(params); it.executeUpdate() }

fun <T> Connection.query(sql: String, vararg params: Any?, map: (ResultSet) -> T): List<T> =
    prepareStatement(sql).use { st ->
        st.bind(params)
        st.executeQuery().use { rs ->
            buildList { while (rs.next()) add(map(rs)) }
        }
    }

fun <T> Connection.queryOne(sql: String, vararg params: Any?, map: (ResultSet) -> T): T? =
    query(sql, *params, map = map).firstOrNull()

fun ResultSet.uuid(col: String): UUID = getObject(col, UUID::class.java)
fun ResultSet.uuidOrNull(col: String): UUID? = getObject(col, UUID::class.java)
fun ResultSet.instant(col: String): Instant = getObject(col, OffsetDateTime::class.java).toInstant()
fun ResultSet.instantOrNull(col: String): Instant? = getObject(col, OffsetDateTime::class.java)?.toInstant()
