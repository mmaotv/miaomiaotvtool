@echo off
chcp 65001 >nul 2>&1 || chcp 936 >nul
echo.
echo ============================================
echo   MiaoMiao TV Tool - Build Script
echo ============================================
echo.

REM Check Python
python --version >nul 2>&1
if errorlevel 1 (
    echo [ERROR] Python not found!
    echo Please install Python 3.x: https://www.python.org/downloads/
    echo Make sure to check "Add Python to PATH" during installation.
    pause
    exit /b 1
)

REM Check and install Pillow
python -c "from PIL import Image" >nul 2>&1
if errorlevel 1 (
    echo [INFO] Installing Pillow for icon processing...
    pip install Pillow -q
    if errorlevel 1 (
        echo [WARN] Pillow install failed, icon processing will be skipped
    )
)

REM Run build script
echo [INFO] Starting build...
python build.py

if errorlevel 1 (
    echo.
    echo [ERROR] Build failed!
    pause
    exit /b 1
)

echo.
echo [INFO] Build completed!
pause
