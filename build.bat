@echo off
cd /d "%~dp0"
title SSH Tunnel Build

echo.
echo  SSH Tunnel v3 - Build Script
echo  ================================
echo.

python --version >nul 2>&1
if errorlevel 1 (
    echo [ERROR] Python not found. Please install Python 3.10+
    pause
    exit /b 1
)

echo [1/3] Installing dependencies...
python -m pip install customtkinter paramiko pillow pystray pyinstaller --quiet
if errorlevel 1 (
    echo [ERROR] pip install failed.
    pause
    exit /b 1
)

echo [2/3] Building EXE...
python -m PyInstaller --noconfirm --onefile --windowed --name SSH-Tunnel --icon assets\app.ico --hidden-import customtkinter --hidden-import paramiko --hidden-import PIL --hidden-import pystray --collect-all customtkinter ssh_tunnel.py
if errorlevel 1 (
    echo [ERROR] PyInstaller failed.
    pause
    exit /b 1
)

echo [3/3] Cleaning temp files...
if exist build rmdir /s /q build
if exist SSH-Tunnel.spec del SSH-Tunnel.spec

echo.
echo  Done! Output: dist\SSH-Tunnel.exe
echo  NOTE: Run as Administrator for System-wide VPN mode.
echo.
pause
