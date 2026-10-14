@echo off
rem usage: run.bat build | web | terminal | backend [external] | workers
cd /d "%~dp0server"

if "%1"=="build" goto build
if "%1"=="web" goto web
if "%1"=="terminal" goto terminal
if "%1"=="backend" goto backend
if "%1"=="workers" goto workers
echo usage: run.bat build ^| web ^| terminal ^| backend [external] ^| workers
goto :eof

:build
if not exist src\out mkdir src\out
javac -d src\out src\*.java && echo Build OK
goto :eof

:ensure
if not exist src\out\FlowBackend.class call :build
goto :eof

:web
call :ensure
java -cp src\out GoogleFlowWebServer public storage
goto :eof

:terminal
call :ensure
java -cp src\out GoogleFlowTerminal storage
goto :eof

:backend
call :ensure
java -cp src\out FlowBackend storage %2
goto :eof

:workers
call :ensure
java -cp src\out FlowWorkerNode 9101 9102 9103
goto :eof