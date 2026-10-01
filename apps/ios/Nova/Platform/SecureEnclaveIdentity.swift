import CryptoKit
import Foundation
import Network
import Shared
import UIKit

/// Geräteschlüssel in der Secure Enclave (Simulator: Software-Schlüssel im Keychain).
/// Der private Schlüssel verlässt das Gerät nie; das Backend kennt nur den öffentlichen Teil.
final class SecureEnclaveIdentity: NSObject, DeviceIdentity {
    private let keychain: KeychainStore
    private let monitor = NWPathMonitor()
    private var path: NWPath?

    init(keychain: KeychainStore) {
        self.keychain = keychain
        super.init()
        monitor.pathUpdateHandler = { [weak self] in self?.path = $0 }
        monitor.start(queue: DispatchQueue(label: "nova.network"))
    }

    var deviceName: String { UIDevice.current.name.isEmpty ? UIDevice.current.model : UIDevice.current.name }
    var platform: String { "ios" }

    func publicKeyBase64() -> String {
        publicKeyDER().base64EncodedString()
    }

    func sign(message: KotlinByteArray) -> KotlinByteArray {
        let data = message.toData()
        let signature: Data
        if let key = enclaveKey() {
            signature = (try? key.signature(for: data).derRepresentation) ?? Data()
        } else {
            signature = (try? softwareKey().signature(for: data).derRepresentation) ?? Data()
        }
        return KotlinByteArray.from(signature)
    }

    func connectionType() -> String {
        guard let path, path.status == .satisfied else { return "unknown" }
        if path.usesInterfaceType(.wifi) { return "wifi" }
        if path.usesInterfaceType(.cellular) { return "cellular" }
        if path.usesInterfaceType(.wiredEthernet) { return "ethernet" }
        // iOS meldet VPN-Tunnel als „other“ – nur ein schwaches Signal.
        if path.usesInterfaceType(.other) { return "vpn" }
        return "unknown"
    }

    // MARK: - Schlüssel

    private func publicKeyDER() -> Data {
        if let key = enclaveKey() { return key.publicKey.derRepresentation }
        return (try? softwareKey().publicKey.derRepresentation) ?? Data()
    }

    private func enclaveKey() -> SecureEnclave.P256.Signing.PrivateKey? {
        guard SecureEnclave.isAvailable else { return nil }
        if let stored = keychain.data(for: Self.enclaveTag),
           let key = try? SecureEnclave.P256.Signing.PrivateKey(dataRepresentation: stored) {
            return key
        }
        guard let key = try? SecureEnclave.P256.Signing.PrivateKey() else { return nil }
        keychain.setData(key.dataRepresentation, for: Self.enclaveTag)
        return key
    }

    private func softwareKey() throws -> P256.Signing.PrivateKey {
        if let stored = keychain.data(for: Self.softwareTag) {
            return try P256.Signing.PrivateKey(rawRepresentation: stored)
        }
        let key = P256.Signing.PrivateKey()
        keychain.setData(key.rawRepresentation, for: Self.softwareTag)
        return key
    }

    private static let enclaveTag = "nova.deviceKey.enclave"
    private static let softwareTag = "nova.deviceKey.software"
}

extension KotlinByteArray {
    static func from(_ data: Data) -> KotlinByteArray {
        let array = KotlinByteArray(size: Int32(data.count))
        for (index, byte) in data.enumerated() { array.set(index: Int32(index), value: Int8(bitPattern: byte)) }
        return array
    }

    func toData() -> Data {
        var data = Data(count: Int(size))
        for index in 0..<Int(size) { data[index] = UInt8(bitPattern: get(index: Int32(index))) }
        return data
    }
}
