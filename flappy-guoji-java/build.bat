@echo off
rem ===========================================================================
rem  Flappy Guoji - build script
rem  Compiles src/ and tools/ into out/.
rem
rem  NOTE: this file is intentionally ASCII-only. A .bat whose own bytes are
rem        UTF-8 gets mangled by cmd.exe before the code page switch applies.
rem ===========================================================================
setlocal
cd /d "%~dp0"

set "JAVAC="
where javac >nul 2>nul
if %errorlevel%==0 set "JAVAC=javac"
if not defined JAVAC if defined JAVA_HOME if exist "%JAVA_HOME%\bin\javac.exe" set "JAVAC=%JAVA_HOME%\bin\javac.exe"
if not defined JAVAC if exist "D:\kaifa\jdk21\bin\javac.exe" set "JAVAC=D:\kaifa\jdk21\bin\javac.exe"
if not defined JAVAC if exist "C:\Program Files\Java\jdk-21\bin\javac.exe" set "JAVAC=C:\Program Files\Java\jdk-21\bin\javac.exe"

if not defined JAVAC goto :nojava

if not exist out mkdir out

echo Compiling with: %JAVAC%
"%JAVAC%" -encoding UTF-8 -d out src\guoji\*.java tools\*.java
if errorlevel 1 goto :failed

echo.
echo [OK] Build finished. Classes are in out\
endlocal
exit /b 0

:nojava
echo [ERROR] javac not found.
echo         Install a JDK 17 or newer, or set JAVA_HOME.
endlocal
exit /b 1

:failed
echo.
echo [ERROR] Compile failed. See messages above.
endlocal
exit /b 1
