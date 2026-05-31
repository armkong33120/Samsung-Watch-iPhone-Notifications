import SwiftUI
import os

@main
struct GalaxyBridgeApp: App {
    private let logger = Logger(subsystem: "com.localbridge.galaxybridge", category: "App")

    init() {
        logger.info("GalaxyBridgeApp init")
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}
