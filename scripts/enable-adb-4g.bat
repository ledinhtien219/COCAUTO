@echo off
chcp 65001 >nul
echo ============================================================
echo   CoC Auto - Bat ADB 127.0.0.1:5555 cho ca Wi-Fi va 4G/5G
echo ============================================================
echo.
set ADB=C:\Users\PC\AppData\Local\Android\Sdk\platform-tools\adb.exe
if not exist "%ADB%" set ADB=adb

echo Dang kiem tra dien thoai cam cap USB...
"%ADB%" devices
echo.
echo Dang mo cong ADB cuc bo 5555 (chay 100%% tren 4G/5G va Wi-Fi)...
"%ADB%" tcpip 5555
timeout /t 2 >nul
echo.
echo Kiem tra trang thai cong ADB tren may:
"%ADB%" shell getprop service.adb.tcp.port
echo.
echo XONG! Ban co the rut cap USB ngay bay gio.
echo Tu luc nay, moi khi mo CoC Auto (du dung Wi-Fi hay chi dung 4G/5G)
echo app se TU DONG ket noi ADB cuc bo 127.0.0.1:5555!
echo (Chi can chay lai file nay 1 lan sau khi khoi dong lai dien thoai.)
echo.
pause
