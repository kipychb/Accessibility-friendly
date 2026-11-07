# 官方服務資料與範圍

來源核對日期為 2026-11-07。每筆服務的機關、官方入口及範圍依據在 [服務資料清單](../src/main/resources/services.json)，啟動時載入 `official_services`。

| 主題 | 做得到的事 | 官方來源 |
|---|---|---|
| 大臺北公車到站 | 路線、站牌與方向可查即時預估。此項是文字需求指定的市政交通延伸，資料來自公共運輸處。 | [資料集](https://data.taipei/dataset/detail?id=f11a5af0-7b37-48ef-98cc-f6f102ed43c6) · [欄位文件](https://pto.gov.taipei/News_Content.aspx?n=A1DF07A86105B6BB&s=55E8ADD164E4F579) |
| 道路陳情 | 輪椅通行受阻可整理描述與地點，再導向與台北通會員整合的 1999。 | [研考會說明](https://rdec.gov.taipei/News_Content.aspx?n=2FDEBE240FA2FAFC&s=2370F031FC488F0E&sms=F80C92E6A535E4E9) |
| 敬老與愛心卡 | 提供區公所洽辦及資格資訊入口。 | [敬老卡](https://dosw.gov.taipei/cp.aspx?n=4683CD9EE2EB68B1&s=48A233DC72F7F0CE) · [愛心卡](https://dosw.gov.taipei/cp.aspx?n=98EA840859DF5D7F) |
| 無障礙計程車 | 提供與敬老／愛心乘車相關的車隊查詢。 | [公共運輸處叫車方式](https://pto.gov.taipei/News_Content.aspx?n=885A55CFAE628CA9&s=E79356922050B5A4&sms=EDEE30A7FEECCE11) |
| 無障礙公廁 | 從台北通「找地點」相關情境導向市府公廁清單。沒有取得定位或即時開放資料。 | [台北通功能](https://id.taipei/tpcd/about/taipeipass-app) · [市府公廁查詢](https://www.teis-pt.gov.taipei/Public/ToiletSearch.aspx) |

公車資料不需金鑰。下載後先解 gzip，再讀取 JSON 的 `BusInfo`。使用路線 `Id` 對到站的 `RouteID`，並用站牌 `Id` 對 `StopID`。站牌方向取自 `GetStop` 的 `goBack`。

- [路線](https://tcgbusfs.blob.core.windows.net/blobbus/GetRoute.gz)
- [站牌](https://tcgbusfs.blob.core.windows.net/blobbus/GetStop.gz)
- [到站預估](https://tcgbusfs.blob.core.windows.net/blobbus/GetEstimateTime.gz)

服務清單涵蓋會員、卡證、帳務、優惠、市政服務、訊息、地圖與生活及交通八個分類。其他項目與問法請見 JSON，新增服務可直接加入資料表的種子檔。使用者問題會以最長符合的別名分流，包含「台北通」但找不到具體項目時會提供官方教學。

公車到站為即時查詢。其他項目提供官網導引，包含垃圾清運查詢、YouBike 站點、帳單與付款、會員及案件進度。個人金額、借閱紀錄、資格、付款、預約及報案須在官方服務依指示操作。

- [台北通卡證](https://id.taipei/tpcd/about/features/card) · [台北通市政服務](https://id.taipei/tpcd/about/features/connect) · [台北通帳單](https://id.taipei/tpcd/about/features/bill) · [特約商家](https://id.taipei/tpcd/offers)
- [台北通功能教學與常見問題](https://id.taipei/tpcd/about/faq) · [臺北市單一識別服務作業要點](https://id.taipei/tpcd/about/policies/terms)
- [開源城市通服務清單](https://github.com/taipei-doit/TownPass/blob/main/lib/page/city_service/model/my_service_model.dart)
