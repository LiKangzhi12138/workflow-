@echo off
setlocal
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0stop-local-server.ps1"
exit /b %errorlevel%
