import Foundation
import Combine
import os

@MainActor
final class ConnectionViewModel: ObservableObject {
    let ble = BleCentralManager()
    @Published var bootMessage = "UI ready"

    private let notifications = LocalNotificationManager()
    private var cancellables: Set<AnyCancellable> = []
    private let logger = Logger(subsystem: "com.localbridge.galaxybridge", category: "UI")
    private var didStart = false

    init() {
        logger.info("ConnectionViewModel init")
        ble.objectWillChange.sink { [weak self] _ in
            self?.objectWillChange.send()
        }.store(in: &cancellables)
    }

    func start() {
        guard !didStart else { return }
        didStart = true
        bootMessage = "Starting Bluetooth scan"
        logger.info("starting BLE after UI appeared")
        ble.start()
    }

    func requestOptionalPermissions() {
        bootMessage = "Requesting optional permissions"
        logger.info("requesting optional permissions")
        notifications.requestAuthorization()
        ble.requestHealthAuthorization()
    }

    func sendNotification() {
        notifications.showLocalTestNotification()
        ble.sendTestNotification()
    }

    func sendCall() {
        ble.sendSimulatedCall()
    }
}
