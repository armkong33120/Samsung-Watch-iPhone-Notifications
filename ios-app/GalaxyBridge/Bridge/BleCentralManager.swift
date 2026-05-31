import CoreBluetooth
import Foundation
import os

@MainActor
final class BleCentralManager: NSObject, ObservableObject {
    static let buildMarker = "restore-1"

    @Published var status = "Idle"
    @Published var latestWatchAction = "No action received"
    @Published var latestHealth = "No health samples"
    @Published var lastEvent = "Ready"
    @Published var writeState = "Write queue idle"

    private var central: CBCentralManager!
    private var watch: CBPeripheral?
    private var notifyEventsCharacteristic: CBCharacteristic?
    private var heartbeatCharacteristic: CBCharacteristic?
    private struct PendingWriteChunk {
        let envelopeId: String
        let envelopeType: String
        let seq: Int
        let total: Int
        let data: Data
        let characteristic: CBCharacteristic
    }

    private var pendingWriteChunks: [PendingWriteChunk] = []
    private var isWritingChunk = false
    private let reassembler = ChunkReassembler()
    private let reconnect = ReconnectController()
    private let healthWriter = HealthKitWriter()
    private let logger = Logger(subsystem: "com.localbridge.galaxybridge", category: "BLE")

    override init() {
        super.init()
        logger.info("BleCentralManager init")
        central = CBCentralManager(
            delegate: self,
            queue: .main,
            options: [CBCentralManagerOptionRestoreIdentifierKey: "GalaxyBridgeCentral"]
        )
    }

    func start() {
        logger.info("start requested, central state: \(self.central.state.rawValue)")
        lastEvent = "Start requested"
        if central.state == .poweredOn {
            scan()
        } else {
            status = readableState(central.state)
            lastEvent = "Waiting for Bluetooth"
        }
    }

    func requestHealthAuthorization() {
        logger.info("requesting HealthKit authorization")
        healthWriter.requestAuthorization()
    }

    func requestNotificationAuthorization() {
        logger.info("requesting notification authorization")
        LocalNotificationManager().requestAuthorization()
    }

    func sendTestNotification() {
        write(BridgeEnvelope(type: "notification", payload: [
            "source": "Galaxy Bridge",
            "title": "Test notification",
            "body": "Forwarded over custom BLE"
        ]))
    }

    func sendSimulatedCall() {
        write(BridgeEnvelope(type: "call", payload: [
            "caller": "Simulated iPhone Call",
            "phone": "+1 555 0100"
        ]))
    }

    private func scan() {
        status = "Scanning"
        lastEvent = "Scanning for GalaxyBridge service"
        logger.info("scanning for service \(BridgeGattProfile.serviceUUID.uuidString, privacy: .public)")
        central.scanForPeripherals(withServices: [BridgeGattProfile.serviceUUID], options: nil)
    }

    private func write(_ envelope: BridgeEnvelope) {
        guard watch != nil, let characteristic = notifyEventsCharacteristic else {
            status = "Not connected"
            lastEvent = "Cannot write: not connected"
            writeState = "Write skipped: not connected"
            logger.warning("write skipped, no peripheral/characteristic")
            return
        }
        guard pendingWriteChunks.isEmpty && !isWritingChunk else {
            lastEvent = "Write busy; try again"
            writeState = "Write busy: pending \(pendingWriteChunks.count)"
            logger.warning("write skipped, queue busy pending=\(self.pendingWriteChunks.count)")
            return
        }
        do {
            enqueueReliableWrite(try BridgeProtocol.chunks(for: envelope), envelope: envelope, characteristic: characteristic)
            lastEvent = "Queued \(envelope.type)"
            logger.info("queued envelope \(envelope.type, privacy: .public) id=\(envelope.id, privacy: .public)")
        } catch {
            status = "Encode failed"
            lastEvent = "Encode failed: \(error.localizedDescription)"
            writeState = "Encode failed"
            logger.error("encode failed: \(error.localizedDescription, privacy: .public)")
        }
    }

    private func enqueueReliableWrite(_ chunks: [Data], envelope: BridgeEnvelope, characteristic: CBCharacteristic) {
        let total = chunks.count
        pendingWriteChunks = chunks.enumerated().map { index, data in
            PendingWriteChunk(
                envelopeId: envelope.id,
                envelopeType: envelope.type,
                seq: index,
                total: total,
                data: data,
                characteristic: characteristic
            )
        }
        writeState = "Queued \(total) chunk(s) for \(envelope.type)"
        logger.info("queued chunks id=\(envelope.id, privacy: .public) type=\(envelope.type, privacy: .public) total=\(total)")
        sendNextChunkIfNeeded()
    }

    private func sendNextChunkIfNeeded() {
        guard !isWritingChunk else { return }
        guard let watch, let next = pendingWriteChunks.first else { return }
        isWritingChunk = true
        writeState = "Writing \(next.envelopeType) chunk \(next.seq + 1)/\(next.total)"
        logger.info(
            "wrote chunk id=\(next.envelopeId, privacy: .public) seq=\(next.seq) total=\(next.total) bytes=\(next.data.count)"
        )
        watch.writeValue(next.data, for: next.characteristic, type: .withResponse)
    }

    private func sendHeartbeat() {
        write(BridgeEnvelope(type: "heartbeat", payload: [
            "platform": "iOS",
            "protocol": BridgeGattProfile.protocolVersion
        ]))
    }

    private func handle(_ data: Data) {
        guard let merged = try? reassembler.accept(data), let envelope = try? BridgeProtocol.decode(merged) else {
            lastEvent = "Dropped unreadable BLE payload"
            logger.warning("dropped unreadable BLE payload")
            return
        }
        guard reassembler.remember(envelope.id) else { return }
        lastEvent = "Received \(envelope.type)"
        logger.info("received envelope \(envelope.type, privacy: .public)")
        switch envelope.type {
        case "call_control":
            latestWatchAction = envelope.payload["action"] as? String ?? "Unknown action"
        case "health":
            let heartRate = envelope.payload["heartRateBpm"] as? Double
            let steps = envelope.payload["steps"] as? Int
            latestHealth = "HR \(heartRate.map { String(Int($0)) } ?? "--")  Steps \(steps.map(String.init) ?? "--")"
            healthWriter.write(heartRateBpm: heartRate, steps: steps)
        case "heartbeat":
            status = "Connected"
        default:
            break
        }
    }

    private func readableState(_ state: CBManagerState) -> String {
        switch state {
        case .poweredOn: "Bluetooth ready"
        case .poweredOff: "Bluetooth off"
        case .unauthorized: "Unauthorized"
        case .unsupported: "Unsupported"
        case .resetting: "Resetting"
        case .unknown: "Idle"
        @unknown default: "Unknown"
        }
    }
}

extension BleCentralManager: @preconcurrency CBCentralManagerDelegate {
    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        logger.info("central state changed: \(central.state.rawValue)")
        switch central.state {
        case .poweredOn:
            scan()
        case .poweredOff:
            status = "Bluetooth off"
            lastEvent = "Turn Bluetooth on"
        case .unauthorized:
            status = "Unauthorized"
            lastEvent = "Allow Bluetooth in Settings"
        default:
            status = "Bluetooth unavailable"
            lastEvent = readableState(central.state)
        }
    }

    func centralManager(_ central: CBCentralManager, didDiscover peripheral: CBPeripheral, advertisementData: [String: Any], rssi RSSI: NSNumber) {
        watch = peripheral
        watch?.delegate = self
        status = "Connecting"
        lastEvent = "Discovered \(peripheral.name ?? "Galaxy Watch") RSSI \(RSSI)"
        logger.info("discovered peripheral \(peripheral.identifier.uuidString, privacy: .public), rssi \(RSSI.intValue)")
        central.stopScan()
        central.connect(peripheral)
    }

    func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        reconnect.reset()
        status = "Discovering"
        lastEvent = "Connected, discovering services"
        logger.info("connected to peripheral \(peripheral.identifier.uuidString, privacy: .public)")
        peripheral.discoverServices([BridgeGattProfile.serviceUUID])
    }

    func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: Error?) {
        status = "Disconnected"
        lastEvent = error.map { "Disconnected: \($0.localizedDescription)" } ?? "Disconnected"
        logger.warning("disconnected: \(error?.localizedDescription ?? "no error", privacy: .public)")
        reconnect.schedule { [weak self] in self?.scan() }
    }

    func centralManager(_ central: CBCentralManager, willRestoreState dict: [String: Any]) {
        if let peripherals = dict[CBCentralManagerRestoredStatePeripheralsKey] as? [CBPeripheral] {
            watch = peripherals.first
            watch?.delegate = self
            lastEvent = "Restored BLE state"
            logger.info("restored BLE state with \(peripherals.count) peripherals")
        }
    }
}

extension BleCentralManager: @preconcurrency CBPeripheralDelegate {
    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        if let error {
            status = "Failed"
            lastEvent = "Service discovery failed: \(error.localizedDescription)"
            logger.error("service discovery failed: \(error.localizedDescription, privacy: .public)")
            return
        }
        peripheral.services?.forEach { service in
            logger.info("discovered service \(service.uuid.uuidString, privacy: .public)")
            peripheral.discoverCharacteristics([
                BridgeGattProfile.notifyEventsUUID,
                BridgeGattProfile.callControlUUID,
                BridgeGattProfile.healthSamplesUUID,
                BridgeGattProfile.heartbeatUUID
            ], for: service)
        }
    }

    func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        if let error {
            status = "Failed"
            lastEvent = "Characteristic discovery failed: \(error.localizedDescription)"
            logger.error("characteristic discovery failed: \(error.localizedDescription, privacy: .public)")
            return
        }
        service.characteristics?.forEach { characteristic in
            logger.info("discovered characteristic \(characteristic.uuid.uuidString, privacy: .public)")
            switch characteristic.uuid {
            case BridgeGattProfile.notifyEventsUUID:
                notifyEventsCharacteristic = characteristic
            case BridgeGattProfile.callControlUUID, BridgeGattProfile.healthSamplesUUID, BridgeGattProfile.heartbeatUUID:
                peripheral.setNotifyValue(true, for: characteristic)
                if characteristic.uuid == BridgeGattProfile.heartbeatUUID {
                    heartbeatCharacteristic = characteristic
                    peripheral.readValue(for: characteristic)
                }
            default:
                break
            }
        }
        status = "Connected"
        lastEvent = "Characteristics ready"
        sendHeartbeat()
    }

    func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
        if let error {
            lastEvent = "BLE update failed: \(error.localizedDescription)"
            logger.error("BLE update failed: \(error.localizedDescription, privacy: .public)")
            return
        }
        guard let data = characteristic.value else { return }
        handle(data)
    }

    func peripheral(_ peripheral: CBPeripheral, didWriteValueFor characteristic: CBCharacteristic, error: Error?) {
        if let error {
            isWritingChunk = false
            pendingWriteChunks.removeAll()
            lastEvent = "BLE write failed: \(error.localizedDescription)"
            writeState = "Write failed: \(error.localizedDescription)"
            logger.error("BLE write failed: \(error.localizedDescription, privacy: .public)")
            return
        }
        let completed = pendingWriteChunks.first
        if !pendingWriteChunks.isEmpty {
            pendingWriteChunks.removeFirst()
        }
        isWritingChunk = false
        if let completed {
            writeState = "ACK chunk \(completed.seq + 1)/\(completed.total), remaining \(pendingWriteChunks.count)"
            logger.info(
                "didWrite chunk id=\(completed.envelopeId, privacy: .public) seq=\(completed.seq) total=\(completed.total) remaining=\(self.pendingWriteChunks.count)"
            )
        }
        sendNextChunkIfNeeded()
        if pendingWriteChunks.isEmpty && !isWritingChunk {
            writeState = "Write queue idle"
        }
    }
}
