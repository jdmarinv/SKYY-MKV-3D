@echo off
setlocal
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0setup-3dfv-selector.ps1" %*
exit /b %ERRORLEVEL%
