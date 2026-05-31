import Foundation
import HealthKit

@MainActor
final class HealthKitWriter {
    private let store = HKHealthStore()
    private var authorized = false

    func requestAuthorization() {
        guard HKHealthStore.isHealthDataAvailable() else { return }
        let types: Set<HKSampleType> = [
            HKQuantityType(.heartRate),
            HKQuantityType(.stepCount)
        ]
        store.requestAuthorization(toShare: types, read: []) { [weak self] success, _ in
            Task { @MainActor in
                self?.authorized = success
            }
        }
    }

    func write(heartRateBpm: Double?, steps: Int?) {
        guard authorized else { return }
        var samples: [HKQuantitySample] = []
        let now = Date()
        if let heartRateBpm {
            let quantity = HKQuantity(unit: HKUnit.count().unitDivided(by: .minute()), doubleValue: heartRateBpm)
            samples.append(HKQuantitySample(type: HKQuantityType(.heartRate), quantity: quantity, start: now, end: now))
        }
        if let steps {
            let quantity = HKQuantity(unit: .count(), doubleValue: Double(steps))
            samples.append(HKQuantitySample(type: HKQuantityType(.stepCount), quantity: quantity, start: now, end: now))
        }
        guard !samples.isEmpty else { return }
        store.save(samples) { _, _ in }
    }
}
