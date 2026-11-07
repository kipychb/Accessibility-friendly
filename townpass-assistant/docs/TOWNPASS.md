# Flutter／TownPass 串接

將 Vue 網頁與後端部署在同一個 HTTPS 網域。團隊在 TownPass 的 `my_service_model.dart` 將新服務的 `destinationUrl` 設為部署後的根網址即可沿用現有的 Flutter WebView 路由。在自訂 Flutter Widget 的點擊回呼，也可呼叫現有 Dart 方法。

```dart
await TPRoute.openUri(
  uri: assistantHttpsUrl,
  forceTitle: '台北通無障礙語音助理',
);
```

Vue 持有當輪對話狀態，利用同源 HTTP 呼叫 Spring API。Flutter 不需要傳入台北通帳號、會員 token 或 GPS。正式部署網址由團隊決定，請先在模擬器與實機驗證文字查詢、語音支援、官方連結開啟與返回鍵。

瀏覽器可用時，示範頁用 zh-TW Web Speech 辨識與朗讀。Android WebView 的 Web Speech 支援與 Chrome 可能不同。示範頁會保留文字輸入；若團隊需要原生錄音辨識，需在 Flutter 宿主另行接入麥克風權限與語音橋接，再把逐字稿送至相同查詢 API。
