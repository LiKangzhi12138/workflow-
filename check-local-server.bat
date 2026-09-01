@echo off
setlocal
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0check-local-server.ps1"
exit /b %errorlevel%
