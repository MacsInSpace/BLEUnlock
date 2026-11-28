// Android/Wear OS companion app support.
//
// The companion (companion-android/) advertises a fixed service UUID with an HMAC
// token, and answers a GATT challenge at unlock time. Identity comes from the shared
// secret, so rotating BLE addresses do not matter. Keep in sync with
// companion-android/.../Protocol.kt.
//
//   scan response service data = [VERSION][keyId 3][token 8]
//   token    = HMAC-SHA256(K, "adv" || u32be(unix / 600))[0..8]
//   keyId    = SHA-256("id" || K)[0..3]
//   response = HMAC-SHA256(K, "unlock" || nonce16)[0..16]
//   pairing  = "bleunlock-companion:1:" + base64url(K)

import Foundation
import AppKit
import CoreBluetooth
import CommonCrypto
import IOBluetooth
import Security

let CompanionServiceUUID = CBUUID(string: "B1E0C0DE-0001-4C0C-8A11-6E0B1EC0FFEE")
let CompanionChallengeUUID = CBUUID(string: "B1E0C0DE-0002-4C0C-8A11-6E0B1EC0FFEE")

struct CompanionConfig {
    let keyId: Data
    var name: String
    /// Public MAC of the phone's classic-Bluetooth bond, used to reach the GATT
    /// server over BR/EDR when an LE connection is refused because of stale bond keys.
    var classicMAC: String?

    /// Stable UUID used for monitoring, independent of the rotating BLE address.
    var virtualUUID: UUID {
        var bytes = [UInt8](Companion.sha256(Data("bleunlock-companion-vuuid".utf8) + keyId).prefix(16))
        bytes[6] = (bytes[6] & 0x0F) | 0x50 // RFC 4122 name-based (v5-style)
        bytes[8] = (bytes[8] & 0x3F) | 0x80
        return UUID(uuid: (bytes[0], bytes[1], bytes[2], bytes[3], bytes[4], bytes[5], bytes[6], bytes[7],
                           bytes[8], bytes[9], bytes[10], bytes[11], bytes[12], bytes[13], bytes[14], bytes[15]))
    }
}

enum Companion {
    static let windowSeconds: TimeInterval = 600
    private static let keychainService = "BLEUnlock Companion"
    private static let prefsKey = "companions"
    private static let legacyPrefsKey = "companion"

    // MARK: Config / storage (several companions, e.g. phone + watch)

    private static var cachedKeys: [Data: Data] = [:]
    private static var cachedConfigs: [CompanionConfig]?

    static var configs: [CompanionConfig] {
        if let c = cachedConfigs { return c }
        migrateLegacyConfigIfNeeded()
        let list = (UserDefaults.standard.array(forKey: prefsKey) as? [[String: Any]] ?? []).compactMap { d -> CompanionConfig? in
            guard let idHex = d["keyId"] as? String, let keyId = Data(hex: idHex) else { return nil }
            return CompanionConfig(keyId: keyId, name: d["name"] as? String ?? "Android Companion",
                                   classicMAC: d["classicMAC"] as? String)
        }
        cachedConfigs = list
        return list
    }

    static func config(keyId: Data) -> CompanionConfig? { configs.first { $0.keyId == keyId } }
    static func config(virtualUUID: UUID) -> CompanionConfig? { configs.first { $0.virtualUUID == virtualUUID } }
    static func isCompanion(_ uuid: UUID) -> Bool { config(virtualUUID: uuid) != nil }

    private static func persist(_ list: [CompanionConfig]) {
        UserDefaults.standard.set(list.map { c -> [String: Any] in
            var d: [String: Any] = ["keyId": c.keyId.hex, "name": c.name]
            if let mac = c.classicMAC { d["classicMAC"] = mac }
            return d
        }, forKey: prefsKey)
        cachedConfigs = list
    }

    /// Insert or update (matched by key id).
    static func save(_ c: CompanionConfig) {
        var list = configs.filter { $0.keyId != c.keyId }
        list.append(c)
        persist(list)
    }

    private static func migrateLegacyConfigIfNeeded() {
        guard let d = UserDefaults.standard.dictionary(forKey: legacyPrefsKey) else { return }
        if UserDefaults.standard.array(forKey: prefsKey) == nil {
            UserDefaults.standard.set([d], forKey: prefsKey)
        }
        UserDefaults.standard.removeObject(forKey: legacyPrefsKey)
    }

    /// Generate a new secret and return the pairing string for the QR code.
    /// Nothing is persisted until `commitPairing` is called.
    static func newPairing() -> (secret: Data, pairingString: String) {
        var b = [UInt8](repeating: 0, count: 32)
        precondition(SecRandomCopyBytes(kSecRandomDefault, b.count, &b) == errSecSuccess)
        let secret = Data(b)
        return (secret, pairingString(secret: secret))
    }

    private static let pairingPrefix = "bleunlock-companion:1:"

    static func pairingString(secret: Data) -> String {
        pairingPrefix + secret.base64EncodedString()
            .replacingOccurrences(of: "+", with: "-").replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }

    /// The pairing code of an existing companion, e.g. to import it in another user account.
    static func pairingString(for c: CompanionConfig) -> String? {
        key(c.keyId).map { pairingString(secret: $0) }
    }

    /// The 32-byte secret in a pairing code, or nil if the text is not one.
    static func parsePairing(_ text: String) -> Data? {
        let t = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard t.hasPrefix(pairingPrefix) else { return nil }
        var b64 = String(t.dropFirst(pairingPrefix.count))
            .replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        while b64.count % 4 != 0 { b64 += "=" }
        guard let d = Data(base64Encoded: b64), d.count == 32 else { return nil }
        return d
    }

    @discardableResult
    static func commitPairing(secret: Data, name: String, classicMAC: String?) -> CompanionConfig? {
        let keyId = sha256(Data("id".utf8) + secret).prefix(3)
        if let existing = config(keyId: keyId) { unpair(existing) } // 24-bit id collision: replace
        let add: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: keychainService,
            kSecAttrAccount as String: keyId.hex,
            kSecAttrLabel as String: "BLEUnlock Companion (\(name))",
            kSecValueData as String: secret,
        ]
        guard SecItemAdd(add as CFDictionary, nil) == errSecSuccess else { return nil }
        let c = CompanionConfig(keyId: keyId, name: name, classicMAC: classicMAC)
        save(c)
        cachedKeys[keyId] = secret
        return c
    }

    static func unpair(_ c: CompanionConfig) {
        let q: [String: Any] = [kSecClass as String: kSecClassGenericPassword,
                                kSecAttrService as String: keychainService,
                                kSecAttrAccount as String: c.keyId.hex]
        SecItemDelete(q as CFDictionary)
        cachedKeys[c.keyId] = nil
        persist(configs.filter { $0.keyId != c.keyId })
    }

    private static func key(_ keyId: Data) -> Data? {
        if let k = cachedKeys[keyId] { return k }
        guard config(keyId: keyId) != nil else { return nil }
        let q: [String: Any] = [kSecClass as String: kSecClassGenericPassword,
                                kSecAttrService as String: keychainService,
                                kSecAttrAccount as String: keyId.hex,
                                kSecReturnData as String: true]
        var out: CFTypeRef?
        guard SecItemCopyMatching(q as CFDictionary, &out) == errSecSuccess, let d = out as? Data, d.count == 32 else { return nil }
        cachedKeys[keyId] = d
        return d
    }

    // MARK: Crypto

    static func sha256(_ data: Data) -> Data {
        var out = [UInt8](repeating: 0, count: Int(CC_SHA256_DIGEST_LENGTH))
        data.withUnsafeBytes { _ = CC_SHA256($0.baseAddress, CC_LONG(data.count), &out) }
        return Data(out)
    }

    private static func hmac(_ key: Data, _ data: Data) -> Data {
        var out = [UInt8](repeating: 0, count: Int(CC_SHA256_DIGEST_LENGTH))
        key.withUnsafeBytes { k in
            data.withUnsafeBytes { d in
                CCHmac(CCHmacAlgorithm(kCCHmacAlgSHA256), k.baseAddress, key.count, d.baseAddress, data.count, &out)
            }
        }
        return Data(out)
    }

    static func token(window: UInt32, key: Data) -> Data {
        var be = window.bigEndian
        return hmac(key, Data("adv".utf8) + Data(bytes: &be, count: 4)).prefix(8)
    }

    /// The paired companion that sent `serviceData`, if it is a fresh, valid advert.
    /// Accepts a few past windows because Android defers alarms in Doze.
    static func verifyAdvert(_ serviceData: Data, now: Date = Date()) -> CompanionConfig? {
        let sd = Data(serviceData)
        guard sd.count == 12, sd[0] == 1 else { return nil }
        let keyId = sd.subdata(in: 1..<4)
        guard let c = config(keyId: keyId), let k = key(keyId) else { return nil }
        let w = UInt32(now.timeIntervalSince1970 / windowSeconds)
        let tok = sd.subdata(in: 4..<12)
        return (w &- 3 ... w &+ 1).contains { token(window: $0, key: k) == tok } ? c : nil
    }

    static func expectedResponse(nonce: Data, keyId: Data) -> Data? {
        guard let k = key(keyId) else { return nil }
        return hmac(k, Data("unlock".utf8) + nonce).prefix(16)
    }
}

/// One in-flight GATT challenge against the companion. Driven by BLE's delegate callbacks.
final class CompanionChallenge: NSObject {
    let peripheral: CBPeripheral
    let config: CompanionConfig
    let nonce: Data
    let startedAt = Date()
    var completion: ((Bool) -> Void)?
    var usedClassicFallback = false
    var classicDevice: IOBluetoothDevice?
    var timer: Timer?
    var onClassicConnected: ((IOReturn) -> Void)?

    init(peripheral: CBPeripheral, config: CompanionConfig, completion: @escaping (Bool) -> Void) {
        self.peripheral = peripheral
        self.config = config
        var b = [UInt8](repeating: 0, count: 16)
        _ = SecRandomCopyBytes(kSecRandomDefault, b.count, &b)
        self.nonce = Data(b)
        self.completion = completion
    }

    /// IOBluetoothDevice asynchronous openConnection target callback.
    @objc func connectionComplete(_ device: IOBluetoothDevice, status: IOReturn) {
        let cb = onClassicConnected
        onClassicConnected = nil
        cb?(status)
    }

    func finish(_ ok: Bool, reason: String) {
        timer?.invalidate()
        timer = nil
        onClassicConnected = nil
        print(String(format: "Companion challenge %@ (%@) in %.2fs", ok ? "verified" : "failed", reason, Date().timeIntervalSince(startedAt)))
        if let classic = classicDevice, classic.isConnected() { classic.closeConnection() }
        classicDevice = nil
        let c = completion
        completion = nil
        c?(ok)
    }
}

/// NSTextField that handles ⌘V/⌘C/⌘X/⌘A itself: BLEUnlock has no Edit menu, so
/// these shortcuts are not routed to text fields in its alerts otherwise.
final class PasteableTextField: NSTextField {
    override func performKeyEquivalent(with event: NSEvent) -> Bool {
        guard event.modifierFlags.intersection(.deviceIndependentFlagsMask) == .command,
              let key = event.charactersIgnoringModifiers else { return super.performKeyEquivalent(with: event) }
        let action: Selector?
        switch key {
        case "v": action = #selector(NSText.paste(_:))
        case "c": action = #selector(NSText.copy(_:))
        case "x": action = #selector(NSText.cut(_:))
        case "a": action = #selector(NSText.selectAll(_:))
        default: action = nil
        }
        if let action = action, NSApp.sendAction(action, to: nil, from: self) { return true }
        return super.performKeyEquivalent(with: event)
    }
}

extension Data {
    var hex: String { map { String(format: "%02x", $0) }.joined() }
    init?(hex: String) {
        guard hex.count % 2 == 0 else { return nil }
        var d = Data()
        var i = hex.startIndex
        while i < hex.endIndex {
            let j = hex.index(i, offsetBy: 2)
            guard let b = UInt8(hex[i..<j], radix: 16) else { return nil }
            d.append(b)
            i = j
        }
        self = d
    }
}
