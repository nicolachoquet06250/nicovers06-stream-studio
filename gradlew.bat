@echo off
setlocal
set "APP_HOME=%~dp0"
set "GRADLE_VERSION=9.5.0"
set "GRADLE_HOME=%APP_HOME%.gradle-dist\gradle-%GRADLE_VERSION%"

if exist "%GRADLE_HOME%\bin\gradle.bat" goto run

where powershell.exe >nul 2>&1
if errorlevel 1 (
    echo Erreur : PowerShell est requis pour telecharger Gradle. 1>&2
    exit /b 1
)

echo Telechargement de Gradle %GRADLE_VERSION%... 1>&2
powershell.exe -NoProfile -ExecutionPolicy Bypass -Command "$ErrorActionPreference = 'Stop'; $distDir = Join-Path $env:APP_HOME '.gradle-dist'; $archive = Join-Path $distDir ('gradle-' + $env:GRADLE_VERSION + '-bin.zip'); $url = 'https://services.gradle.org/distributions/gradle-' + $env:GRADLE_VERSION + '-bin.zip'; New-Item -ItemType Directory -Force -Path $distDir | Out-Null; Invoke-WebRequest -UseBasicParsing -Uri $url -OutFile $archive; Expand-Archive -Force -Path $archive -DestinationPath $distDir; Remove-Item -Force $archive"
if errorlevel 1 exit /b 1

:run
call "%GRADLE_HOME%\bin\gradle.bat" %*
exit /b %ERRORLEVEL%
