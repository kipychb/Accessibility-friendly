## 摘要
在臺北通（TownPass）App 的 WebView 裡，瀏覽器的 SpeechRecognition 不能用，麥克風按了沒反應。這個 PR 讓前端改走 TownPass 原生語音（中文辨識＋朗讀），並把兩套前端統一成一套。

## 內容
- `townpass-assistant/web`（恩多的 Vue 前端）作為唯一前端：
  - 新增 `src/voice.js`：App 內走 TownPass 原生語音；一般瀏覽器仍走 Web Speech（smoke 測試相容）
  - 回覆顯示後自動朗讀；辨識錯誤顯示中文提示並聚焦文字框
  - 套用 `TownPass-Front-end` 的版面：填滿 WebView、頂部固定「回到首頁」、移除底部導覽、首頁「您好，想查什麼？」、按下填色
- `device-bridge/`：語音介面 `townpass-voice.js`、測試頁、說明
- `device-bridge/townpass/voice-handler.patch`：TownPass 原生 `voice` Handler（speech_to_text 7.5.0 / flutter_tts 4.2.5，不使用 LLM），可 `git am` 套用到 taipei-doit/TownPass main
- `voice_probe/`：獨立語音測試 App 與模擬器啟動檔
- `DEVICE_INTEGRATION.md`：已驗證項目與已知問題

## 測試（Pixel_8 模擬器，TownPass 內）
- [x] 說「一號公車華江站往松仁路幾點來」→ 真實到站資料 → 字幕 → 自動朗讀
- [x] 範例「我要申請敬老卡」→ 回答 → 自動朗讀；按「回到首頁」停止朗讀
- [x] 拒絕麥克風權限 → 中文提示
- [x] 斷網 → 朗讀可用，辨識顯示「需要網路」
- [x] 朗讀中關閉頁面 → 停止
- [ ] 實體手機

## 請組員注意
- 之後請只改 `townpass-assistant/web`；語音請透過 `src/voice.js`，不要直接用瀏覽器語音 API
- 前端 A 的範例「臺北通怎麼登入？」後端回 OUT_OF_SCOPE，改保留「附近有無障礙設施嗎？」
- 後端未辨識「307公車還有多久到」的路線（context.route 為空）
- repo 缺 `.gitignore`，`target/` 已被 commit
- 「按住說話」實際是點一下即可開始，建議文字改為「點一下說話」，避免手部不便的使用者以為要長按

🤖 Generated with [Claude Code](https://claude.com/claude-code)
