# 裝置語音整合狀態（2026-11-07 更新）

## 已驗證（Pixel_8 模擬器，Android 37，TownPass debug build）
- TownPass「我的服務 → 語音助理」開啟測試頁，`flutterObject` 可用。
- 網頁 `startListening()` → 原生中文辨識 → `speechResult`（含 `isFinal:true`）回到網頁。
- 「307公車還有多久到」辨識正確（dictation 模式；search 模式曾誤聽為「杉林溪」）。
- 網頁先顯示字幕再 `speak()` → 原生朗讀，`speakingStarted`／`speakingCompleted` 回傳；朗讀後字幕保留，喇叭實際聽到聲音。

## 前端整合（townpass-assistant/web）
- 新增 `src/voice.js`：App 內走 TownPass 原生語音，一般瀏覽器沿用 Web Speech（smoke 測試相容）。
- `App.vue`：語音改用 voice.js；回覆顯示後自動朗讀；辨識錯誤顯示原生中文訊息並聚焦文字框。
- 模擬器實測：說「一號公車華江站往松仁路幾點來」→ final → `/api/v1/query` 真實到站資料 → 字幕 → 自動朗讀 ✅
- 本機測試：後端 `java -jar assistant-1.0.0.jar --server.address=127.0.0.1`，`adb reverse tcp:8080 tcp:8080`，
  TownPass 以 `--dart-define=VOICE_ASSISTANT_URL=http://localhost:8080/` 編譯。
- 待後端／問題理解處理：「307公車還有多久到」後端未辨識路線 307（context.route 為空）。
- 注意：repo 缺 `.gitignore`，`web/node_modules`、`web/dist`、`target/` 勿 commit。

## 第五階段測試（模擬器）
| 情境 | 結果 |
|---|---|
| 拒絕麥克風權限 | ✅ 回傳 `permission_denied`，畫面顯示中文提示，App 不當機 |
| 斷網：朗讀（TTS） | ✅ 可離線朗讀（Google TTS 本機 zh-TW 語音） |
| 斷網：字幕 | ✅ 由網頁顯示，不需網路 |
| 斷網：辨識（STT） | ⚠️ 失敗，回傳 `error_network`，顯示「語音服務需要網路」 |
| 朗讀中關閉頁面 | ✅ `stopAll` 停止朗讀，不在背景繼續 |

## 語音辨識改為混合模式（2026-11-07 晚）
- 有網路：Google（Android 系統語音服務），實測「一號公車華江站松仁路幾點來」約 1 秒、正確 ✅
- 離線：Whisper base（whisper.cpp），實測自動改用、說完自動結束、可辨識 ✅；
  但模擬器上需約 10–40 秒，且準確度較差（「松人路及點來」）⚠️
- 模擬器 x86_64 需套 `device-bridge/townpass/patch-whisper-x86.ps1`（AVX2）；ARM 手機不需要
- 模擬器 AVD 已調為 RAM 8 GB（備份 `config.ini.bak-20261107`）；儲存仍 10 GB（需 wipe data 才擴充）
- 待主辦方確認：有網路時使用 Google 語音服務是否符合規範
- LLM：Gemma 4 E4B（Apache 2.0，q4_0 GGUF）已在模擬器 TownPass 內離線執行 ✅
  規則優先、聽不懂才問 Gemma；預熱約 135 秒，之後每句約 10 秒；GBNF 文法保證 JSON
  英文「Where can I apply for a senior card」→ senior-card → 正確答案 ✅（答案仍為中文）
- 模擬器已 wipe data，RAM 8 GB／data 24 GB

## 架構
- 部署：微服務網頁在 TownPass WebView；語音由 TownPass 自訂 Handler（name: `voice`）提供。
  Android WebView 不支援瀏覽器 SpeechRecognition，前端必須改用 `device-bridge/townpass-voice.js`。
- 原生程式：TownPass 本機分支 `feature/voice-handler`
  （`C:\Users\ASUS\Documents\Codex\Projects\TownPass`）
  - `lib/util/web_message_handler/tp_voice_message_handler.dart`
  - 註冊於 `tp_web_message_listener.dart`；「語音助理」入口於 `my_service_model.dart`
  - Android：RECORD_AUDIO、INTERNET、語音服務 queries；debug 允許 http 測試頁。iOS：麥克風／語音辨識說明文字。
- 套件：speech_to_text 7.5.0（BSD-3-Clause）、flutter_tts 4.2.5（MIT）。不使用 LLM。

## 已知問題與處理
- Google 語音服務結束時常只回 `error_client`、不送 final：原生端以最後一次非空文字補送 final。
- 空白中間結果已過濾。
- 模擬器收音：Windows 麥克風須未靜音；模擬器以 `-allow-host-audio` 啟動（`voice_probe/start_voice_emulator.bat`）。

## 未完成
- 辨識需連網（Google 線上辨識）；離線繁中語言包未安裝。展示時須確保網路，或準備文字輸入備案。
- 實體 Android 手機測試（目前只有模擬器）。
- 前端兩套原型尚未改用 townpass-voice.js；正式網址需以 `--dart-define=VOICE_ASSISTANT_URL=` 指定。
- 待主辦方確認：Android 系統 Google 語音服務是否視為商用第三方模組。
- TownPass 修改尚未 commit／推送到團隊可取得的位置。
