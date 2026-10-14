@echo off
setlocal
cd /d "%~dp0"
echo workdir: %CD%
echo.
where git >nul 2>nul || ( echo [ERR] git not found in PATH & pause & exit /b 1 )

if exist .git goto staged
echo [1/2] git init -b main
git init -b main
git remote add origin git@github.com:StarXfmy/dayplan-widget.git

:staged
echo [2/2] commit + push
git add -A
git -c user.name="StarXfmy" -c user.email="331280822+StarXfmy@users.noreply.github.com" commit -m "Day Plan android home-screen widget" || echo       (nothing changed)
git push -u origin main
echo.
echo ==================== how to read ====================
echo OK   : "main -^> main". Then open the repo page, tab "Actions",
echo        run "build APK", wait 2-4 min, download the artifact.
echo TYPE : "authenticity of host 'github.com'" -^> type yes, Enter
echo ERR  : "remote origin already exists" -^> run  git remote remove origin  then again
echo ERR  : "Permission denied (publickey)" / "failed to push" -^> copy lines to me
echo NOTE : repo must exist and be created first (ask the agent to create it)
echo ====================================================
git ls-files
pause
endlocal
