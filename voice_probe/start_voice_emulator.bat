@echo off
REM Start Pixel_8 emulator with host microphone enabled (-allow-host-audio).
REM Stop any running Pixel_8 first. Windows microphone must be unmuted.
set "EMU=%LOCALAPPDATA%\Android\Sdk\emulator\emulator.exe"
if not exist "%EMU%" (
  echo Emulator not found: %EMU%
  pause
  exit /b 1
)
start "" "%EMU%" -avd Pixel_8 -allow-host-audio
echo Emulator is starting. Wait 30-60 seconds.
timeout /t 5 >nul
