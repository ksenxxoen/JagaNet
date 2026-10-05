import NetworkExtension
import WireGuardKit

/// WireGuard and AmneziaWG through amneziawg-apple (module name WireGuardKit).
/// `params.obfuscation` holds awg-quick keys; absent for plain WireGuard.
final class AmneziaWGBackend: TunnelBackend {
    private var adapter: WireGuardAdapter?

    func start(provider: NEPacketTunnelProvider, config: [String: Any], options _: [String: Any], privateKey: String?) async throws {
        let params = config["params"] as? [String: Any] ?? [:]
        guard
            let privateKey = privateKey.flatMap(PrivateKey.init(base64Key:)),
            let serverKey = (params["serverPublicKey"] as? String).flatMap(PublicKey.init(base64Key:)),
            let endpoint = (params["endpoint"] as? String).flatMap(Endpoint.init(from:)),
            let address = (config["address"] as? String).flatMap(IPAddressRange.init(from:))
        else { throw NEVPNError(.configurationInvalid) }

        var iface = InterfaceConfiguration(privateKey: privateKey)
        iface.addresses = [address]
        iface.dns = (config["dns"] as? [String] ?? []).compactMap(DNSServer.init(from:))
        iface.mtu = (config["mtu"] as? NSNumber)?.uint16Value
        Self.applyObfuscation(params["obfuscation"] as? [String: String] ?? [:], to: &iface)

        var peer = PeerConfiguration(publicKey: serverKey)
        peer.endpoint = endpoint
        peer.allowedIPs = (params["allowedIps"] as? [String] ?? ["0.0.0.0/0"]).compactMap(IPAddressRange.init(from:))
        peer.persistentKeepAlive = (params["persistentKeepalive"] as? NSNumber)?.uint16Value

        let tunnel = TunnelConfiguration(name: "JagaNet", interface: iface, peers: [peer])
        let adapter = WireGuardAdapter(with: provider) { _, message in NSLog("[awg] %@", message) }
        self.adapter = adapter
        try await withCheckedThrowingContinuation { (c: CheckedContinuation<Void, Error>) in
            adapter.start(tunnelConfiguration: tunnel) { error in
                if let error { c.resume(throwing: error) } else { c.resume() }
            }
        }
    }

    /// awg-quick keys (see dev.jaganet.api.AmneziaWG) → amneziawg-apple's InterfaceConfiguration.
    static func applyObfuscation(_ o: [String: String], to i: inout InterfaceConfiguration) {
        func u16(_ k: String) -> UInt16? { o[k].flatMap { UInt16($0) } }
        i.junkPacketCount = u16("Jc")
        i.junkPacketMinSize = u16("Jmin")
        i.junkPacketMaxSize = u16("Jmax")
        i.initPacketJunkSize = u16("S1")
        i.responsePacketJunkSize = u16("S2")
        i.cookieReplyPacketJunkSize = u16("S3")
        i.transportPacketJunkSize = u16("S4")
        i.initPacketMagicHeader = o["H1"]
        i.responsePacketMagicHeader = o["H2"]
        i.underloadPacketMagicHeader = o["H3"]
        i.transportPacketMagicHeader = o["H4"]
        i.specialJunk1 = o["I1"]
        i.specialJunk2 = o["I2"]
        i.specialJunk3 = o["I3"]
        i.specialJunk4 = o["I4"]
        i.specialJunk5 = o["I5"]
        i.headerProtectionKey = o["HeaderProtectionKey"].flatMap(PrivateKey.init(base64Key:))
        i.contentPaddingAddition = o["ContentPaddingAddition"]
    }

    func stop() async {
        await withCheckedContinuation { (c: CheckedContinuation<Void, Never>) in
            guard let adapter else { return c.resume() }
            adapter.stop { _ in c.resume() }
        }
    }

    func counters() async -> (Int64, Int64) {
        await withCheckedContinuation { c in
            guard let adapter else { return c.resume(returning: (0, 0)) }
            adapter.getRuntimeConfiguration { cfg in
                // UAPI text: sum rx_bytes / tx_bytes lines.
                var rx: Int64 = 0, tx: Int64 = 0
                for line in (cfg ?? "").split(separator: "\n") {
                    if line.hasPrefix("rx_bytes=") { rx += Int64(line.dropFirst(9)) ?? 0 }
                    if line.hasPrefix("tx_bytes=") { tx += Int64(line.dropFirst(9)) ?? 0 }
                }
                c.resume(returning: (rx, tx))
            }
        }
    }
}
