package dev.jaganet.server.services

import dev.jaganet.api.AccessKey
import dev.jaganet.api.AmneziaWG
import dev.jaganet.api.ErrorCode
import dev.jaganet.api.KeyTunnel
import dev.jaganet.api.Protocols
import dev.jaganet.api.TunnelConfig
import dev.jaganet.api.TunnelProvisionReq
import dev.jaganet.api.Vless
import dev.jaganet.api.WireGuard
import dev.jaganet.server.AppError
import dev.jaganet.server.Ctx
import dev.jaganet.server.db.Row
import io.nayuki.qrcodegen.QrCode
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.interfaces.XECPrivateKey
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.imageio.ImageIO

/** What a key link opens: the config file and where it points. */
data class KeyConfig(val name: String, val location: String, val protocol: String, val text: String)

/**
 * VPN keys for people who bought on the website or in Telegram: a config made on the
 * server that any AmneziaWG / WireGuard app imports (file, link or QR code).
 * Each key is a device of the user, so plan device limits and data quotas apply as usual.
 * The Boop VPN app does not use these: it makes its own key on the phone.
 */
class Keys(private val ctx: Ctx, private val tunnels: Tunnels) {
    private val b64 = Base64.getEncoder()

    suspend fun list(userId: String): List<AccessKey> = ctx.db.run { sql ->
        sql.query(
            """SELECT k.*, d.name, t.last_seen_at, v.country_code FROM access_keys k JOIN devices d ON d.id = k.device_id
                 LEFT JOIN tunnels t ON t.device_id = k.device_id LEFT JOIN servers v ON v.id = t.server_id
                WHERE k.user_id=?::uuid AND d.removed_at IS NULL ORDER BY k.created_at""",
            userId,
        ).map { it.toAccessKey() }
    }

    /** The user's first key, made if they have none (after a website / Telegram purchase). */
    suspend fun ensure(userId: String): AccessKey = list(userId).firstOrNull() ?: create(userId)

    suspend fun create(userId: String): AccessKey {
        val protocol = tunnels.defaultProtocol() ?: throw AppError(503, ErrorCode.SERVER_FULL, "No VPN server is available")
        if (protocol != Protocols.AMNEZIAWG && protocol != Protocols.WIREGUARD) {
            throw AppError(400, ErrorCode.UNSUPPORTED_PROTOCOL, "Keys for other apps need AmneziaWG or WireGuard")
        }
        val (privateKey, publicKey) = newKeyPair()
        val deviceId = ctx.db.run { sql ->
            val n = sql.one("SELECT count(*)::int AS n FROM access_keys WHERE user_id=?::uuid", userId)!!.int("n")
            sql.one(
                "INSERT INTO devices (user_id, name, platform) VALUES (?::uuid,?,'other') RETURNING id",
                userId, if (n == 0) "VPN key" else "VPN key ${n + 1}",
            )!!.str("id")
        }
        val config = try {
            tunnels.provisionFor(userId, deviceId, TunnelProvisionReq(protocol, clientParams = Protocols.encode(WireGuard.ClientParams(publicKey))))
        } catch (e: Throwable) {
            ctx.db.run { it.exec("DELETE FROM devices WHERE id=?::uuid", deviceId) }
            throw e
        }
        return ctx.db.run { sql ->
            sql.exec(
                "INSERT INTO access_keys (device_id, user_id, link_token, private_key_enc, config) VALUES (?::uuid,?::uuid,?,?,?)",
                deviceId, userId, Crypto.newToken(), encrypt(privateKey), Protocols.encode(config),
            )
            sql.one("SELECT k.*, d.name FROM access_keys k JOIN devices d ON d.id = k.device_id WHERE k.device_id=?::uuid", deviceId)!!.toAccessKey()
        }
    }

    suspend fun remove(userId: String, id: String) {
        ctx.db.run { it.one("SELECT 1 FROM access_keys WHERE device_id=?::uuid AND user_id=?::uuid", id, userId) }
            ?: throw AppError(404, ErrorCode.NOT_FOUND, "Key not found")
        tunnels.removeFor(userId, id)
    }

    /** Opened by a key link. Null if the token is unknown or the key was deleted. */
    suspend fun byToken(token: String): KeyConfig? = configWhere("k.link_token=?", token)

    suspend fun config(userId: String, id: String): KeyConfig? = configWhere("k.device_id=?::uuid AND k.user_id=?::uuid", id, userId)

    /** A key's tunnel as it is now, with its private key: for the owner's own Boop desktop app. */
    suspend fun tunnel(userId: String, id: String): KeyTunnel {
        if (!Regex("^[0-9a-fA-F-]{36}$").matches(id)) throw AppError(404, ErrorCode.NOT_FOUND, "Key not found")
        val (r, cfg) = current("k.device_id=?::uuid AND k.user_id=?::uuid", id, userId) ?: throw AppError(404, ErrorCode.NOT_FOUND, "Key not found")
        val version = MessageDigest.getInstance("SHA-256").digest(Protocols.encode(cfg).toString().toByteArray())
            .take(12).joinToString("") { "%02x".format(it) }
        return KeyTunnel(cfg, decrypt(r.str("private_key_enc")), version, cfg.location.substringAfterLast(", ").trim().ifEmpty { null })
    }

    private suspend fun configWhere(cond: String, vararg args: Any?): KeyConfig? =
        current(cond, *args)?.let { (r, cfg) -> KeyConfig(r.str("name"), cfg.location, cfg.protocol, configText(decrypt(r.str("private_key_enc")), cfg)) }

    private suspend fun current(cond: String, vararg args: Any?): Pair<Row, TunnelConfig>? = ctx.db.run { sql ->
        val r = sql.one("SELECT k.*, d.name FROM access_keys k JOIN devices d ON d.id = k.device_id WHERE $cond AND d.removed_at IS NULL", *args)
            ?: return@run null
        // The node's current settings (port, obfuscation profile), not the ones from the day
        // the key was made: after a node changes them, importing the key again is enough.
        val stored = Protocols.decode<TunnelConfig>(r.json("config"))
        val t = sql.one("SELECT server_id, protocol, address, peer_key FROM tunnels WHERE device_id=?::uuid", r.str("device_id"))
        val server = t?.let { sql.one("SELECT * FROM servers WHERE id=?", it.str("server_id")) }
        val current = if (t != null && server != null && t.str("protocol") == stored.protocol)
            ctx.drivers[stored.protocol]?.currentParams(server.node(stored.protocol), t.str("peer_key"))
        else null
        // The device may have moved to another node: its address and location there.
        r to (current?.let {
            stored.copy(
                params = it, mtu = server!!.int("mtu"), dns = server.strings("dns"), serverId = server.str("id"),
                address = "${t!!.str("address")}/32", location = "${server.str("city")}, ${server.str("country_code").trim()}",
            ).withEndpointIp(publicIp(server!!, stored.protocol))
        } ?: stored)
    }

    private fun Row.toAccessKey(): AccessKey {
        val cfg = Protocols.decode<TunnelConfig>(json("config"))
        val base = "${ctx.cfg.publicUrl.trimEnd('/')}/k/${str("link_token")}"
        return AccessKey(
            str("device_id"), str("name"), cfg.protocol, cfg.location, base, "$base/$CONFIG_FILE", "$base/qr.png", instant("created_at").toString(),
            lastSeenAt = instantOrNull("last_seen_at")?.toString(), countryCode = strOrNull("country_code")?.trim(),
        )
    }

    /* ---- crypto: the private key is stored encrypted with a key derived from AUTH_SECRET ---- */

    private val aesKey by lazy { SecretKeySpec(MessageDigest.getInstance("SHA-256").digest("access-keys:${ctx.cfg.authSecret}".toByteArray()), "AES") }

    private fun encrypt(plain: String): String {
        val iv = ByteArray(12).also(random::nextBytes)
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, aesKey, GCMParameterSpec(128, iv)) }
        return b64.encodeToString(iv + c.doFinal(plain.toByteArray()))
    }

    private fun decrypt(enc: String): String {
        val all = Base64.getDecoder().decode(enc)
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, aesKey, GCMParameterSpec(128, all, 0, 12)) }
        return String(c.doFinal(all, 12, all.size - 12))
    }

    companion object {
        /** Becomes the tunnel's name in AmneziaWG / WireGuard (max 15 characters). */
        const val CONFIG_FILE = "BoopVPN.conf"

        /**
         * The key as AmneziaVPN's "vpn://" text, pasted in the app (Add server, Insert key):
         * base64url of the config compressed the way Qt's qCompress does (4-byte big-endian
         * length, then a zlib stream). The app unpacks it and reads it like an imported .conf.
         */
        fun vpnLink(configText: String): String {
            val raw = configText.toByteArray()
            val deflater = java.util.zip.Deflater(9)
            deflater.setInput(raw); deflater.finish()
            val out = ByteArrayOutputStream()
            out.write(java.nio.ByteBuffer.allocate(4).putInt(raw.size).array())
            val buf = ByteArray(4096)
            while (!deflater.finished()) out.write(buf, 0, deflater.deflate(buf))
            deflater.end()
            return "vpn://" + Base64.getUrlEncoder().withoutPadding().encodeToString(out.toByteArray())
        }
        private val random = SecureRandom()

        /** A WireGuard key pair (Curve25519), base64 like `wg genkey` / `wg pubkey`. */
        fun newKeyPair(rng: SecureRandom = random): Pair<String, String> {
            val kp = KeyPairGenerator.getInstance("X25519").apply { initialize(255, rng) }.generateKeyPair()
            val priv = (kp.private as XECPrivateKey).scalar.get()
            // X.509 encoding of an X25519 key = fixed 12-byte header + the 32 raw bytes.
            val pub = kp.public.encoded.takeLast(32).toByteArray()
            val e = Base64.getEncoder()
            return e.encodeToString(priv) to e.encodeToString(pub)
        }

        /** awg-quick / wg-quick text, which AmneziaVPN, AmneziaWG and WireGuard all import. */
        fun configText(privateKey: String, c: TunnelConfig): String {
            val p = when (c.protocol) {
                Protocols.AMNEZIAWG -> Protocols.decode<AmneziaWG.ServerParams>(c.params)
                Protocols.WIREGUARD -> Protocols.decode<WireGuard.ServerParams>(c.params)
                    .let { AmneziaWG.ServerParams(it.serverPublicKey, it.endpoint, it.allowedIps, it.persistentKeepalive, emptyMap()) }
                // No config file: the standard vless:// links, one per transport, for other apps.
                Protocols.VLESS -> return Vless.links(Protocols.decode<Vless.ServerParams>(c.params), "Boop ${c.location}").joinToString("\n", postfix = "\n")
                else -> throw AppError(400, ErrorCode.UNSUPPORTED_PROTOCOL, "No config file for {protocol}", mapOf("protocol" to c.protocol))
            }
            return AmneziaWG.quickConfig(privateKey, c.address, c.dns, c.mtu, p)
        }

        fun qrPng(text: String, scale: Int = 8, border: Int = 4): ByteArray {
            val qr = QrCode.encodeText(text, QrCode.Ecc.LOW)
            val size = (qr.size + border * 2) * scale
            val img = BufferedImage(size, size, BufferedImage.TYPE_BYTE_BINARY)
            for (y in 0 until size) for (x in 0 until size) {
                val dark = qr.getModule(x / scale - border, y / scale - border)
                img.setRGB(x, y, if (dark) 0x000000 else 0xFFFFFF)
            }
            return ByteArrayOutputStream().also { ImageIO.write(img, "png", it) }.toByteArray()
        }
    }
}
