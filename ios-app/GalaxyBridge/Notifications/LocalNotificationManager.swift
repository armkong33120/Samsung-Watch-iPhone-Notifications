import UserNotifications

final class LocalNotificationManager {
    func requestAuthorization() {
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound]) { _, _ in }
    }

    func showLocalTestNotification() {
        let content = UNMutableNotificationContent()
        content.title = "Galaxy Bridge"
        content.body = "Local test event sent to the watch"
        let request = UNNotificationRequest(identifier: UUID().uuidString, content: content, trigger: nil)
        UNUserNotificationCenter.current().add(request)
    }
}
