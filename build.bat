@echo off
chcp 65001 >nul
echo ========================================
echo   喵喵嗷影视 - 自动打包脚本
echo ========================================
echo.

REM 读取CONFIG.txt里的配置
set "LIVE_URL="
set "VOD_URL="
set "INFO_TEXT="

for /f "tokens=1,* delims==" %%A in (CONFIG.txt) do (
    set "key=%%A"
    set "val=%%B"
    if "%%A"=="LIVE_URL" set "LIVE_URL=%%B"
    if "%%A"=="VOD_URL"  set "VOD_URL=%%B"
    if "%%A"=="INFO_TEXT" set "INFO_TEXT=%%B"
)

echo [配置读取]
echo 直播链接: %LIVE_URL%
echo 点播链接: %VOD_URL%
echo 提示文字: %INFO_TEXT%
echo.

REM 用Python更新strings.xml并打包
python build_helper.py "%LIVE_URL%" "%VOD_URL%" "%INFO_TEXT%"

if %errorlevel% equ 0 (
    echo.
    echo ========================================
    echo   打包成功！APK已生成：喵喵嗷影视.apk
    echo ========================================
) else (
    echo.
    echo ========================================
    echo   打包失败，请查看上方错误信息
    echo ========================================
)
pause
