# 臺北通語音市政服務助手

2026 臺北秋季程式設計節黑客松作品（團隊：城市解法）。

在臺北通（TownPass）裡用說話查詢臺北通既有的市政服務，讓手部不便、不方便打字或點選的市民也能使用。
流程：說話 → 語音轉文字 → 問題理解 → 後端查官方服務資料 → 畫面字幕＋語音朗讀。

## 範圍原則

- **只回答臺北通有的服務**（目前 51 項，分為會員、卡證、帳務、優惠、市政服務、訊息、地圖與生活、交通）。
- 臺北通沒有的服務（例如公車到站、無障礙計程車、道路施工）一律回答「台北通目前沒有提供」，並提供 1999 市民熱線一鍵撥號。
- 答案一律來自官方資料整理的服務清單，**不由 AI 產生**。個人資料、付款、申辦、陳情送件都導向官方服務，本助手不代為送出。
- 範圍判定與停用原因見 [townpass-assistant/docs/SOURCES.md](townpass-assistant/docs/SOURCES.md)。

## 架構

| 元件 | 位置 | 說明 |
|---|---|---|
| 前端（最終版） | [`index.html`](index.html) | 單一網頁，在 TownPass WebView 內執行；語音、字幕、朗讀、設定、服務清單 |
| 後端 | [`townpass-assistant/`](townpass-assistant/) | Spring Boot：`POST /api/v1/query`（規則式意圖比對＋官方資料）、`GET /api/v1/services` |
| 服務資料 | `townpass-assistant/src/main/resources/services.json` | 每筆服務的機關、官方入口、來源與範圍依據 |
| TownPass 原生修改 | [`device-bridge/townpass/`](device-bridge/townpass/) | `voice`（語音辨識＋朗讀）與 `llm`（裝置端 Gemma）兩個 Handler，`voice-handler.patch` 以 `git am` 套用 |
| Gemma 提示詞 | `townpass-assistant/voice-gemma-55/src/gemma-parser.js` | 裝置端模型的意圖分類提示與結果檢查 |

查詢順序：先用原句查後端（規則式，約 1 秒）；後端判斷為範圍外時，才交給裝置端 Gemma 做意圖分類，再用分類結果重新查後端。

## 執行方式

完整步驟（模擬器、編譯後端、部署前端、套用 TownPass 修改、放入模型）見 [HANDOFF.md](HANDOFF.md) 與
[device-bridge/townpass/README.md](device-bridge/townpass/README.md)。摘要：

```bash
# 後端（JDK 17 以上與 Maven）
cd townpass-assistant
mvn -B package
java -jar target/assistant-1.0.0.jar --server.address=127.0.0.1
```

```bash
# 測試（run-natural 需後端在 127.0.0.1:8080 執行中）
cd townpass-assistant
mvn test
node qa/run-natural.mjs
```

## 如何測試

本助手要在 **台北通（TownPass）App 裡**執行，並連到後端，才有完整功能。三種打開方式的差別：

| 打開方式 | 查詢 | 聽不懂的說法 | 語音辨識 |
|---|---|---|---|
| **TownPass App 內**（模擬器或手機，見 HANDOFF.md） | 後端 51 項服務，中英文 | 裝置端 Gemma | Android 系統語音服務，或裝置端 Whisper |
| **電腦瀏覽器＋後端**（下方指令） | 後端 51 項服務，中英文 | **沒有 Gemma** | **瀏覽器內建的語音辨識** |
| 直接用瀏覽器打開 `index.html` 檔案 | 只有內建 5 項、只認中文關鍵字（畫面上方會顯示「示範模式」） | 沒有 | 瀏覽器內建 |

用電腦瀏覽器測試時，**先啟動後端，再打開 <http://127.0.0.1:8080/>**，不要直接開檔案：

```bash
# 在 townpass-assistant 資料夾執行；static-locations 指向 repo 根目錄，提供 index.html 與相關檔案（只在本機 127.0.0.1）
cd townpass-assistant
mvn -B package
java -jar target/assistant-1.0.0.jar --server.address=127.0.0.1 --spring.web.resources.static-locations=file:../
```

- 打字查詢最準確；語音要先在設定選好語言（選 English 才能用英文說），因為語音辨識的語言跟著設定，不會自動判斷。
- 瀏覽器裡**沒有裝置端 Gemma 和 Whisper**（這兩個只在 TownPass App 內），規則比對不到的說法會回答範圍外並提供 1999。
- 如果在瀏覽器裡關掉網路後仍辨識出文字，那是瀏覽器自己的語音辨識，與本專案的 Whisper 無關；要驗證離線請在 TownPass App 內測試。

## 資料來源

- 服務內容依臺北市政府官方網站與臺北通官方說明（[id.taipei](https://id.taipei/tpcd/about/faq) 等）整理；每筆服務的機關（`authority`）、官方入口與來源網址記錄在 `services.json`，回答底部會標示「資料來源：機關名稱」。
- 比賽版 TownPass 的服務清單：[taipei-doit/TownPass `my_service_model.dart`](https://github.com/taipei-doit/TownPass/blob/main/lib/page/city_service/model/my_service_model.dart)。
- 來源清單、核對日期與停用服務原因：[townpass-assistant/docs/SOURCES.md](townpass-assistant/docs/SOURCES.md)。
- 英文回答用的 `service-en.json`（服務說明英文版）與 `townpass-assistant/src/main/resources/aliases-en.json`（英文問法別名）為**開發階段預先撰寫的固定資料，不是執行時由 AI 即時產生**。

## 裝置端模型揭露

| 項目 | 內容 |
|---|---|
| 模型 | Gemma 4 E4B（Google），`google/gemma-4-E4B-it-qat-q4_0-gguf`，檔案 `gemma-4-E4B_q4_0-it.gguf` |
| 版本／量化 | instruction-tuned，QAT q4_0，GGUF 格式（5,154,941,280 bytes） |
| 推論框架 | llama.cpp，經 Flutter 套件 `llamadart` 0.18.0（MIT），CPU 推論 |
| 來源 | <https://huggingface.co/google/gemma-4-E4B-it-qat-q4_0-gguf> |
| 授權 | Apache-2.0 |
| 網路 | 完全在裝置上離線執行，不呼叫任何雲端模型 |
| 用途 | **只做意圖分類**（輸出服務代碼，以 GBNF 文法限制為 JSON），答案一律來自後端官方資料 |

語音辨識：

- **有網路時**使用 Android 系統語音服務，經 `speech_to_text` 7.5.0（BSD-3-Clause）呼叫。
- 可在設定頁切換為**裝置端 Whisper**：Whisper base（`ggml-base.bin`，OpenAI，MIT），經 `whisper_ggml` 2.9.0（whisper.cpp，MIT）在裝置上離線辨識；沒有網路時也會自動改用。
- 朗讀使用 `flutter_tts` 4.2.5（MIT，Android 系統 TTS）。
- 其他相依套件授權見 [device-bridge/townpass/README.md](device-bridge/townpass/README.md)。

## 授權

本專案以 GNU AGPL v3 授權釋出，全文見 [LICENSE](LICENSE)。

臺北通語音市政服務助手 — 以語音查詢臺北通既有服務，協助手部不便者使用。
Copyright (C) 2026 城市解法（廖鈺瑄、吳書慧、卓恩多、陳柏妤、陳欣怡）
This program is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more details. You should have received a copy of the GNU Affero General Public License along with this program. If not, see <https://www.gnu.org/licenses/>.
