@echo off
rem Puts the website online through a free Cloudflare Quick Tunnel.
rem   share.bat           use the data in .\data
rem   share.bat --demo    also create the demo accounts if the data folder is empty
rem From PowerShell (e.g. the VS Code terminal) type .\share.bat
rem The public address (https://....trycloudflare.com) is printed below and changes every time.
rem Close both windows (or press Ctrl+C in each) to take the site offline.
setlocal
cd /d "%~dp0"
where cloudflared >nul 2>nul || (
  echo cloudflared is not installed. Install it with:
  echo   winget install --id Cloudflare.cloudflared
  exit /b 1
)
start "Quiz web server" cmd /k run.bat web --port 8080 %*
echo Starting the web server...
:wait
timeout /t 2 /nobreak >nul
curl -s -o nul http://localhost:8080/api/status || goto wait
echo Web server is ready. Opening the tunnel - look for the https://....trycloudflare.com address below.
cloudflared tunnel --url http://localhost:8080
