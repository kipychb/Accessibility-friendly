# 台北通無障礙語音助理

給手部操作受限、希望少點幾次或以中文口述的人使用。後端只查服務清單內的主題。即時到站使用臺北市公共運輸處的開放資料，其餘主題提供官方資訊、確認提示及服務入口。

## 交接檔案

- [API 文件與範例](docs/API.md) · [OpenAPI 3.1](docs/openapi.yaml)
- [官方服務資料清單](src/main/resources/services.json) · [來源與範圍](docs/SOURCES.md)
- [Postman 集合](postman/TownPass.postman_collection.json)
- [Flutter／TownPass WebView 串接](docs/TOWNPASS.md)

## 本機啟動

使用 JDK 17、Maven 3.6.3 以上及 Node.js 24。在此目錄執行以下指令，開啟 <http://localhost:8080>。

```powershell
npm.cmd --prefix web ci
npm.cmd --prefix web run build
mvn.cmd clean package
java -jar target/assistant-1.0.0.jar --server.address=127.0.0.1
```

已取得封裝好的 JAR 時，只需執行最後一行。在 Windows 重新打包前，先停止執行中的 JAR。Linux 或 macOS 使用 `npm` 和 `mvn`。Web 和查詢 API 由同一個 Spring Boot 服務提供，Spring MVC 負責 HTTP，Hibernate 將清單載入 H2 的 `official_services` 資料表。H2 啟動時由版控內的 JSON 重建。查詢文字、語音及陳情草稿不寫入資料庫。

## Docker

```sh
docker build -t townpass-assistant .
docker run --rm -p 127.0.0.1:8080:8080 townpass-assistant
```

Docker 建置包含 Vue 編譯、Java 測試與 JAR 封裝。公車查詢需要執行環境可對官方資料站連線。部署給其他裝置使用時，請在服務前提供 HTTPS。

本機若使用 Ubuntu WSL 的 Docker，請在此目錄從 PowerShell 執行 `wsl -d Ubuntu-22.04 -u root -- docker build -t townpass-assistant .`。若已有 JAR 佔用 Windows 的 8080，執行容器時可改用 `-p 127.0.0.1:18081:8080`。

## 驗證與展示

- `mvn test` 使用本機 gzip fixture 模擬官方格式，驗證資料表、路線站牌配對、方向、陳情草稿和輸入驗證。新鮮度測試使用固定時鐘。
- 啟動應用後匯入 Postman 集合，或執行 `npx --yes newman@6 run postman/TownPass.postman_collection.json`。其中「公車即時到站」要求外部資料可用且新鮮，實際預估秒數不固定。
- 以隔離的 Chrome 測試視窗開啟 DevTools Protocol 後，可執行 `npm --prefix web run test:smoke`。預設連線到本機 9222 連接埠並測試 8080 的服務。此測試模擬語音逐字稿，涵蓋語音按鈕、文字輸入、陳情補問及官方連結。
- Demo 可說「我想申請敬老卡」、「我的虛擬卡證在哪裡看」或「人行道破損，輪椅很難通過」；問「307公車幾點來」會回答台北通沒有提供公車到站（範圍見 docs/SOURCES.md）。回覆都有文字，支援環境也可播放。
- 語音辨識與語音播放由瀏覽器提供。若不支援、無權限或網路失效，使用文字框或系統鍵盤的語音輸入。WebView 的語音支援須依宿主及裝置驗證。
