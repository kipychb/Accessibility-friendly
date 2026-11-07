# 讓 whisper_ggml 在 x86_64（Android 模擬器）上使用 AVX2/FMA/F16C 加速。
#
# 原因：whisper_ggml 2.9.0 的 Android CMake 沒有指定 -march，x86_64 只會用基本指令，
# 模擬器上一句話要辨識 30 秒以上。真的 Android 手機是 ARM（arm64-v8a），不受此修改影響。
#
# 用法（在 TownPass 專案執行過 flutter pub get 之後）：
#   powershell -ExecutionPolicy Bypass -File patch-whisper-x86.ps1
# 之後重新 flutter build apk。每次 pub cache 被清除或升級 whisper_ggml 都要重跑。

$ErrorActionPreference = 'Stop'
$cache = if ($env:PUB_CACHE) { $env:PUB_CACHE } else { "$env:LOCALAPPDATA\Pub\Cache" }
$cmake = Join-Path $cache 'hosted\pub.dev\whisper_ggml-2.9.0\android\src\whisper\CMakeLists.txt'
if (-not (Test-Path $cmake)) { throw "找不到 $cmake，請先在 TownPass 執行 flutter pub get" }

$marker = '# PATCH: x86_64 AVX2 for Android emulator'
$text = Get-Content $cmake -Raw
if ($text.Contains($marker)) { Write-Host '已套用過，略過。'; exit 0 }

$anchor = 'target_compile_options(whisper PRIVATE -O3 -ffunction-sections -fdata-sections)'
if (-not $text.Contains($anchor)) { throw 'CMakeLists.txt 內容和預期不同，請手動檢查' }

$patch = @"
$anchor
$marker
if(ANDROID_ABI STREQUAL "x86_64")
    target_compile_options(whisper PRIVATE -mavx -mavx2 -mfma -mf16c -mbmi2)
endif()
"@
$text = $text.Replace($anchor, $patch)
Set-Content -Path $cmake -Value $text -NoNewline -Encoding utf8
Write-Host "已套用：$cmake"
