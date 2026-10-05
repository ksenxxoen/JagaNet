package dev.jaganet.server.db

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import java.io.File

/**
 * A real PostgreSQL server started from binaries shipped as a Maven dependency.
 * For simulation and tests only; production uses DATABASE_URL.
 */
object Embedded {
    fun start(dataDir: File? = null): EmbeddedPostgres = EmbeddedPostgres.builder().apply {
        if (dataDir != null) {
            dataDir.mkdirs()
            setDataDirectory(dataDir)
            setCleanDataDirectory(false)
        }
    }.start()
}
