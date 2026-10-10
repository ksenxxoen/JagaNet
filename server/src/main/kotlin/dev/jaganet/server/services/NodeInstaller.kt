package dev.jaganet.server.services

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.UIKeyboardInteractive
import com.jcraft.jsch.UserInfo
import dev.jaganet.api.NodeSshReq
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlin.concurrent.thread
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/** Why a remote run failed before the script could finish: an English message (translated on screen) with arguments. */
class RemoteFailure(val reason: String, val args: Map<String, String> = emptyMap(), cause: Throwable? = null) : Exception(reason, cause)

/** Runs a script as root on another machine. */
fun interface RemoteRunner {
    /**
     * Runs [script] with [env] exported (values go over stdin, not the command line) and
     * streams its output line by line to [out]. Returns the exit code.
     */
    suspend fun run(target: NodeSshReq, script: String, env: Map<String, String>, out: (String) -> Unit): Int
}

/** The command the remote login shell runs: reads [keys] and then the script from stdin. */
internal fun remoteCommand(keys: Collection<String>, root: Boolean): String {
    val reads = keys.joinToString(" ") { "IFS= read -r $it;" }
    // The script goes to a file first: the commands inside it (apt-get…) must not eat stdin.
    val body = "$reads export ${keys.joinToString(" ")}; f=\$(mktemp); cat > \"\$f\"; bash \"\$f\" </dev/null 2>&1; r=\$?; rm -f \"\$f\"; exit \$r"
    return (if (root) "" else "sudo -n ") + "bash -c '$body'"
}

/** SSH with a password or a private key (JSch). The server's host key is accepted on first use and logged. */
class SshRunner(private val timeout: Duration = 45.minutes) : RemoteRunner {
    override suspend fun run(target: NodeSshReq, script: String, env: Map<String, String>, out: (String) -> Unit): Int = withContext(Dispatchers.IO) {
        val jsch = JSch()
        target.privateKey?.takeIf { it.isNotBlank() }?.let { key ->
            try {
                jsch.addIdentity("node", (key.trim() + "\n").toByteArray(), null, null)
            } catch (e: JSchException) {
                throw RemoteFailure("The private key can't be read. Use an OpenSSH key without a passphrase", cause = e)
            }
        }
        val session = jsch.getSession(target.user, target.host, target.port)
        val password = target.password?.takeIf { it.isNotEmpty() }
        if (password != null) session.setPassword(password)
        session.userInfo = object : UserInfo, UIKeyboardInteractive {
            override fun getPassphrase(): String? = null
            override fun getPassword(): String? = password
            override fun promptPassword(message: String?) = password != null
            override fun promptPassphrase(message: String?) = false
            override fun promptYesNo(message: String?) = true
            override fun showMessage(message: String?) {}
            override fun promptKeyboardInteractive(destination: String?, name: String?, instruction: String?, prompt: Array<out String>?, echo: BooleanArray?) =
                if (password != null && prompt?.size == 1) arrayOf(password) else null
        }
        session.setConfig("StrictHostKeyChecking", "no")
        session.setConfig("PreferredAuthentications", "publickey,keyboard-interactive,password")
        session.serverAliveInterval = 15_000
        session.serverAliveCountMax = 8
        try {
            session.connect(20_000)
        } catch (e: JSchException) {
            val msg = e.message.orEmpty()
            throw if (msg.contains("Auth fail", true) || msg.contains("Auth cancel", true) || msg.contains("auth", true) && msg.contains("fail", true))
                RemoteFailure("The server didn't accept the user name, password or key", cause = e)
            else RemoteFailure("Can't connect to {host}: {reason}", mapOf("host" to "${target.host}:${target.port}", "reason" to msg.substringAfterLast(": ").ifBlank { msg }), e)
        }
        var timedOut = false
        try {
            out("Connected to ${target.host}, host key ${session.hostKey.getFingerPrint(jsch)}")
            val ch = session.openChannel("exec") as ChannelExec
            ch.setCommand(remoteCommand(env.keys, target.user == "root"))
            val stdin = env.values.joinToString("") { it.replace("\n", "") + "\n" } + script
            ch.setInputStream(ByteArrayInputStream(stdin.toByteArray()))
            val stdout = ch.inputStream
            val stderr = ch.extInputStream
            ch.connect(20_000)
            coroutineScope {
                val watchdog = launch { delay(timeout); timedOut = true; session.disconnect() }
                val errReader = thread(isDaemon = true) { pump(stderr, out) }
                pump(stdout, out)
                errReader.join(5_000)
                while (!ch.isClosed && session.isConnected) Thread.sleep(100)
                watchdog.cancel()
            }
            if (timedOut) throw RemoteFailure("The install took longer than {n} minutes", mapOf("n" to timeout.inWholeMinutes.toString()))
            if (!ch.isClosed) throw RemoteFailure("The connection to the server was lost")
            ch.exitStatus
        } finally {
            session.disconnect()
        }
    }

    private fun pump(stream: InputStream, out: (String) -> Unit) = runCatching {
        stream.bufferedReader().forEachLine { line -> line.split('\r').lastOrNull { it.isNotBlank() }?.let(out) }
    }
}
