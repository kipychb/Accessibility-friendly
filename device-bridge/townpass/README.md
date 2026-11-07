# TownPass 原生語音修改

`voice-handler.patch` 是對 [taipei-doit/TownPass](https://github.com/taipei-doit/TownPass)
main（commit `c8e5d4d`）的修改；`tp_voice_message_handler.dart` 是其中主要檔案的副本，方便閱讀。

## 語音辨識（混合）

| 情況 | 引擎 | 備註 |
|---|---|---|
| 有網路，且裝置有系統語音服務 | Android 系統語音服務（Google），經 `speech_to_text` | 約 1 秒，即時字幕 |
| 離線，或裝置沒有 Google 服務 | Whisper base，經 `whisper_ggml`（whisper.cpp）在裝置上執行 | 說完再辨識；模擬器上需數十秒，ARM 手機較快 |

- Whisper 模型（`ggml-base.bin`，約 148 MB）**需事先下載一次**，之後完全離線（見下方「預先下載」）。
- 說完停頓約 1.2 秒自動結束收音；最長 10 秒。
- 朗讀：`flutter_tts`（Android 系統 TTS），離線可用。
- 本 Handler 不使用 LLM。

## 套用

```bash
git clone https://github.com/taipei-doit/TownPass.git
cd TownPass
git checkout -b feature/voice-handler
git am ../Accessibility-friendly/device-bridge/townpass/voice-handler.patch
flutter pub get
dart run build_runner build --delete-conflicting-outputs
```

**用 Android 模擬器（x86_64）展示時**，先套用 AVX2 補丁再編譯，否則 Whisper 會非常慢：

```bash
powershell -ExecutionPolicy Bypass -File ../Accessibility-friendly/device-bridge/townpass/patch-whisper-x86.ps1
```

```bash
flutter build apk --debug --target-platform android-x64 --dart-define=VOICE_ASSISTANT_URL=http://localhost:8080/
```

（路徑依你 clone 的位置調整。用 fvm 的話在 flutter／dart 前加 `fvm`。實體 ARM 手機不需要補丁。）

## 預先下載 Whisper 模型

模型只在走 Whisper 時才下載，但 App 判斷「有網路」就會走 Google。最簡單的做法是直接推送模型檔：

```bash
curl -L -o ggml-base.bin https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.bin
```

```bash
adb push ggml-base.bin /sdcard/Download/
adb shell run-as com.example.townpass cp /sdcard/Download/ggml-base.bin files/ggml-base.bin
```

## 修改內容

| 檔案 | 內容 |
|---|---|
| `lib/util/web_message_handler/tp_voice_message_handler.dart` | `voice` Handler：混合 STT、TTS、離開頁面停止 |
| `lib/util/web_message_handler/tp_web_message_listener.dart` | 註冊 `VoiceMessageHandler` |
| `lib/util/tp_web_view.dart` | `VoiceLifecycleGuard`：關閉頁面／進背景時停止收音與朗讀 |
| `lib/page/city_service/model/my_service_model.dart` | 新增「語音助理」服務入口 |
| `lib/page/city_service/widget/pinned_service_widget_controller.dart` | 釘選到「我的服務」第一格 |
| `android/app/src/main/AndroidManifest.xml` | RECORD_AUDIO、INTERNET、語音服務 queries |
| `android/app/src/debug/AndroidManifest.xml` | debug 允許 http 本機頁面 |
| `ios/Runner/Info.plist` | 麥克風、語音辨識使用說明 |
| `pubspec.yaml` | speech_to_text 7.5.0、flutter_tts 4.2.5、whisper_ggml 2.9.0、record 7.1.2 |

## 授權

| 元件 | 授權 |
|---|---|
| speech_to_text | BSD-3-Clause |
| flutter_tts | MIT |
| whisper_ggml / whisper.cpp | MIT |
| Whisper 模型（OpenAI） | MIT |
| record | BSD-3-Clause |
| ffmpeg_kit_flutter_new_min（whisper_ggml 相依） | LGPL-3.0 |
| Android 系統語音服務（Google） | 閉源系統服務、免費；是否符合競賽規範待主辦方確認 |

## 指定前端網址

```bash
flutter run --dart-define=VOICE_ASSISTANT_URL=https://你的網址/
```
