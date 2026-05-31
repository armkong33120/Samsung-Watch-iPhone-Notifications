import Foundation

/// Reconnect/backoff helper.
/// - Increments delay on each schedule attempt (1s, 2s, 4s, ... up to 30s).
/// - `reset()` clears attempts and cancels any pending reconnect work.
final class ReconnectController {
    private var attempts: Int = 0
    private var pendingWorkItem: DispatchWorkItem?

    func reset() {
        attempts = 0
        pendingWorkItem?.cancel()
        pendingWorkItem = nil
    }

    func schedule(on queue: DispatchQueue = .main, _ work: @escaping () -> Void) {
        attempts += 1
        let delay = min(pow(2.0, Double(max(0, attempts - 1))), 30.0)
        let item = DispatchWorkItem(block: work)
        pendingWorkItem?.cancel()
        pendingWorkItem = item
        queue.asyncAfter(deadline: .now() + delay, execute: item)
    }
}
