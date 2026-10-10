package dev.jaganet.server

import dev.jaganet.api.NodeSshReq
import dev.jaganet.server.services.RemoteFailure
import dev.jaganet.server.services.SshRunner
import kotlinx.coroutines.runBlocking
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.server.shell.ProcessShellCommandFactory
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The SSH runner against a real SSH server on localhost that runs commands with the local shell. */
class SshRunnerTest {
    private fun <T> withServer(block: (port: Int) -> T): T {
        val sshd = SshServer.setUpDefaultServer().apply {
            host = "127.0.0.1"; port = 0
            keyPairProvider = SimpleGeneratorHostKeyProvider()
            setPasswordAuthenticator { user, password, _ -> user == "root" && password == "secret" }
            commandFactory = ProcessShellCommandFactory.INSTANCE
        }
        sshd.start()
        try { return block(sshd.port) } finally { sshd.stop(true) }
    }

    @Test fun `runs the script with the values from stdin and streams its output`() = withServer { port ->
        val lines = Collections.synchronizedList(mutableListOf<String>())
        val script = "echo \"url=\$JAGANET_URL token=\$NODE_TOKEN\"\necho to-stderr >&2\nread x || echo stdin-closed\nexit 3\n"
        val code = runBlocking {
            SshRunner().run(NodeSshReq("127.0.0.1", port, "root", "secret"), script, linkedMapOf("JAGANET_URL" to "https://m.example", "NODE_TOKEN" to "tok123"), lines::add)
        }
        assertEquals(3, code)
        assertTrue(lines.first().startsWith("Connected to 127.0.0.1, host key "), lines.toString())
        assertTrue("url=https://m.example token=tok123" in lines, lines.toString())
        assertTrue("to-stderr" in lines && "stdin-closed" in lines, lines.toString())
    }

    @Test fun `a wrong password and a closed port are explained`() = withServer { port ->
        val auth = assertFailsWith<RemoteFailure> {
            runBlocking { SshRunner().run(NodeSshReq("127.0.0.1", port, "root", "nope"), "true", emptyMap()) {} }
        }
        assertEquals("The server didn't accept the user name, password or key", auth.reason)
        val closed = assertFailsWith<RemoteFailure> {
            runBlocking { SshRunner().run(NodeSshReq("127.0.0.1", 1, "root", "secret"), "true", emptyMap()) {} }
        }
        assertEquals("Can't connect to {host}: {reason}", closed.reason)
    }
}
