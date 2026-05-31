import CoreBluetooth

enum BridgeGattProfile {
    // Store raw UUID strings as static constants (pure Swift types are Sendable).
    private static let serviceUUIDString = "8F0E7A10-4B6D-4F0B-9C2E-7F4C0A11B001"
    private static let notifyEventsUUIDString = "8F0E7A11-4B6D-4F0B-9C2E-7F4C0A11B001"
    private static let callControlUUIDString = "8F0E7A12-4B6D-4F0B-9C2E-7F4C0A11B001"
    private static let healthSamplesUUIDString = "8F0E7A13-4B6D-4F0B-9C2E-7F4C0A11B001"
    private static let heartbeatUUIDString = "8F0E7A14-4B6D-4F0B-9C2E-7F4C0A11B001"

    // Expose computed properties that create CBUUID on demand to avoid static stored non-Sendable state.
    static var serviceUUID: CBUUID { CBUUID(string: serviceUUIDString) }
    static var notifyEventsUUID: CBUUID { CBUUID(string: notifyEventsUUIDString) }
    static var callControlUUID: CBUUID { CBUUID(string: callControlUUIDString) }
    static var healthSamplesUUID: CBUUID { CBUUID(string: healthSamplesUUIDString) }
    static var heartbeatUUID: CBUUID { CBUUID(string: heartbeatUUIDString) }

    static let protocolVersion = 1
}
