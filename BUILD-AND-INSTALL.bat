@echo off
setlocal EnableDelayedExpansion
cd /d "%~dp0"
title Lyrics Overlay - build
echo.
echo  ==========================================
echo     Lyrics Overlay  -  building the mod
echo  ==========================================
echo.

rem ---- find a Java 21+ JDK (needed to build, not just to play)
for /d %%D in ("%ProgramFiles%\Eclipse Adoptium\jdk-2*" "%ProgramFiles%\Microsoft\jdk-2*" "%ProgramFiles%\Java\jdk-2*" "%ProgramFiles%\Zulu\zulu-2*") do (
    if exist "%%~D\bin\javac.exe" set "JAVA_HOME=%%~D"
)
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\javac.exe" goto havejava
where javac >nul 2>nul && goto havejava

echo  You need the Java 21 JDK to build the mod (one-time setup).
echo.
choice /c YN /m " Install it now with winget (free, Eclipse Temurin 21)"
if errorlevel 2 goto manualjava
winget install -e --id EclipseAdoptium.Temurin.21.JDK --accept-source-agreements --accept-package-agreements
echo.
echo  Java installed. CLOSE this window and double-click BUILD-AND-INSTALL.bat again.
pause
exit /b 0

:manualjava
echo.
echo  Download "JDK 21" from https://adoptium.net and install it, then run this again.
pause
exit /b 1

:havejava
if defined JAVA_HOME echo  Using Java: %JAVA_HOME%
echo  Building... (first time downloads Minecraft + Fabric, takes a few minutes)
echo.
call gradlew.bat build --no-daemon
if errorlevel 1 (
    echo.
    echo  BUILD FAILED. Scroll up for the error, or send it to whoever helps you.
    pause
    exit /b 1
)

set "JAR="
for %%F in (build\libs\lyricsoverlay-*.jar) do (
    echo %%~nxF | findstr /i "sources dev" >nul || set "JAR=%%F"
)
if not defined JAR (
    echo  Could not find the built jar in build\libs
    pause
    exit /b 1
)
copy /y "%JAR%" . >nul
echo.
echo  SUCCESS:  %CD%\%JAR:build\libs\=%
echo.

set "MODS=%APPDATA%\.minecraft\mods"
if exist "%APPDATA%\.minecraft" (
    choice /c YN /m " Copy it into %MODS% now"
    if !errorlevel! == 1 (
        if not exist "%MODS%" mkdir "%MODS%"
        copy /y "%JAR%" "%MODS%" >nul
        echo  Copied. Make sure Fabric API for 1.21.11 is in that folder too.
    )
) else (
    echo  Put the jar in your mods folder (plus Fabric API for 1.21.11).
)
echo.
pause
