package dev.jaganet.server

import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The node agent (resources/node/agent.py) is Python: its unit tests, with Xray's API and the
 * WireGuard tool mocked, live in src/test/python and run here, so `:server:test` covers them.
 * Skipped where there is no python3.
 */
class AgentScriptTest {
    @Test fun `agent py unit tests pass`() {
        val python = listOf("python3", "python").firstOrNull { runCatching { ProcessBuilder(it, "--version").start().waitFor() == 0 }.getOrDefault(false) }
        if (python == null) {
            println("python3 not found: agent.py tests skipped")
            return
        }
        val dir = File("src/test/python")
        assertTrue(File(dir, "test_agent.py").isFile, "src/test/python/test_agent.py is missing (working dir ${File(".").absolutePath})")
        val p = ProcessBuilder(python, "-B", "-m", "unittest", "discover", "-s", dir.path, "-v")
            .redirectErrorStream(true).also { it.environment()["PYTHONDONTWRITEBYTECODE"] = "1" }.start()
        val out = p.inputStream.bufferedReader().readText()
        assertTrue(p.waitFor(120, TimeUnit.SECONDS), "agent tests timed out")
        assertEquals(0, p.exitValue(), out)
    }

    @Test fun `agent and installer parse`() {
        val python = listOf("python3", "python").firstOrNull { runCatching { ProcessBuilder(it, "--version").start().waitFor() == 0 }.getOrDefault(false) }
        if (python != null) {
            val p = ProcessBuilder(python, "-B", "-c", "import ast,sys; ast.parse(open(sys.argv[1]).read())", "src/main/resources/node/agent.py").redirectErrorStream(true).start()
            assertEquals(0, p.waitFor(), p.inputStream.bufferedReader().readText())
        }
        val bash = runCatching { ProcessBuilder("bash", "-n", "src/main/resources/node/install.sh").redirectErrorStream(true).start() }.getOrNull() ?: return
        assertEquals(0, bash.waitFor(), bash.inputStream.bufferedReader().readText())
    }
}
