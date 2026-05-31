import Foundation

struct BridgeEnvelope: Identifiable, Equatable {
    let id: String
    let type: String
    let timestampSeconds: Int
    let payload: [String: Any]
    let version: Int

    init(
        id: String = UUID().uuidString,
        type: String,
        timestampSeconds: Int = Int(Date().timeIntervalSince1970),
        payload: [String: Any],
        version: Int = BridgeGattProfile.protocolVersion
    ) {
        self.id = id
        self.type = type
        self.timestampSeconds = timestampSeconds
        self.payload = payload
        self.version = version
    }

    static func == (lhs: BridgeEnvelope, rhs: BridgeEnvelope) -> Bool {
        lhs.id == rhs.id && lhs.type == rhs.type
    }
}

enum BridgeProtocol {
    static let maxChunkBytes = 160

    static func encode(_ envelope: BridgeEnvelope) throws -> Data {
        let object: [String: Any] = [
            "v": envelope.version,
            "id": envelope.id,
            "type": envelope.type,
            "ts": envelope.timestampSeconds,
            "payload": envelope.payload
        ]
        return try JSONSerialization.data(withJSONObject: object)
    }

    static func decode(_ data: Data) throws -> BridgeEnvelope {
        let object = try JSONSerialization.jsonObject(with: data) as? [String: Any] ?? [:]
        return BridgeEnvelope(
            id: object["id"] as? String ?? UUID().uuidString,
            type: object["type"] as? String ?? "unknown",
            timestampSeconds: object["ts"] as? Int ?? Int(Date().timeIntervalSince1970),
            payload: object["payload"] as? [String: Any] ?? [:],
            version: object["v"] as? Int ?? 1
        )
    }

    static func ack(for id: String) -> BridgeEnvelope {
        BridgeEnvelope(type: "ack", payload: ["for": id])
    }

    static func chunks(for envelope: BridgeEnvelope) throws -> [Data] {
        let data = try encode(envelope)
        guard data.count > maxChunkBytes else { return [data] }
        let total = Int(ceil(Double(data.count) / Double(maxChunkBytes)))
        return stride(from: 0, to: data.count, by: maxChunkBytes).enumerated().map { index, offset in
            let end = min(offset + maxChunkBytes, data.count)
            let part = data.subdata(in: offset..<end)
            let object: [String: Any] = [
                "chunk": true,
                "id": envelope.id,
                "seq": index,
                "total": total,
                "data": part.base64EncodedString()
            ]
            return try! JSONSerialization.data(withJSONObject: object)
        }
    }
}

final class ChunkReassembler {
    private var chunks: [String: [Int: String]] = [:]
    private var totals: [String: Int] = [:]
    private var seen: [String] = []
    private var seenSet: Set<String> = []

    func remember(_ id: String) -> Bool {
        guard !seenSet.contains(id) else { return false }
        seen.append(id)
        seenSet.insert(id)
        while seen.count > 64 {
            seenSet.remove(seen.removeFirst())
        }
        return true
    }

    func accept(_ data: Data) throws -> Data? {
        let object = try JSONSerialization.jsonObject(with: data) as? [String: Any] ?? [:]
        guard object["chunk"] as? Bool == true else { return data }
        guard
            let id = object["id"] as? String,
            let seq = object["seq"] as? Int,
            let total = object["total"] as? Int,
            let fragment = object["data"] as? String
        else { return nil }

        totals[id] = total
        var map = chunks[id, default: [:]]
        map[seq] = fragment
        chunks[id] = map
        guard map.count == total else { return nil }

        var merged = Data()
        for index in 0..<total {
            guard let fragment = map[index], let data = Data(base64Encoded: fragment) else {
                return nil
            }
            merged.append(data)
        }
        chunks[id] = nil
        totals[id] = nil
        return merged
    }
}
