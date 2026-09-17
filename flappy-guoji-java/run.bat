@echo off
rem ===========================================================================
rem  Flappy Guoji - run script
rem  Builds if needed, then launches the game window.
rem
rem  Optional: drag a .gif onto this file, or pass a path, to use another asset:
rem      run.bat "D:\some\other.gif"
rem
rem  ASCII-only on purpose, see build.bat.
rem ===========================================================================
setlocal
cd /d "%~dp0"

rem Match the console code page to -Dfile.encoding=UTF-8 below,
rem otherwise the Chinese startup report prints as mojibake on zh-CN Windows.
chcp 65001 >nul

set "JAVA="
where java >nul 2>nul
if %errorlevel%==0 set "JAVA=java"
if not defined JAVA if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set "JAVA=%JAVA_HOME%\bin\java.exe"
if not defined JAVA if exist "D:\kaifa\jdk21\bin\java.exe" set "JAVA=D:\kaifa\jdk21\bin\java.exe"
if not defined JAVA if exist "C:\Program Files\Java\jdk-21\bin\java.exe" set "JAVA=C:\Program Files\Java\jdk-21\bin\java.exe"

if not defined JAVA goto :nojava

rem --- build first if the classes are missing ---
if not exist "out\guoji\FlappyGuoJiApp.class" call "%~dp0build.bat"
if not exist "out\guoji\FlappyGuoJiApp.class" goto :failed

rem UTF-8 so the console prints the asset report correctly on zh-CN Windows
"%JAVA%" -Dfile.encoding=UTF-8 -Dsun.java2d.uiScale.enabled=true -cp out guoji.FlappyGuoJiApp %*
endlocal
exit /b 0

:nojava
echo [ERROR] java not found.
echo         Install a JDK 17 or newer, or set JAVA_HOME.
endlocal
exit /b 1

:failed
echo [ERROR] Build failed, cannot start the game.
endlocal
exit /b 1
