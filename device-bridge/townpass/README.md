# TownPass 原生語音修改

`voice-handler.patch` 是對 [taipei-doit/TownPass](https://github.com/taipei-doit/TownPass)
main（commit `c8e5d4d`）的修改；`tp_voice_message_handler.dart` 是其中主要檔案的副本，方便閱讀。

## 套用

```bash
git clone https://github.com/taipei-doit/TownPass.git
cd TownPass
git checkout -b feature/voice-handler
git am ../Accessibility-friendly/device-bridge/townpass/voice-handler.patch
flutter pub get
dart run build_runner build --delete-conflicting-outputs
flutter run
```

（路徑依你 clone 的位置調整。用 fvm 的話在 flutter／dart 前加 `fvm`。）

## 修改內容

| 檔案 | 內容 |
|---|---|
| `lib/util/web_message_handler/tp_voice_message_handler.dart` | 新增 `voice` Handler（STT／TTS） |
| `lib/util/web_message_handler/tp_web_message_listener.dart` | 註冊 `VoiceMessageHandler` |
| `lib/page/city_service/model/my_service_model.dart` | 新增「語音助理」服務入口 |
| `lib/page/city_service/widget/pinned_service_widget_controller.dart` | 釘選到「我的服務」第一格 |
| `android/app/src/main/AndroidManifest.xml` | RECORD_AUDIO、INTERNET、語音服務 queries |
| `android/app/src/debug/AndroidManifest.xml` | debug 允許 http 本機測試頁 |
| `ios/Runner/Info.plist` | 麥克風、語音辨識使用說明 |
| `pubspec.yaml` | speech_to_text 7.5.0、flutter_tts 4.2.5 |

## 指定前端網址

預設開啟 `http://localhost:8000/voice-test.html`（需 `adb reverse tcp:8000 tcp:8000`）。
正式網址：

```bash
flutter run --dart-define=VOICE_ASSISTANT_URL=https://你的網址/
```
