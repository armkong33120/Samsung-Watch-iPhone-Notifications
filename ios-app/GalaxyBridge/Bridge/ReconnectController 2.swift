import Foundation

/// Simple reconnect/backoff helper.
/// - Increments delay on each schedule attempt (1s, 2s, 4s, 8s, ... up to 30s)
/// - `reset()` clears attempts and cancels any pending work
final class ReconnectController {
    private var attempts: Int = 0
    private var pendingWorkItem: DispatchWorkItem?

    /// Reset attempts and cancel any pending scheduled work.
    func reset() {
        attempts = 0
        pendingWorkItem?.cancel()
        pendingWorkItem = nil
    }

    /// Schedule a reconnect action with exponential backoff on the provided queue (default: main).
    func schedule(on queue: DispatchQueue = .main, _ block: @escaping () -> Void) {
        attempts += 1
        let delay = min(pow(2.0, Double(max(0, attempts - 1))), 30.0)
        let work = DispatchWorkItem(block: block)
        pendingWorkItem?.cancel()
        pendingWorkItem = work
        queue.asyncAfter(deadline: .now() + delay, execute: work)
    }
}
