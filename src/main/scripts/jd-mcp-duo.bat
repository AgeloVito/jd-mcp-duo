@echo off
setlocal

set "DIST_DIR=%~dp0.."
set "JAVA=%DIST_DIR%\runtime\bin\java.exe"

set "JAR=%DIST_DIR%\lib\jd-mcp-duo.jar"

if not exist "%JAVA%" (
    echo Error: bundled JRE not found at "%JAVA%"
    exit /b 1
)

"%JAVA%" -Xss10m -jar "%JAR%" %*
