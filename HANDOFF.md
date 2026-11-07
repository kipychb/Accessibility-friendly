# 交接文件：臺北通語音市政服務助手（裝置整合）

給接手的 AI／組員：照這份文件就能重建環境並繼續工作。最後更新：2026-11-07 23:xx。

## 專案目標（摘要）
臺北通（TownPass）內的語音市政服務助手，服務手部不便者。流程：
說話 → STT → 問題理解（規則；聽不懂才用裝置端 Gemma）→ 後端查官方資料 → 字幕＋TTS。
競賽規則：LLM 必須完全在裝置端執行；只能用開源套件；答案不可由 LLM 編造。

## 程式碼位置
| 位置 | 內容 |
|---|---|
| 團隊 repo `kipychb/Accessibility-friendly`，分支 **`device/whisper-offline`** | 最新的所有修改（尚未合併進 main） |
| `index.html`（repo 根目錄） | **最終前端**。已接 TownPass 原生語音、`/api/v1/query`、Gemma、方向選項按鈕 |
| `townpass-assistant/` | Spring Boot 後端（恩多）＋ Vue 前端（已不使用） |
| `townpass-assistant/voice-gemma-55/` | Jassline 的 Gemma 解析模組（`gemma-parser.js`），`index.html` 會載入 |
| `device-bridge/townpass/voice-handler.patch` | 對 `taipei-doit/TownPass` main 的修改（voice + llm Handler），`git am` 套用 |
| `device-bridge/townpass/README.md` | TownPass 修改說明、模型放置、授權 |
| `device-bridge/townpass/patch-whisper-x86.ps1` | 模擬器用：whisper_ggml 開 AVX2 |
| `DEVICE_INTEGRATION.md` | 驗證紀錄與已知問題 |
| 本機 TownPass：`C:\Users\ASUS\Documents\Codex\Projects\TownPass`，分支 `feature/voice-handler` | 修補檔的來源（已 commit） |

## 環境（Windows）
- Flutter 3.47.6（fvm）、JDK（Android Studio jbr）、Maven：`C:\Users\ASUS\tools\apache-maven-3.9.11`、Node 24
- 模型檔：`C:\Users\ASUS\tools\models\`（`gemma-4-E4B_q4_0-it.gguf` 4.9 GB、`ggml-base.bin` 148 MB）
- 模擬器 AVD `Pixel_8`：RAM 8 GB、data 24 GB（設定備份 `config.ini.bak-20261107`）
- 模擬器啟動：`voice_probe\start_voice_emulator.bat`（含 `-allow-host-audio`；Windows 麥克風不可靜音）

## 從零跑起來（依序）
1. 啟動模擬器：雙擊 `voice_probe\start_voice_emulator.bat`
2. 編譯後端（OneDrive 會鎖 target，所以複製到暫存資料夾再編）：
   ```powershell
   robocopy townpass-assistant $env:TEMP\backend /MIR /XD node_modules target
   cd $env:TEMP\backend; C:\Users\ASUS\tools\apache-maven-3.9.11\bin\mvn.cmd -B package
   ```
3. 準備前端資料夾（後端只提供這個資料夾，避免公開整個 repo）：
   把 `index.html` 和 `townpass-assistant\voice-gemma-55\src\gemma-parser.js`
   複製到 `$env:TEMP\frontend\`（後者放在 `townpass-assistant\voice-gemma-55\src\` 子路徑）
4. 啟動後端並提供前端：
   ```powershell
   java -jar $env:TEMP\backend\target\assistant-1.0.0.jar --server.address=127.0.0.1 "--spring.web.resources.static-locations=file:<TEMP>/frontend/,classpath:/static/"
   ```
5. TownPass：`device-bridge/townpass/README.md` 的「套用」步驟；模擬器先跑 `patch-whisper-x86.ps1`，再
   `flutter build apk --debug --target-platform android-x64 --dart-define=VOICE_ASSISTANT_URL=http://localhost:8080/`
   `adb install -r build\app\outputs\flutter-apk\app-debug.apk`
6. 放模型（Git Bash，App 需先開過一次）：
   `adb exec-in "run-as com.example.townpass sh -c 'cat > files/gemma-4-E4B_q4_0-it.gguf'" < gemma-4-E4B_q4_0-it.gguf`
   （`ggml-base.bin` 同法）
7. `adb reverse tcp:8080 tcp:8080`；App →「服務」→「語音助理」。開頁後約 2.5 分鐘 Gemma 才預熱完成。

## 目前狀態（已驗證，模擬器）
- 語音：有網路用 Google（約 1 秒）；離線用 Whisper（10–40 秒）✅
- 朗讀、字幕、拒絕權限、斷網、離開頁面停止 ✅
- 規則式查詢約 1 秒；後端 `OUT_OF_SCOPE` 才交給 Gemma（預熱後約 10 秒）✅
- 英文「Where can I apply for a senior card」→ Gemma → senior-card → 中文官方答案 ✅
- 後端已修：「307公車」「307路」可抽路線；站名寬鬆比對（去「捷運」、去括號）；
  找不到站牌時舉例；12 個測試通過
- 前端已加：後端回 `choices`（多方向）時顯示按鈕

## 待辦（依優先順序）
1. ~~307 路線解析~~ ✅、補問可直接說終點名稱 ✅、補問朗讀完自動收音 ✅ → **模擬器實測**（例：「307公車西門站還有多久到」→ 說「撫遠街」）
2. 服務範圍外（OUT_OF_SCOPE）提供 1999 一鍵撥號與官方聯絡方式（TownPass 已內建 `phone_call`／`1999agree` Handler）
3. 中文系統性測試：55 項服務各 2–3 種自然問法，修規則或別名（例：「身心障礙證明」對不到 disability-card）
4. 離線模式開關（強制 Whisper），因無法向主辦方確認 Google 語音是否合規
5. 英文問題用英文回答（Gemma 在裝置上翻譯後端答案、英文 TTS）
6. 建 PR：`device/whisper-offline` → main
7. 實體手機測試

## 注意事項
- 不要 commit `node_modules`、`dist`、`target`（已有 `.gitignore`；`townpass-assistant/target/` 仍被追蹤，未處理）
- `run-as` 讀不到 `/sdcard`，放檔要用 `adb exec-in ... cat >`
- Git Bash 會把 `/sdcard/...` 轉成 Windows 路徑，adb 刪檔請用 PowerShell
- 每次重開模擬器後要重做 `adb reverse tcp:8080 tcp:8080`
- 後端跑在 127.0.0.1:8080；重新編譯前要先停掉舊的 java 程序
