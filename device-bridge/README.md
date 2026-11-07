# TownPass 語音橋接（前端使用說明）

在臺北通 App 的 WebView 裡，**瀏覽器的 `SpeechRecognition` 不能用**（Android WebView 不支援）。
請改用本資料夾的 `townpass-voice.js`，它會透過 TownPass 的 `flutterObject` 呼叫 App 原生語音。

## 引入

```html
<script src="townpass-voice.js"></script>
```

## 呼叫（網頁 → App）

| 函式 | 作用 |
|---|---|
| `TownPassVoice.isAvailable()` | 是否在臺北通 App 內；`false` 時請顯示文字輸入 |
| `TownPassVoice.startListening()` | 開始收音（第一次會跳出麥克風權限） |
| `TownPassVoice.stopListening()` | 停止收音，已說的內容會送出 final 結果 |
| `TownPassVoice.speak(text)` | 朗讀文字（最多 1000 字） |
| `TownPassVoice.stopSpeaking()` | 停止朗讀 |

## 事件（App → 網頁）

```js
TownPassVoice.on('speechResult', ({ text, isFinal }) => { ... });
```

| 事件 | 資料 | 前端處理 |
|---|---|---|
| `listeningStarted` | — | 按鈕變「聆聽中」 |
| `listeningStopped` | — | 按鈕恢復 |
| `speechResult` | `text`, `isFinal` | `isFinal=false` 只更新字幕；`isFinal=true` 且 `text` 非空才送後端 `/api/v1/query` |
| `speechError` | `code`, `message` | 顯示 `message`（已是中文），並提供文字輸入 |
| `speakingStarted` | — | 可顯示「朗讀中」 |
| `speakingCompleted` | — | 朗讀結束 |
| `speakingStopped` | — | 使用者按了停止 |
| `soundLevel` | `level`（0–1） | 收音中的音量，約每 80 ms 一次，前端用來顯示麥克風波紋 |

## 規則

1. **先顯示完整回答字幕，再呼叫 `speak()`**。朗讀失敗不可清除字幕。
2. 同一句話只在 `isFinal=true` 時送後端一次。
3. 回答文字必須來自後端真實資料，不可前端自行生成。

## 底層訊息格式（給除錯用）

- 送出：`flutterObject.postMessage('{"name":"voice","data":{"action":"speak","text":"..."}}')`
- 收到：`{"name":"voice","data":{"event":"speechResult","text":"...","isFinal":true}}`

## 原生端

TownPass 分支 `feature/voice-handler`：`lib/util/web_message_handler/tp_voice_message_handler.dart`。
使用 `speech_to_text` 7.5.0（BSD-3-Clause）與 `flutter_tts` 4.2.5（MIT），不使用 LLM。
一般模式的辨識由 Android 系統 Google 語音服務處理，**需要網路**。

## 本機測試

```bash
cd device-bridge
python -m http.server 8000 --bind 127.0.0.1
adb reverse tcp:8000 tcp:8000
```

在 App 首頁點「語音助理」即開啟 `http://localhost:8000/voice-test.html`。
正式網址用 `flutter run --dart-define=VOICE_ASSISTANT_URL=https://...` 指定。
