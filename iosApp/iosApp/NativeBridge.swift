import ComposeApp
import NetworkExtension
import UIKit
import WireGuardKit

/// Swift side of IosNativeBridge: Keychain, VPN profile control and key generation.
/// Both protocols run through amneziawg-apple in the Packet Tunnel extension.
final class NativeBridge: NSObject, IosNativeBridge {
    private var manager: NETunnelProviderManager?
    private var onStatus: ((String) -> Void)?
    private var onLog: ((String) -> Void)?
    private var since: Double?

    var deviceName: String { UIDevice.current.name }

    // MARK: Keychain
    func keychainGet(key: String) -> String? { Keychain.get(key) }
    func keychainSet(key: String, value: String) { Keychain.set(key, value) }
    func keychainRemove(key: String) { Keychain.remove(key) }

    // MARK: Tunnel
    func protocols() -> [String] { ["amneziawg", "wireguard"] }

    func requestPermission(done: @escaping (KotlinBoolean) -> Void) {
        Task {
            do {
                // iOS asks for consent the first time a VPN profile is saved.
                let m = try await loadManager()
                if m.protocolConfiguration == nil { configure(m, killSwitch: false) }
                try await m.saveToPreferences()
                try await m.loadFromPreferences()
                done(KotlinBoolean(value: true))
            } catch {
                log("ERROR", "VPN permission: \(error.localizedDescription)")
                done(KotlinBoolean(value: false))
            }
        }
    }

    /// One key pair per protocol, created here and kept in the Keychain. Only the public key leaves.
    func clientParams(protocolId: String, done: @escaping (String?, String?) -> Void) {
        guard protocols().contains(protocolId) else { return done(nil, "Unsupported protocol \(protocolId)") }
        let key = "\(protocolId).privateKey"
        let privateKey = Keychain.get(key).flatMap(PrivateKey.init(base64Key:)) ?? {
            let k = PrivateKey()
            Keychain.set(key, k.base64Key)
            return k
        }()
        done(#"{"publicKey":"\#(privateKey.publicKey.base64Key)"}"#, nil)
    }

    func start(configJson: String, optionsJson: String, done: @escaping (String?) -> Void) {
        Task {
            do {
                let config = try JSONSerialization.jsonObject(with: Data(configJson.utf8)) as? [String: Any] ?? [:]
                let options = try JSONSerialization.jsonObject(with: Data(optionsJson.utf8)) as? [String: Any] ?? [:]
                let proto = config["protocol"] as? String ?? ""
                ActiveTunnel(configJson: configJson, optionsJson: optionsJson, privateKey: Keychain.get("\(proto).privateKey")).save()

                let m = try await loadManager()
                configure(m, killSwitch: options["killSwitch"] as? Bool ?? false, location: config["location"] as? String)
                try await m.saveToPreferences()
                try await m.loadFromPreferences()
                try m.connection.startVPNTunnel()
                log("INFO", "Starting \(proto) tunnel to \(config["location"] as? String ?? "server")")
                done(nil)
            } catch {
                log("ERROR", error.localizedDescription)
                done(error.localizedDescription)
            }
        }
    }

    func stop(done: @escaping () -> Void) {
        Task {
            (try? await loadManager())?.connection.stopVPNTunnel()
            log("INFO", "Tunnel down")
            done()
        }
    }

    func status(done: @escaping (String) -> Void) {
        Task {
            guard let m = try? await loadManager(), let session = m.connection as? NETunnelProviderSession else {
                return done(statusJson(.disconnected, rx: 0, tx: 0))
            }
            var rx: Int64 = 0, tx: Int64 = 0
            if session.status == .connected, let reply = try? await session.sendProviderMessage(Data("stats".utf8)),
               let o = try? JSONSerialization.jsonObject(with: reply) as? [String: Int64] {
                rx = o["rxBytes"] ?? 0
                tx = o["txBytes"] ?? 0
            }
            done(statusJson(session.status, rx: rx, tx: tx))
        }
    }

    func setListener(onStatus: @escaping (String) -> Void, onLog: @escaping (String) -> Void) {
        self.onStatus = onStatus
        self.onLog = onLog
        NotificationCenter.default.addObserver(forName: .NEVPNStatusDidChange, object: nil, queue: .main) { [weak self] note in
            guard let self, let c = note.object as? NEVPNConnection else { return }
            self.onStatus?(self.statusJson(c.status, rx: 0, tx: 0))
        }
    }

    // MARK: UI helpers
    func share(text: String) {
        let vc = UIActivityViewController(activityItems: [text], applicationActivities: nil)
        UIApplication.shared.connectedScenes.compactMap { ($0 as? UIWindowScene)?.keyWindow }.first?
            .rootViewController?.present(vc, animated: true)
    }
    func copy(text: String) { UIPasteboard.general.string = text }
    func openUrl(url: String) { if let u = URL(string: url) { UIApplication.shared.open(u) } }

    // MARK: private
    private func loadManager() async throws -> NETunnelProviderManager {
        if let m = manager { return m }
        let m = try await NETunnelProviderManager.loadAllFromPreferences().first ?? NETunnelProviderManager()
        manager = m
        return m
    }

    private func configure(_ m: NETunnelProviderManager, killSwitch: Bool, location: String? = nil) {
        let p = NETunnelProviderProtocol()
        p.providerBundleIdentifier = (Bundle.main.bundleIdentifier ?? "dev.jaganet.app") + ".PacketTunnel"
        p.serverAddress = location ?? "JagaNet"
        // Closest iOS equivalent of a kill switch: everything goes through the tunnel while it's on.
        p.includeAllNetworks = killSwitch
        m.protocolConfiguration = p
        m.localizedDescription = "JagaNet"
        m.isEnabled = true
    }

    private func statusJson(_ s: NEVPNStatus, rx: Int64, tx: Int64) -> String {
        let name: String
        switch s {
        case .connected: name = "CONNECTED"
        case .connecting, .reasserting: name = "CONNECTING"
        case .disconnecting: name = "DISCONNECTING"
        case .invalid: name = "ERROR"
        default: name = "DISCONNECTED"
        }
        if s == .connected { since = since ?? Date().timeIntervalSince1970 * 1000 } else if s == .disconnected { since = nil }
        let sinceJson = since.map { String(Int64($0)) } ?? "null"
        return #"{"status":"\#(name)","since":\#(sinceJson),"rxBytes":\#(rx),"txBytes":\#(tx)}"#
    }

    private func log(_ level: String, _ msg: String) {
        let escaped = msg.replacingOccurrences(of: "\\", with: "\\\\").replacingOccurrences(of: "\"", with: "\\\"")
        onLog?(#"{"time":\#(Int64(Date().timeIntervalSince1970 * 1000)),"level":"\#(level)","msg":"\#(escaped)"}"#)
    }
}
