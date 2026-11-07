# 中文語音裝置測試

本資料夾是獨立 Flutter Android 測試程式，尚未整合 TownPass WebView。
固定回答：您好，這是語音測試。未串接 LLM 或市政 API。

## 執行
flutter pub get
flutter run -d emulator-5554

若中文路徑造成 Dart 分析器錯誤，使用英文路徑建置副本：
C:\Users\ASUS\Documents\Codex\Projects\VoiceProbe

## 測試
1. 按「開始說話」，允許麥克風，說中文測試句。
2. 確認辨識文字與固定回答字幕；回答應朗讀。
3. 按停止，字幕應保留；可再次開始。
4. 拒絕權限時應顯示錯誤提示。
5. 斷網測試並記錄結果；要求裝置端辨識不等於保證支援離線中文。

## 授權與限制
speech_to_text 7.5.0: BSD-3-Clause。
flutter_tts 4.2.5: MIT。
套件授權不等同於 Google 語音引擎授權；引擎可能連網，競賽適用性尚待確認。
保留套件 LICENSE；發行時應檢查完整相依套件授權。
此 APK 僅供開發測試，並非正式上架版本。
