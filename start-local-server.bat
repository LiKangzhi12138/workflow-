@echo off
setlocal
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0start-local-server.ps1"
exit /b %errorlevel%
