package dev.jaganet.server.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.flywaydb.core.Flyway
import org.postgresql.util.PGobject
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import javax.sql.DataSource

/**
 * Thin JDBC layer: plain SQL, positional `?` params. Kept deliberately small so
 * every query is visible in the service that runs it.
 */
class Db(private val ds: DataSource) {
    /** Autocommit: each statement on its own. */
    suspend fun <T> run(block: suspend (Sql) -> T): T = withContext(Dispatchers.IO) {
        ds.connection.use { block(Sql(it)) }
    }

    /** One transaction: commit on success, roll back on any exception. */
    suspend fun <T> tx(block: suspend (Sql) -> T): T = withContext(Dispatchers.IO) {
        ds.connection.use { c ->
            c.autoCommit = false
            try {
                block(Sql(c)).also { c.commit() }
            } catch (e: Throwable) {
                c.rollback()
                throw e
            } finally {
                c.autoCommit = true
            }
        }
    }

    fun migrate() {
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate()
    }

    fun close() = (ds as? AutoCloseable)?.close()

    companion object {
        fun pooled(url: String, user: String? = null, password: String? = null): Db =
            Db(HikariDataSource(HikariConfig().apply {
                jdbcUrl = url
                user?.let { username = it }
                password?.let { this.password = it }
                maximumPoolSize = 10
            }))
    }
}

/** Marks a String parameter as jsonb. */
@JvmInline value class Jsonb(val json: String)

class Sql(val c: Connection) {
    fun query(sql: String, vararg params: Any?): List<Row> =
        c.prepareStatement(sql).use { st ->
            bind(st, params)
            if (!st.execute()) return emptyList()
            st.resultSet.use { rs -> buildList { while (rs.next()) add(Row.of(rs)) } }
        }

    fun one(sql: String, vararg params: Any?): Row? = query(sql, *params).firstOrNull()

    fun exec(sql: String, vararg params: Any?): Int = c.prepareStatement(sql).use { st ->
        bind(st, params)
        st.executeUpdate()
    }

    private fun bind(st: java.sql.PreparedStatement, params: Array<out Any?>) {
        params.forEachIndexed { i, p ->
            val idx = i + 1
            when (p) {
                null -> st.setObject(idx, null)
                is Instant -> st.setTimestamp(idx, Timestamp.from(p))
                is LocalDate -> st.setObject(idx, p)
                is Jsonb -> st.setObject(idx, PGobject().apply { type = "jsonb"; value = p.json })
                is JsonObject -> st.setObject(idx, PGobject().apply { type = "jsonb"; value = p.toString() })
                else -> st.setObject(idx, p)
            }
        }
    }
}

class Row(private val m: Map<String, Any?>) {
    operator fun get(k: String): Any? = m[k]
    fun str(k: String) = m[k] as String
    fun strOrNull(k: String) = m[k]?.toString()
    fun long(k: String) = (m[k] as Number).toLong()
    fun longOrNull(k: String) = (m[k] as Number?)?.toLong()
    fun int(k: String) = (m[k] as Number).toInt()
    fun bool(k: String) = m[k] as Boolean
    fun instant(k: String) = (m[k] as Timestamp).toInstant()
    fun instantOrNull(k: String) = (m[k] as Timestamp?)?.toInstant()
    fun jsonOrNull(k: String): JsonObject? = if (m[k] == null) null else json(k)
    fun json(k: String): JsonObject = Json.parseToJsonElement((m[k] as PGobject).value ?: "{}") as JsonObject
    fun strings(k: String): List<String> = ((m[k] as java.sql.Array).array as Array<*>).map { it.toString() }

    companion object {
        fun of(rs: ResultSet): Row {
            val md = rs.metaData
            return Row((1..md.columnCount).associate { i ->
                md.getColumnLabel(i) to when (val v = rs.getObject(i)) {
                    is java.util.UUID -> v.toString()
                    is java.sql.Date -> v.toLocalDate()
                    else -> v
                }
            })
        }
    }
}
