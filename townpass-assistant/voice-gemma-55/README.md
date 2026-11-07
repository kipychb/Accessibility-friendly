# 臺北通 Gemma 55 項服務解析模組

此資料夾為獨立的繁體中文 NLU 解析模組，供既有 townpass-assistant 整合；**不會覆寫 Java 後端或 Vue 前端**。

服務 ID 以 `../src/main/resources/services.json` 為唯一來源，並非固定六項白名單。

## Node 用法

```js
const {createParser} = require('./src/gemma-parser');
const services = require('../src/main/resources/services.json');
const parser = createParser(services);
const systemPrompt = parser.buildSystemPrompt(); // 交給 Gemma 作為 System Prompt
const parsed = parser.normalizeResult(gemmaOutput, userText);
console.log(parsed, parser.clarification(parsed));
```

## 測試

```bash
node test/gemma-test.js
```

## 整合注意

- 模組不會自行呼叫 Gemma；須由模型整合者取得模型輸出，再呼叫 normalizeResult。
- Java `POST /api/v1/query` 接受 `{text, context}`，**不直接接受解析器 JSON**。
- `ANSWER` 只表示槽位足夠，並非公車 ETA 已查到或陳情已送出。
- 多路線比較需要既有 adapter 的雙次查詢或後端另行擴充。
- 55 項測試只驗證服務 ID 及格式邏輯，不代表模型的真實辨識準確率。
- 離線環境應預先封裝 services.json，不可依賴網路取得清單。
