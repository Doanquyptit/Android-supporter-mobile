# IHUB Android Remote Agent

Phase 0 Android Agent cho remote control PoC.

## Trạng thái hiện tại

- Source skeleton đã được scaffold.
- Máy phát triển hiện tại chưa có Android SDK / adb / Gradle wrapper khả dụng, nên project chưa được build tại local này.

## Những gì đã có

- `MainActivity`: nhập `deviceId`, `deviceName`, `websocketUrl`, mở Accessibility Settings, xin quyền MediaProjection.
- `ScreenCaptureService`: foreground service, tạo MediaProjection, capture frame, nén JPEG base64 và gửi WebSocket.
- `RemoteAccessibilityService`: nhận tap/swipe qua gesture API.
- `RemoteWebSocketClient`: đăng ký `DEVICE_REGISTER`, gửi `SCREEN_FRAME`, nhận `COMMAND`, trả `COMMAND_RESULT`.

## Cần chuẩn bị để chạy

1. Cài Android Studio hoặc Android SDK command-line tools.
2. Cài platform/compile SDK 35.
3. Tạo Gradle wrapper hoặc mở project bằng Android Studio để sync.
4. Chỉnh `DEFAULT_WEBSOCKET_URL` trong `MainActivity.kt` theo IP backend trong LAN.
5. Bật Accessibility Service thủ công trên thiết bị test.
6. Chạy app, xin quyền capture màn hình, rồi kết nối tới `/ws/remote`.
# Android-supporter-mobile
