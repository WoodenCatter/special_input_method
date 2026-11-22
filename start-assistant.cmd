@echo off
setlocal
title Life Assistant Server
cd /d "%~dp0"
if errorlevel 1 goto failed

if not exist ".env" (
    copy /y ".env.example" ".env" >nul
    if errorlevel 1 goto failed
    echo Fill in .env, then run start-assistant.cmd again.
    pause
    exit /b 1
)

if not exist ".venv\Scripts\python.exe" (
    echo Set up the Python environment using docs\life-assistant.md.
    goto failed
)

".venv\Scripts\python.exe" -m assistant_server.launcher
if errorlevel 1 goto failed
exit /b 0

:failed
pause
exit /b 1
