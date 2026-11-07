@echo off
setlocal

REM 1. Check if mvn is directly available in PATH
where mvn >nul 2>nul
if %ERRORLEVEL% equ 0 (
    mvn %*
    exit /b %ERRORLEVEL%
)

REM 2. Check IntelliJ bundled Maven
set "IDEA_MAVEN=C:\Program Files\JetBrains\IntelliJ IDEA 2025.3.3\plugins\maven\lib\maven3\bin\mvn.cmd"
if exist "%IDEA_MAVEN%" (
    call "%IDEA_MAVEN%" %*
    exit /b %ERRORLEVEL%
)

REM 3. Check alternate IntelliJ versions
for /d %%D in ("C:\Program Files\JetBrains\IntelliJ*") do (
    if exist "%%D\plugins\maven\lib\maven3\bin\mvn.cmd" (
        call "%%D\plugins\maven\lib\maven3\bin\mvn.cmd" %*
        exit /b %ERRORLEVEL%
    )
)

REM 4. If neither, auto-download portable Maven into .mvn\apache-maven
set "MAVEN_DIR=%~dp0.mvn\apache-maven-3.9.9"
if not exist "%MAVEN_DIR%\bin\mvn.cmd" (
    echo [INFO] Maven not found. Downloading portable Apache Maven 3.9.9...
    powershell -NoProfile -ExecutionPolicy Bypass -Command ^
        "$zip = '%~dp0.mvn\maven.zip'; [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12; (New-Object System.Net.WebClient).DownloadFile('https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.9/apache-maven-3.9.9-bin.zip', $zip); Expand-Archive -Path $zip -DestinationPath '%~dp0.mvn'; Remove-Item $zip -Force"
)

if exist "%MAVEN_DIR%\bin\mvn.cmd" (
    call "%MAVEN_DIR%\bin\mvn.cmd" %*
    exit /b %ERRORLEVEL%
)

echo [ERROR] Could not run or download Maven. Please install Maven or configure PATH.
exit /b 1
