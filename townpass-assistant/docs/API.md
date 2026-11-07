# 查詢 API

同源根網址為 `http://localhost:8080`。JSON 使用 UTF-8。OpenAPI 可匯入支援 3.1 的工具。

| 方法 | 路徑 | 用途 |
|---|---|---|
| GET | `/api/v1/services` | 傳回服務分類、常用問法別名、資料類型、機關、官方入口及來源 |
| POST | `/api/v1/query` | 將中文逐字稿和選用的對話上下文轉為可顯示、可朗讀的回覆 |

```json
{"text":"一號公車華江站往松仁路幾點來"}
```

用戶端每次可傳 1 到 400 個 UTF-16 單位的 `text`。回覆的 `speech` 是要顯示和朗讀的文字。只有查詢相關的欄位會出現。

| type | 前端處理 |
|---|---|
| `ANSWER` | 顯示 `speech`、`arrivals` 和官方資料時間 `updatedAt`。 |
| `CLARIFY` | 朗讀補問，將回覆中的 `context` 隨下一句送回。有 `choices` 時也可把選項的 `context` 搭配 `text: "確認站牌"` 送回。 |
| `GUIDANCE` | 顯示官方連結。若有 `draft`，顯示描述及地點供確認。 |
| `NO_RESULT` | 提示核對路線、站牌或方向，並提供官方查詢。 |
| `UPSTREAM_UNAVAILABLE` | 暫時無法取得新鮮的上游資料，提供大臺北公車連結。 |
| `OUT_OF_SCOPE` | 介紹可查的主題。 |

上述業務結果皆回傳 HTTP 200。空白文字、過長文字、不合法的 JSON 或方向回傳 HTTP 400。切換主題或開啟新對話時，前端可省略 `context`。伺服器沒有對話 session。

## 公車追問

第一句 `{"text":"一號公車幾點來"}` 得到補問和 `{"route":"1"}`。接著送 `{"text":"華江站往松仁路","context":{"route":"1"}}`。公車名稱以官方路線名稱為準，支援常見的數字路線與「一號」等中文說法。站牌使用官方完整名稱，方向可用「去程」「返程」或「往」加官方端點名稱。

每筆 `arrivals` 提供 `routeId`、`route`、`stopId`、`stop`、`direction`、`destination`、`status`。方向 0 表示去程、1 表示返程。只有可用秒數才會有 `estimateSeconds`。官方時間按臺北時區解析，傳回 UTC ISO 8601。

| status | 意義 |
|---|---|
| `DUE` | 官方回傳 0 秒，即將到站 |
| `ESTIMATED` | 正數秒，語音以向上取整的分鐘回答 |
| `NOT_DEPARTED` | 尚未發車 |
| `SKIPPED` | 交管不停靠 |
| `LAST_PASSED` | 末班已過 |
| `NO_SERVICE` | 今日未營運 |
| `NO_ESTIMATE` | 官方沒有可用估值 |

到站屬於預估。當同一站有多筆可用估值時回傳最近一班。路線、站牌快取六小時，到站快照二十秒。超過三分鐘的到站資料不會用來報時間。

## 道路陳情

第一句 `{"text":"人行道破損，輪椅很難通過"}` 得到地點補問。把該回覆的 `context` 原樣帶回，再傳 `text: "民生東路三段，靠近公園的路口"`。後端回傳 `draft.description`、`draft.location` 及官方入口。送件由使用者在官方網站完成。官方連結不附帶逐字稿或地址參數。
