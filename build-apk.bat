@echo off
rem ===========================================================================
rem  DroidDeck - one-click APK builder (Windows)
rem
rem  Usage:  build-apk.bat [release|debug] [clean] [--offline] [--no-open]
rem                      [--own-key|--no-own-key] [--no-pause]
rem
rem  What it does:
rem    1. finds the Android SDK, a JDK 17+ and the NDK the project pins
rem       (searched under D:\Android first, then the usual environment
rem       variables and install locations)
rem    2. links that NDK into <sdk>\ndk\<version>, which is where the Android
rem       Gradle plugin insists on looking for it
rem    3. provisions Gradle itself: gradlew would download from
rem       services.gradle.org, which redirects to github.com - unreachable on
rem       many Chinese networks - so the distribution comes from a mirror
rem    4. writes local.properties, then builds
rem    5. checks the APK really carries the session's native payload, then
rem       signs it with this checkout's own key when GoogleKeystore\signing.env
rem       names one, handing over from the AOSP testkey so a device that
rem       already has an earlier build of this checkout updates in place
rem       instead of being refused as a different signer. No settings file, or
rem       no keystore beside it, and the APK keeps the AOSP testkey signature
rem       rather than the build stopping - a checkout without the key still
rem       produces a package
rem    6. copies the APK into dist\ and opens that folder
rem
rem  Everything is logged to build-logs\build-<variant>-<stamp>.log
rem
rem  The signing key is not in this file, nor in any file git tracks. Its
rem  location, password and alias live in GoogleKeystore\signing.env - beside
rem  the key, inside a folder .gitignore excludes because it is not source.
rem  Without that folder every build stays signed with the public AOSP testkey,
rem  which proves nothing but keeps one stable signature.
rem  DROIDDECK_SIGNING_ENV points at a copy kept somewhere else.
rem  See tools/release/sign-apk-local.py.
rem
rem  Overrides (set before running):
rem    DROIDDECK_ANDROID_SDK   Android SDK root
rem    DROIDDECK_JAVA_HOME     JDK 17 or newer
rem    DROIDDECK_NDK_HOME      NDK root (the folder holding source.properties)
rem    DROIDDECK_GRADLE_STORE  where a provisioned Gradle is kept
rem                            (default: D:\Android\Gradle, else %%LOCALAPPDATA%%)
rem ===========================================================================

setlocal EnableExtensions

set "ROOT=%~dp0"
if "%ROOT:~-1%"=="\" set "ROOT=%ROOT:~0,-1%"

set "VARIANT=release"
set "DO_CLEAN=0"
set "PAUSE_AT_END=1"
set "OPEN_DIST=1"
set "OFFLINE=0"

:parse_args
if "%~1"=="" goto args_done
set "KNOWN="
if /i "%~1"=="release"    (set "VARIANT=release" & set "KNOWN=1")
if /i "%~1"=="debug"      (set "VARIANT=debug"   & set "KNOWN=1")
if /i "%~1"=="clean"      (set "DO_CLEAN=1"      & set "KNOWN=1")
if /i "%~1"=="--clean"    (set "DO_CLEAN=1"      & set "KNOWN=1")
if /i "%~1"=="--offline"  (set "OFFLINE=1"       & set "KNOWN=1")
if /i "%~1"=="--no-open"  (set "OPEN_DIST=0"     & set "KNOWN=1")
if /i "%~1"=="--no-pause" (set "PAUSE_AT_END=0"  & set "KNOWN=1")
if /i "%~1"=="--own-key"    (set "OWN_KEY=1" & set "OWN_KEY_SET=1" & set "KNOWN=1")
if /i "%~1"=="--no-own-key" (set "OWN_KEY=0" & set "OWN_KEY_SET=1" & set "KNOWN=1")
if /i "%~1"=="-h"         (set "KNOWN=1" & goto usage)
if /i "%~1"=="--help"     (set "KNOWN=1" & goto usage)
if /i "%~1"=="/?"         (set "KNOWN=1" & goto usage)
if not defined KNOWN echo [WARN] unknown argument "%~1", ignored.
shift
goto parse_args
:args_done

rem  Sign with this checkout's own key when it has one. --own-key / --no-own-key above decide it;
rem  otherwise the settings file being there is what says so. It is untracked because it holds the
rem  keystore's password - no settings, no key, and the APK keeps the AOSP testkey signature. The
rem  project root is still read as the older location, so a checkout set up before the move keeps
rem  signing with its own key. sign-apk-local.py prefers GoogleKeystore\signing.env the same way.
if not defined OWN_KEY_SET (
    set "OWN_KEY=0"
    set "OWN_KEY_AUTO=1"
    if exist "%ROOT%\GoogleKeystore\signing.env" set "OWN_KEY=1"
    if exist "%ROOT%\.signing.env" set "OWN_KEY=1"
)

rem  When nothing on the command line asked for the key, its absence is a fall-back rather than a
rem  failure: the APK keeps the AOSP testkey signature and the build still finishes. That covers
rem  both halves - a checkout without GoogleKeystore\, and one where the keystore itself was deleted
rem  while the settings naming it stayed behind.
set "SIGNARGS="
if defined OWN_KEY_AUTO set "SIGNARGS=--optional"

if /i "%VARIANT%"=="debug" (set "TASK=assembleDebug") else (set "TASK=assembleRelease")

title DroidDeck - building %VARIANT% APK
echo.
echo ===========================================================================
echo  DroidDeck APK builder
echo  project : %ROOT%
echo  variant : %VARIANT%  (%TASK%)
echo ===========================================================================
echo.

if not exist "%ROOT%\gradlew.bat" (
    echo [ERROR] gradlew.bat not found in %ROOT%
    echo         Run this script from the project root.
    goto :fail
)
if not exist "%ROOT%\gradle\wrapper\gradle-wrapper.properties" (
    echo [ERROR] gradle\wrapper\gradle-wrapper.properties not found.
    echo         The checkout is incomplete.
    goto :fail
)

rem ---------------------------------------------------------------------------
rem  Android SDK
rem ---------------------------------------------------------------------------
set "SDK="
if defined DROIDDECK_ANDROID_SDK if exist "%DROIDDECK_ANDROID_SDK%\platforms" set "SDK=%DROIDDECK_ANDROID_SDK%"
if not defined SDK if defined ANDROID_SDK_ROOT if exist "%ANDROID_SDK_ROOT%\platforms" set "SDK=%ANDROID_SDK_ROOT%"
if not defined SDK if defined ANDROID_HOME if exist "%ANDROID_HOME%\platforms" set "SDK=%ANDROID_HOME%"
if not defined SDK if exist "D:\Android\SDK\platforms" set "SDK=D:\Android\SDK"
if not defined SDK if exist "D:\Android\sdk\platforms" set "SDK=D:\Android\sdk"
if not defined SDK if exist "D:\Android\platforms" set "SDK=D:\Android"
if not defined SDK if exist "%LOCALAPPDATA%\Android\Sdk\platforms" set "SDK=%LOCALAPPDATA%\Android\Sdk"
if not defined SDK if exist "C:\Android\Sdk\platforms" set "SDK=C:\Android\Sdk"
if not defined SDK (
    echo [ERROR] Android SDK not found.
    echo         Looked at: DROIDDECK_ANDROID_SDK, ANDROID_SDK_ROOT, ANDROID_HOME,
    echo                    D:\Android\SDK, D:\Android, %%LOCALAPPDATA%%\Android\Sdk
    echo         Point the script at yours, for example:
    echo             set DROIDDECK_ANDROID_SDK=D:\Android\SDK ^&^& build-apk.bat
    goto :fail
)
echo [ OK ] Android SDK : %SDK%

if not exist "%SDK%\platforms\android-34" (
    echo [WARN] platform android-34 is missing; compileSdk 34 needs it.
    echo        AGP will try to download it on the first build.
)
if not exist "%SDK%\build-tools\34.0.0" (
    echo [WARN] build-tools 34.0.0 is missing; AGP will try to download it.
)
if not exist "%SDK%\cmake\3.22.1\bin\cmake.exe" (
    echo [WARN] CMake 3.22.1 is not installed in the SDK. The app's native code needs
    echo        it, and AGP will download it on the first build ^(needs internet^).
)

rem ---------------------------------------------------------------------------
rem  JDK (17 or newer - AGP 8.8 refuses anything older)
rem ---------------------------------------------------------------------------
set "JDK="
if defined DROIDDECK_JAVA_HOME if exist "%DROIDDECK_JAVA_HOME%\bin\javac.exe" set "JDK=%DROIDDECK_JAVA_HOME%"
if not defined JDK if defined JAVA_HOME if exist "%JAVA_HOME%\bin\javac.exe" set "JDK=%JAVA_HOME%"
if not defined JDK for /d %%D in ("D:\Android\JDK\*") do if exist "%%~fD\bin\javac.exe" set "JDK=%%~fD"
if not defined JDK for /d %%D in ("D:\Android\jdk\*") do if exist "%%~fD\bin\javac.exe" set "JDK=%%~fD"
if not defined JDK for /d %%D in ("D:\Android\*jdk*") do if exist "%%~fD\bin\javac.exe" set "JDK=%%~fD"
if not defined JDK for /d %%D in ("C:\Program Files\Java\*") do if exist "%%~fD\bin\javac.exe" set "JDK=%%~fD"
if not defined JDK for /d %%D in ("C:\Program Files\Eclipse Adoptium\*") do if exist "%%~fD\bin\javac.exe" set "JDK=%%~fD"
if not defined JDK for /d %%D in ("C:\Program Files\Microsoft\jdk*") do if exist "%%~fD\bin\javac.exe" set "JDK=%%~fD"
if not defined JDK for /d %%D in ("C:\Program Files\Zulu\*") do if exist "%%~fD\bin\javac.exe" set "JDK=%%~fD"
if not defined JDK if exist "C:\Program Files\Android\Android Studio\jbr\bin\javac.exe" set "JDK=C:\Program Files\Android\Android Studio\jbr"
if not defined JDK if exist "%LOCALAPPDATA%\Programs\Android Studio\jbr\bin\javac.exe" set "JDK=%LOCALAPPDATA%\Programs\Android Studio\jbr"
if not defined JDK (
    echo [ERROR] No JDK with javac.exe found ^(need 17 or newer^).
    echo         Install one, or point the script at yours:
    echo             set DROIDDECK_JAVA_HOME=C:\path\to\jdk-17 ^&^& build-apk.bat
    goto :fail
)
rem  A JDK ships a "release" file whose JAVA_VERSION line states the version.
rem  Reading it beats parsing "java -version", which writes to stderr and needs
rem  the ugliest quoting in cmd to capture.
set "JAVAVER="
set "JAVA_MAJOR="
for /f "tokens=2 delims==" %%a in ('findstr /b "JAVA_VERSION=" "%JDK%\release" 2^>nul') do set "JAVAVER=%%~a"
for /f "tokens=1 delims=.-+_" %%a in ("%JAVAVER%") do set "JAVA_MAJOR=%%a"
if "%JAVA_MAJOR%"=="1" set "JAVA_MAJOR=8"
echo [ OK ] JDK         : %JDK%  ^(java %JAVAVER%^)
if defined JAVA_MAJOR if %JAVA_MAJOR% LSS 17 (
    echo [ERROR] This JDK is too old. Android Gradle Plugin 8.8 needs JDK 17 or newer.
    goto :fail
)

rem ---------------------------------------------------------------------------
rem  NDK - the version the project pins in gradle.properties
rem ---------------------------------------------------------------------------
set "NDK_VER="
for /f "tokens=1,* delims==" %%a in ('findstr /b /i "ndkVersion" "%ROOT%\gradle.properties" 2^>nul') do set "NDK_VER=%%b"
if not defined NDK_VER set "NDK_VER=27.3.13750724"

set "NDK="
if defined DROIDDECK_NDK_HOME if exist "%DROIDDECK_NDK_HOME%\source.properties" set "NDK=%DROIDDECK_NDK_HOME%"
if not defined NDK if exist "%SDK%\ndk\%NDK_VER%\source.properties" set "NDK=%SDK%\ndk\%NDK_VER%"
if not defined NDK call :find_ndk "%SDK%\ndk"
if not defined NDK call :find_ndk "D:\Android\NDK"
if not defined NDK call :find_ndk "D:\Android\ndk"
if not defined NDK call :find_ndk "D:\Android\SDK\ndk"

if not defined NDK (
    echo [WARN] No NDK %NDK_VER% found. AGP will try to download it ^(needs
    echo        internet^); if that fails, unpack an NDK r27d into D:\Android\NDK
    echo        and run this script again.
    goto :ndk_done
)
echo [ OK ] NDK         : %NDK%  (%NDK_VER%)

if /i "%NDK%"=="%SDK%\ndk\%NDK_VER%" goto :ndk_done
if exist "%SDK%\ndk\%NDK_VER%\source.properties" (
    set "NDK=%SDK%\ndk\%NDK_VER%"
    goto :ndk_done
)
echo [INFO] Linking that NDK into the SDK at ndk\%NDK_VER% ...
if not exist "%SDK%\ndk" mkdir "%SDK%\ndk" >nul 2>&1
if exist "%SDK%\ndk\%NDK_VER%" rmdir "%SDK%\ndk\%NDK_VER%" 2>nul
mklink /J "%SDK%\ndk\%NDK_VER%" "%NDK%" >nul 2>&1
if exist "%SDK%\ndk\%NDK_VER%\source.properties" (
    echo [ OK ]   linked ^(a link, not a copy - no extra disk use^)
) else (
    echo [WARN] Could not create the link. If %SDK%\ndk\%NDK_VER% already exists,
    echo        delete it and run this script again.
)
:ndk_done

rem ---------------------------------------------------------------------------
rem  Gradle - the wrapper would fetch services.gradle.org, which redirects to
rem  github.com. Take the distribution from a mirror once instead, and run that
rem  copy of Gradle directly.
rem ---------------------------------------------------------------------------
set "DIST_URL="
set "DIST_FILE="
set "DIST_BASE="
set "GRADLE_VER="
set "GRADLE_BAT="
for /f "tokens=1,* delims==" %%a in ('findstr /b /i "distributionUrl" "%ROOT%\gradle\wrapper\gradle-wrapper.properties" 2^>nul') do set "DIST_URL=%%b"
rem  The properties file escapes the colon of the scheme: https\://...
if defined DIST_URL set "DIST_URL=%DIST_URL:\=%"
if defined DIST_URL for %%f in ("%DIST_URL%") do set "DIST_FILE=%%~nxf"
if not defined DIST_FILE set "DIST_FILE=gradle-8.10.2-all.zip"
for %%f in ("%DIST_FILE%") do set "DIST_BASE=%%~nf"
set "GRADLE_VER=%DIST_BASE:gradle=%"
if /i "%GRADLE_VER:~-4%"=="-all" set "GRADLE_VER=%GRADLE_VER:~0,-4%"
if /i "%GRADLE_VER:~-4%"=="-bin" set "GRADLE_VER=%GRADLE_VER:~0,-4%"
set "GRADLE_VER=%GRADLE_VER:-=%"
if not defined GRADLE_VER set "GRADLE_VER=8.10.2"

rem  Something the Gradle wrapper already unpacked is the cheapest source.
set "GUH=%GRADLE_USER_HOME%"
if not defined GUH set "GUH=%USERPROFILE%\.gradle"
call :find_gradle_dist "%GUH%\wrapper\dists\gradle-%GRADLE_VER%-all"
if not defined GRADLE_BAT call :find_gradle_dist "%GUH%\wrapper\dists\gradle-%GRADLE_VER%-bin"

set "GSTORE=%DROIDDECK_GRADLE_STORE%"
if not defined GSTORE if exist "D:\Android\Gradle" set "GSTORE=D:\Android\Gradle"
if not defined GSTORE set "GSTORE=%LOCALAPPDATA%\DroidDeck\gradle"
if not defined GRADLE_BAT if exist "%GSTORE%\gradle-%GRADLE_VER%\bin\gradle.bat" set "GRADLE_BAT=%GSTORE%\gradle-%GRADLE_VER%\bin\gradle.bat"

if defined GRADLE_BAT goto :gradle_ready
if "%OFFLINE%"=="1" (
    echo [ERROR] Gradle %GRADLE_VER% is not on this machine and --offline was given.
    goto :fail
)
echo [INFO] Gradle %GRADLE_VER% is not installed yet; fetching it once.
if not exist "%GSTORE%" mkdir "%GSTORE%" >nul 2>&1

:gradle_fetch
rem  -bin is half the size of -all and builds identically; only -all carries
rem  sources and javadoc, which a command-line build never opens. Try it first,
rem  fall back to whatever the wrapper asked for.
set "DIST_ZIP=%GSTORE%\gradle-%GRADLE_VER%-bin.zip"
if not exist "%DIST_ZIP%" call :fetch "gradle-%GRADLE_VER%-bin.zip"
if exist "%DIST_ZIP%" goto :gradle_unpack
set "DIST_ZIP=%GSTORE%\gradle-%GRADLE_VER%-all.zip"
if not exist "%DIST_ZIP%" call :fetch "%DIST_FILE%"
if exist "%DIST_ZIP%" goto :gradle_unpack
echo [ERROR] Could not download Gradle %GRADLE_VER% from any mirror.
echo         Download gradle-%GRADLE_VER%-bin.zip by hand, unpack it into
echo         "%GSTORE%" and run this script again.
goto :fail

:gradle_unpack
echo [INFO] Unpacking "%DIST_ZIP%" ^(this takes a minute^) ...
tar -xf "%DIST_ZIP%" -C "%GSTORE%" 2>nul
if not exist "%GSTORE%\gradle-%GRADLE_VER%\bin\gradle.bat" powershell -NoProfile -Command "Expand-Archive -LiteralPath '%DIST_ZIP%' -DestinationPath '%GSTORE%' -Force"
if not exist "%GSTORE%\gradle-%GRADLE_VER%\bin\gradle.bat" (
    echo [ERROR] Unpacking %DIST_ZIP% produced no gradle-%GRADLE_VER%\bin\gradle.bat
    goto :fail
)
set "GRADLE_BAT=%GSTORE%\gradle-%GRADLE_VER%\bin\gradle.bat"
:gradle_ready
echo [ OK ] Gradle      : %GRADLE_BAT%

rem ---------------------------------------------------------------------------
rem  local.properties
rem ---------------------------------------------------------------------------
>"%ROOT%\local.properties" echo sdk.dir=%SDK:\=/%
echo [ OK ] local.properties written

rem  The session's native helpers are cross-compiled by tools/build_local.sh
rem  (Docker + the NDK cross toolchain) and are not part of a plain checkout.
rem  Say so up front, and refuse at the end: without libproot.so,
rem  LinuxRuntime.isInstalled() is false however complete the rootfs is, so the
rem  APK installs, its UI runs, and then it sits on the session loading screen
rem  forever. Every other check passes - which is how one got shipped.
set "PAYLOAD_MISSING="
if not exist "%ROOT%\app\src\main\jniLibs\arm64-v8a\libproot.so" set "PAYLOAD_MISSING=1"
if not exist "%ROOT%\app\src\main\jniLibs\arm64-v8a\libdirectaudiorelay.so" set "PAYLOAD_MISSING=1"
if not exist "%ROOT%\app\src\main\assets\linuxfs\libfakeinput.so" set "PAYLOAD_MISSING=1"
if not exist "%ROOT%\app\src\main\assets\linuxfs\libssbs.so" set "PAYLOAD_MISSING=1"
if not exist "%ROOT%\app\src\main\assets\linuxfs\usr\local\bin\gamescope" set "PAYLOAD_MISSING=1"
if defined PAYLOAD_MISSING (
    echo.
    echo [WARN] This checkout is missing the session's native payload: either
    echo        app\src\main\jniLibs\arm64-v8a has no libproot.so or
    echo        libdirectaudiorelay.so, or app\src\main\assets\linuxfs has no
    echo        libfakeinput.so / libssbs.so / gamescope.
    echo        Those are built by tools\build_local.sh, which needs Docker; no
    echo        other step produces them. Without them the APK installs and its UI
    echo        runs, but no Linux session can ever start.
    echo        The payload is checked again when the APK is built, and this run is
    echo        rejected then if it is still missing.
    echo.
)

rem ---------------------------------------------------------------------------
rem  Build
rem ---------------------------------------------------------------------------
set "LOGDIR=%ROOT%\build-logs"
if not exist "%LOGDIR%" mkdir "%LOGDIR%" >nul 2>&1
set "STAMP="
for /f "usebackq delims=" %%t in (`powershell -NoProfile -Command "Get-Date -Format yyyyMMdd-HHmmss"`) do set "STAMP=%%t"
if not defined STAMP set "STAMP=%RANDOM%"
set "LOG=%LOGDIR%\build-%VARIANT%-%STAMP%.log"

rem  Arguments are single-quoted for the PowerShell call below, and that is not
rem  decoration. Powershell 5.1 parses -PndkVersion=27.3.13750724 as the number
rem  27.3 followed by .13750724 and hands Gradle two arguments, the second of
rem  which Gradle takes for a task name. Double quotes cannot fix it here: they
rem  sit inside the -Command "..." argument, where cmd.exe's own quote handling
rem  eats them before PowerShell ever sees them. Single quotes survive.
set GRADLE_ARGS='%TASK%' '--console=plain' '-PndkVersion=%NDK_VER%'
set GRADLE_SHOW=%TASK% --console=plain -PndkVersion=%NDK_VER%
if "%DO_CLEAN%"=="1" (
    set GRADLE_ARGS='clean' %GRADLE_ARGS%
    set GRADLE_SHOW=clean %GRADLE_SHOW%
)
if "%OFFLINE%"=="1" (
    set GRADLE_ARGS=%GRADLE_ARGS% '--offline'
    set GRADLE_SHOW=%GRADLE_SHOW% --offline
)

set "ANDROID_HOME=%SDK%"
set "ANDROID_SDK_ROOT=%SDK%"
set "JAVA_HOME=%JDK%"
set "PATH=%JDK%\bin;%PATH%"

echo.
echo ---------------------------------------------------------------------------
echo  gradle %GRADLE_SHOW%
echo  log: %LOG%
echo  The first build downloads the Android Gradle Plugin, the dependencies and
echo  CMake, then compiles the native code - expect a long wait.
echo ---------------------------------------------------------------------------
echo.

cd /d "%ROOT%"
rem  Tee-Object would write the log as UTF-16 (PowerShell 5.1's default), which
rem  a plain "type log" then shows as spaced-out rubbish. A StreamWriter emits
rem  UTF-8, while each line is re-emitted so the console still sees it live.
rem  [string]$_ stringifies stderr records: javac's notes arrive as ErrorRecords
rem  and PowerShell would otherwise print each as a NativeCommandError block.
rem  No double quotes may appear in the command below - they are inside the
rem  -Command "..." argument, where cmd.exe strips them before PowerShell runs.
powershell -NoProfile -ExecutionPolicy Bypass -Command "$ec=0; $w=New-Object System.IO.StreamWriter('%LOG%'); & '%GRADLE_BAT%' %GRADLE_ARGS% 2>&1 | ForEach-Object { $t = [string]$_; $w.WriteLine($t); $t } | Out-Host; $ec=$LASTEXITCODE; $w.Close(); exit $ec"
set "GRADLE_EXIT=%ERRORLEVEL%"

if not "%GRADLE_EXIT%"=="0" (
    echo.
    echo [FAIL] Gradle exited with code %GRADLE_EXIT%.
    echo        Full log: %LOG%
    echo        Search it for the first "FAILURE" or "error:" line.
    goto :fail
)

rem ---------------------------------------------------------------------------
rem  Collect the APK
rem ---------------------------------------------------------------------------
set "APK="
if exist "%ROOT%\app\build\outputs\apk\%VARIANT%\app-%VARIANT%.apk" set "APK=%ROOT%\app\build\outputs\apk\%VARIANT%\app-%VARIANT%.apk"
if not defined APK for %%F in ("%ROOT%\app\build\outputs\apk\%VARIANT%\*.apk") do set "APK=%%~fF"
if not defined APK (
    echo [FAIL] The build reported success but no APK is under
    echo        app\build\outputs\apk\%VARIANT%\
    goto :fail
)

rem  The project's own check: the session helper scripts in the APK match this
rem  checkout, and the native payload really is in there. Its exit code is the
rem  whole difference between an APK that works and one that hangs on the loading
rem  screen, so it is a gate and not a note - the one build that shipped without
rem  the payload passed every other check this script makes.
for %%P in (python.exe python3.exe py.exe) do if not defined PYTHON set "PYTHON=%%~$PATH:P"
if defined PYTHON (
    "%PYTHON%" "%ROOT%\tools\release\check_session_assets.py" "%APK%"
    if errorlevel 1 (
        echo.
        echo [FAIL] The APK is incomplete - see the "missing ..." lines above.
        echo        An APK built without the session's native payload installs and
        echo        its UI runs, but no Linux session can start: it stops on the
        echo        loading screen.
        echo        Build that payload with tools\build_local.sh - Docker plus the
        echo        NDK cross toolchain - or restore app\src\main\jniLibs and
        echo        app\src\main\assets\linuxfs from a release APK of the same
        echo        version.
        echo.
        echo        Built, but NOT copied to dist: %APK%
        echo        log: %LOG%
        goto :fail
    )
) else (
    echo [WARN] No Python on PATH, so tools\release\check_session_assets.py did not
    echo        run: the APK was not checked for the session's native payload.
)

rem  Sign with this checkout's own key, handing over from the AOSP testkey. The hand-over is what
rem  makes it an update rather than a clash: a device that already has a build of this checkout -
rem  testkey-signed, every one of them - installs this over it and keeps its rootfs, Proton seed and
rem  Steam client. It also takes the rollback right away from the testkey, so an apk signed with the
rem  testkey alone - a public key, so anyone can sign with it - can no longer replace this one.
if "%OWN_KEY%"=="1" (
    if not defined PYTHON (
        echo.
        echo [WARN] No Python on PATH, so the APK keeps the AOSP testkey signature:
        echo        the signing settings name a key, but signing with it needs Python.
    ) else (
        echo.
        echo   Looking for this checkout's own signing key ...
        "%PYTHON%" "%ROOT%\tools\release\sign-apk-local.py" "%APK%" "%APK%.signed" --sdk "%SDK%" %SIGNARGS%
        if errorlevel 1 (
            if errorlevel 3 (
                rem  --optional: there is no key to sign with. Nothing was written, %APK% is
                rem  untouched, so the testkey signature it already carries is what ships.
                set "OWN_KEY=0"
                echo.
                echo [WARN] No key to sign with, so the APK keeps the AOSP testkey signature.
                echo        GoogleKeystore\signing.env, or the keystore it names, is not there.
                echo        Pass --own-key to make a missing key a build failure instead.
            ) else (
                echo.
                echo [FAIL] Signing with the local key failed - see the message above.
                echo        GoogleKeystore\signing.env names the keystore, its password, the key
                echo        alias and the certificate the result must carry. Right the file, or
                echo        pass --no-own-key to build with the AOSP testkey instead.
                echo.
                echo        Built, but NOT copied to dist: %APK%
                echo        log: %LOG%
                goto :fail
            )
        ) else (
            move /y "%APK%.signed" "%APK%" >nul
        )
    )
)

set "APPVER="
for /f "tokens=2" %%a in ('findstr /r /c:"versionName" "%ROOT%\app\build.gradle" 2^>nul') do set "APPVER=%%~a"
if not defined APPVER set "APPVER=0.0.0"

set "DIST=%ROOT%\dist"
if not exist "%DIST%" mkdir "%DIST%" >nul 2>&1
rem  The fork is called DroidDeckCOK (COK = Chinese One Key), and that is the only
rem  name that differs from upstream: the APK is still built from the same tree, so
rem  it keeps upstream's applicationId, versionName and label. Only the file this
rem  script drops into dist\ carries the new name.
set "OUT=%DIST%\DroidDeckCOK-%APPVER%-%VARIANT%.apk"
set "OUTMB=?"
copy /y "%APK%" "%OUT%" >nul
if errorlevel 1 (
    set "OUT=%APK%"
    echo [WARN] Could not copy the APK into dist\.
) else (
    for %%A in ("%OUT%") do set /a OUTMB=%%~zA/1048576
)

set "SIGNERNOTE=the AOSP testkey  ^(--no-own-key given, or no key to sign with^)"
if "%OWN_KEY%"=="1" set "SIGNERNOTE=this checkout's own key, handed over from the AOSP testkey"

echo.
echo ===========================================================================
echo  BUILD OK
echo ===========================================================================
echo  APK    : %OUT%
echo  size   : about %OUTMB% MB
echo  signer : %SIGNERNOTE%
echo  log    : %LOG%
echo.
echo  Install it on a device with USB debugging enabled:
echo      "%SDK%\platform-tools\adb.exe" install -r "%OUT%"
echo.
if "%OPEN_DIST%"=="1" start "" explorer "%DIST%"
if "%PAUSE_AT_END%"=="1" pause
exit /b 0

rem ---------------------------------------------------------------------------
:find_ndk
rem  Scans one directory's children for an NDK whose Pkg.Revision is %NDK_VER%.
if not exist "%~1" goto :eof
for /d %%D in ("%~1\*") do (
    if exist "%%~fD\source.properties" (
        for /f "tokens=1,* delims==" %%a in ('findstr /b "Pkg.Revision" "%%~fD\source.properties" 2^>nul') do (
            for /f "tokens=1 delims= " %%c in ("%%b") do (
                if "%%c"=="%NDK_VER%" set "NDK=%%~fD"
            )
        )
    )
)
goto :eof

:find_gradle_dist
rem  %1 is a wrapper dists flavour directory; its children are hash directories
rem  holding gradle-<version> plus the .ok marker the wrapper looks for.
if not exist "%~1" goto :eof
for /d %%D in ("%~1\*") do if exist "%%~fD\gradle-%GRADLE_VER%\bin\gradle.bat" set "GRADLE_BAT=%%~fD\gradle-%GRADLE_VER%\bin\gradle.bat"
goto :eof

:fetch
rem  %1 is the file name to fetch. Mirrors first, the official host last; the
rem  official one redirects to github.com, which is why it is not tried first.
set "FNAME=%~1"
set "FTARGET=%GSTORE%\%FNAME%"
set "FTMP=%FTARGET%.part"
del "%FTMP%" >nul 2>&1
call :try_fetch "https://mirrors.cloud.tencent.com/gradle"
if not exist "%FTARGET%" call :try_fetch "https://mirrors.aliyun.com/macports/distfiles/gradle"
if not exist "%FTARGET%" call :try_fetch "https://services.gradle.org/distributions"
del "%FTMP%" >nul 2>&1
goto :eof

:try_fetch
echo        %~1/%FNAME%
where curl.exe >nul 2>&1
if errorlevel 1 goto :try_fetch_ps
curl.exe -# -f -L --ssl-no-revoke --connect-timeout 20 --retry 2 --retry-delay 3 --max-time 3600 -o "%FTMP%" "%~1/%FNAME%"
if not exist "%FTMP%" goto :eof
goto :try_fetch_check
:try_fetch_ps
powershell -NoProfile -Command "$ProgressPreference='SilentlyContinue'; try { Invoke-WebRequest -Uri '%~1/%FNAME%' -OutFile '%FTMP%' } catch { exit 1 }"
:try_fetch_check
if not exist "%FTMP%" goto :eof
for %%Z in ("%FTMP%") do if %%~zZ LSS 20000000 (
    echo        ^(too small to be the distribution, trying the next source^)
    del "%FTMP%" >nul 2>&1
    goto :eof
)
move /y "%FTMP%" "%FTARGET%" >nul
echo        kept %FTARGET%
goto :eof

:usage
echo.
echo  build-apk.bat [release^|debug] [clean] [--offline] [--no-open]
echo                [--own-key^|--no-own-key] [--no-pause]
echo.
echo    release       build the release APK  ^(default^)
echo    debug         build the debug APK
echo    clean         run "gradle clean" first
echo    --offline     build without touching the network
echo    --no-open     do not open the dist folder when finished
echo    --no-pause    do not wait for a key press at the end
echo    --own-key     sign with the key the settings name - and fail if it is not there
echo    --no-own-key  leave the APK signed with the AOSP testkey
echo                  ^(neither given: that key is used when it is there, and the AOSP
echo                   testkey kept when it is not^)
echo.
echo  Overrides: DROIDDECK_ANDROID_SDK, DROIDDECK_JAVA_HOME, DROIDDECK_NDK_HOME,
echo             DROIDDECK_GRADLE_STORE,
echo             DROIDDECK_SIGNING_ENV  ^(default: GoogleKeystore\signing.env^)
echo.
if "%PAUSE_AT_END%"=="1" pause
exit /b 0

:fail
echo.
echo  Build aborted.
if "%PAUSE_AT_END%"=="1" pause
exit /b 1
