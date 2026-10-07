@echo off
rem ===========================================================================
rem  DroidDeck - Steam client package downloader (Windows)
rem
rem  Usage:  download-steam-client.bat [options]
rem
rem    (no options)      download the 17 packages into .\FirstLocalInstall\steam-client
rem    --list            only print the manifest and the package URLs
rem    --channel NAME    publicbeta | steamdeck_publicbeta (default)
rem    --host HOST       a different copy of Valve's CDN
rem    --out DIR         put the packages somewhere else
rem    --no-open         do not open the folder when finished
rem    --no-pause        do not wait for a key press at the end
rem
rem  The arm64 Steam client is not one file: it is 17 zip packages, about
rem  343 MiB, listed in a small text manifest on Valve's CDN. DroidDeck can
rem  install them from a folder on the device, so the whole point of this
rem  script is to get that folder down on a machine whose connection to Valve
rem  is good, then copy it across.
rem
rem  Every package is verified against the sha256 the manifest carries, and
rem  the manifest itself is written into the folder, so the device can check
rem  the lot without reaching Valve at all. Run this again any time: finished
rem  files are verified and skipped, partial ones resume.
rem
rem  It fills .\FirstLocalInstall\steam-client, the folder this checkout keeps its local-install
rem  payloads in - the Linux runtime, Valve's Proton seed and the third-party Protons sit beside it.
rem
rem  Then copy the packages to the device, for example
rem      adb push FirstLocalInstall\steam-client /sdcard/Download/steam-client
rem  and pick "Install from folder" under Steam client in DroidDeck's
rem  settings.
rem
rem  Overrides (set before running):
rem    DROIDDECK_PYTHON     the python.exe to use
rem ===========================================================================

setlocal EnableExtensions

set "ROOT=%~dp0"
if "%ROOT:~-1%"=="\" set "ROOT=%ROOT:~0,-1%"

set "OUT=%ROOT%\FirstLocalInstall\steam-client"
set "OPEN_OUT=1"
set "PAUSE_AT_END=1"
set "EXTRA="
set "LIST_ONLY="
set "OTHER_OUT="

:parse_args
if "%~1"=="" goto args_done
rem  Two kinds of options: the ones only this script knows, which the Python
rem  tool would reject, and the ones it takes as well, which have to be kept
rem  so it can act on them.
set "FWD=1"
if /i "%~1"=="--no-open"  (set "OPEN_OUT=0"     & set "FWD=")
if /i "%~1"=="--no-pause" (set "PAUSE_AT_END=0" & set "FWD=")
if /i "%~1"=="-h"         (set "FWD=" & goto usage)
if /i "%~1"=="--help"     (set "FWD=" & goto usage)
if /i "%~1"=="/?"         (set "FWD=" & goto usage)
if /i "%~1"=="--list" set "LIST_ONLY=1"
if /i "%~1"=="--out"  set "OTHER_OUT=1"
rem  Re-quoted on the way through: a path with a space in it (--out "D:\My
rem  Steam") would otherwise arrive in two pieces.
if defined FWD set EXTRA=%EXTRA% "%~1"
shift
goto parse_args
:args_done

if not exist "%ROOT%\tools\steam-client-fetch.py" (
    echo [ERROR] tools\steam-client-fetch.py is missing from %ROOT%
    echo         The checkout is incomplete.
    goto :fail
)

rem ---------------------------------------------------------------------------
rem  Python - the tool is a plain script, so anything 3.x will do
rem ---------------------------------------------------------------------------
set "PYTHON="
if defined DROIDDECK_PYTHON if exist "%DROIDDECK_PYTHON%" set "PYTHON=%DROIDDECK_PYTHON%"
for %%P in (python.exe python3.exe py.exe) do if not defined PYTHON set "PYTHON=%%~$PATH:P"
if not defined PYTHON for /d %%D in ("%LOCALAPPDATA%\Programs\Python\Python3*") do if exist "%%~fD\python.exe" set "PYTHON=%%~fD\python.exe"
if not defined PYTHON for /d %%D in ("C:\Program Files\Python3*") do if exist "%%~fD\python.exe" set "PYTHON=%%~fD\python.exe"
if not defined PYTHON for /d %%D in ("C:\Program Files\Python*") do if exist "%%~fD\python.exe" set "PYTHON=%%~fD\python.exe"
if not defined PYTHON for /d %%D in ("%USERPROFILE%\.workbuddy\binaries\python\versions\*") do if exist "%%~fD\python.exe" set "PYTHON=%%~fD\python.exe"
if not defined PYTHON (
    echo [ERROR] No Python found.
    echo         Install Python 3 from https://www.python.org/downloads/ and run
    echo         this script again, or point it at yours:
    echo             set DROIDDECK_PYTHON=C:\path\to\python.exe ^&^& download-steam-client.bat
    echo         The URLs are also listed in docs\development\steam-client-packages.txt,
    echo         if you would rather fetch them by hand.
    goto :fail
)
rem  "py" on a machine that never installed Python 3 would answer with 2.x,
rem  and the tool would die on a syntax error three lines in.
"%PYTHON%" -c "import sys; sys.exit(0 if sys.version_info[0] == 3 else 1)" >nul 2>&1
if errorlevel 1 (
    echo [ERROR] %PYTHON% is not Python 3.
    goto :fail
)

title DroidDeck - downloading the Steam client packages
echo.
echo ===========================================================================
echo  DroidDeck Steam client downloader
echo  project : %ROOT%
echo  target  : %OUT%
echo  python  : %PYTHON%
echo ===========================================================================
echo.

cd /d "%ROOT%"
"%PYTHON%" "%ROOT%\tools\steam-client-fetch.py" --out "%OUT%" %EXTRA%
set "STATUS=%ERRORLEVEL%"

if not "%STATUS%"=="0" (
    echo.
    echo [FAIL] The download stopped with exit code %STATUS%.
    echo        Whatever arrived is kept and already verified - just run this
    echo        script again to pick up where it left off.
    goto :fail
)
rem  --list prints and stops, and --out means the packages went to a folder
rem  this script does not know about; in both cases the tool's own output is
rem  the whole story, so there is nothing to summarise.
if defined LIST_ONLY goto :done
if defined OTHER_OUT goto :done
if not exist "%OUT%" goto :done

rem  Counting this with "dir | find /c /v ..." is the obvious way and a trap:
rem  on a machine with Git's tools on PATH, cmd can resolve "find" to the Unix
rem  one, which reads /c as the C: drive and then walks the entire disk. A
rem  plain for over dir's output needs no external program at all.
set "COUNT=0"
for /f %%c in ('dir /b /a-d "%OUT%" 2^>nul') do set /a COUNT+=1

echo.
echo ===========================================================================
echo  DOWNLOAD OK
echo ===========================================================================
echo  folder : %OUT%
echo  files  : %COUNT% ^(the 17 packages plus Valve's manifest^)
echo.
echo  Copy it to the device, for example:
echo      adb push "%OUT%" /sdcard/Download/steam-client
echo  then pick "Install from folder" under Steam client in DroidDeck's
echo  settings. The app checks every file against the manifest before it
echo  installs anything.
echo.
if "%OPEN_OUT%"=="1" start "" explorer "%OUT%"
if "%PAUSE_AT_END%"=="1" pause
exit /b 0

:done
if "%PAUSE_AT_END%"=="1" pause
exit /b 0

:usage
echo.
echo  download-steam-client.bat [--list] [--channel NAME] [--host HOST]
echo                            [--out DIR] [--no-open] [--no-pause]
echo.
echo    (no options)      download the 17 packages into
echo                      .\FirstLocalInstall\steam-client
echo    --list            only print the manifest and the package URLs
echo    --channel NAME    publicbeta ^| steamdeck_publicbeta ^(default^)
echo    --host HOST       a different copy of Valve's CDN. Default is
echo                      client-update.fastly.steamstatic.com; Valve serves
echo                      the same files from client-update.steamstatic.com,
echo                      which measured about twice as fast from one network
echo                      here. Either can be given as a whole base URL.
echo    --out DIR         put the packages somewhere else
echo    --no-open         do not open the folder when finished
echo    --no-pause        do not wait for a key press at the end
echo.
echo  Override: DROIDDECK_PYTHON
echo.
if "%PAUSE_AT_END%"=="1" pause
exit /b 0

:fail
echo.
echo  Download aborted.
if "%PAUSE_AT_END%"=="1" pause
exit /b 1
