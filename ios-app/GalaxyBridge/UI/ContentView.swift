import SwiftUI
import os

struct ContentView: View {
    @StateObject private var viewModel = ConnectionViewModel()
    private let logger = Logger(subsystem: "com.localbridge.galaxybridge", category: "UI")

    var body: some View {
        NavigationStack {
            VStack(alignment: .leading, spacing: 16) {
                VStack(alignment: .leading, spacing: 6) {
                    Text("Galaxy Bridge")
                        .font(.largeTitle.bold())
                    Text("iPhone BLE Central")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                    Text(viewModel.bootMessage)
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                    Text("Build: \(BleCentralManager.buildMarker)")
                        .font(.caption2.monospaced())
                        .foregroundStyle(.secondary)
                }
                .padding(.horizontal)
                .padding(.top)

                Form {
                    Section("Connection") {
                        LabeledContent("Status", value: viewModel.ble.status)
                        LabeledContent("Last event", value: viewModel.ble.lastEvent)
                        LabeledContent("Write", value: viewModel.ble.writeState)
                        LabeledContent("Watch action", value: viewModel.ble.latestWatchAction)
                        LabeledContent("Health", value: viewModel.ble.latestHealth)
                    }

                    Section("Permissions") {
                        Button("Request Optional Permissions") {
                            viewModel.requestOptionalPermissions()
                        }
                    }

                    Section("Test Events") {
                        Button("Send Test Notification") {
                            viewModel.sendNotification()
                        }
                        Button("Simulate Incoming Call") {
                            viewModel.sendCall()
                        }
                    }
                }
                .scrollContentBackground(.hidden)
            }
            .background(Color(.systemBackground))
            .navigationTitle("Galaxy Bridge")
            .task {
                logger.info("ContentView appeared")
                viewModel.start()
            }
        }
    }
}

#Preview {
    ContentView()
}
