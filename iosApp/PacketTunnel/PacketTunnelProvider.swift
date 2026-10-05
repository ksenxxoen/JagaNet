import NetworkExtension
import WireGuardKit

/// Runs every protocol: reads the generic TunnelConfig the app stored in the shared
/// Keychain and hands it to the backend registered for `config.protocol`.
protocol TunnelBackend: AnyObject {
    func start(provider: NEPacketTunnelProvider, config: [String: Any], options: [String: Any], privateKey: String?) async throws
    func stop() async
    /// (rxBytes, txBytes)
    func counters() async -> (Int64, Int64)
}

final class PacketTunnelProvider: NEPacketTunnelProvider {
    /// Add a custom protocol: implement TunnelBackend and register it here
    /// (and list its id in NativeBridge.protocols()).
    private let backends: [String: () -> TunnelBackend] = [
        "amneziawg": { AmneziaWGBackend() },
        // AmneziaWG with no obfuscation values is wire-compatible WireGuard.
        "wireguard": { AmneziaWGBackend() },
    ]
    private var active: TunnelBackend?

    override func startTunnel(options _: [String: NSObject]?) async throws {
        guard
            let saved = ActiveTunnel.load(),
            let config = try JSONSerialization.jsonObject(with: Data(saved.configJson.utf8)) as? [String: Any],
            let protocolId = config["protocol"] as? String,
            let make = backends[protocolId]
        else { throw NEVPNError(.configurationInvalid) }
        let options = (try? JSONSerialization.jsonObject(with: Data(saved.optionsJson.utf8)) as? [String: Any]) ?? [:]
        let backend = make()
        try await backend.start(provider: self, config: config, options: options, privateKey: saved.privateKey)
        active = backend
    }

    override func stopTunnel(with _: NEProviderStopReason) async {
        await active?.stop()
        active = nil
    }

    /// The app polls byte counters with sendProviderMessage("stats").
    override func handleAppMessage(_ messageData: Data) async -> Data? {
        guard String(data: messageData, encoding: .utf8) == "stats", let b = active else { return nil }
        let (rx, tx) = await b.counters()
        return try? JSONSerialization.data(withJSONObject: ["rxBytes": rx, "txBytes": tx])
    }
}
