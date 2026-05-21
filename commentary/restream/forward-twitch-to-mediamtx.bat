@echo off
setlocal EnableExtensions

REM ============================================================
REM Twitch -> python -m streamlink -> FFmpeg -> MediaMTX over RTMP
REM No transcoding. Remux only.
REM
REM Usage:
REM   forward-twitch-to-mediamtx.bat MAIN_CHANNEL
REM   forward-twitch-to-mediamtx.bat MAIN_CHANNEL ALT_CHANNEL
REM   MAIN_CHANNEL and ALT_CHANNEL may also be Twitch URLs.
REM
REM Behavior:
REM   - MediaMTX path always uses MAIN_CHANNEL.
REM   - If ALT_CHANNEL is provided:
REM       1. Try ALT_CHANNEL first.
REM       2. When ALT_CHANNEL is offline or ends, wait 5 seconds.
REM       3. Try MAIN_CHANNEL.
REM       4. When MAIN_CHANNEL is offline or ends, wait 60 seconds.
REM       5. Repeat.
REM   - If only MAIN_CHANNEL is provided:
REM       1. Try MAIN_CHANNEL.
REM       2. When it is offline or ends, wait 60 seconds.
REM       3. Repeat.
REM ============================================================

REM ----- Manually editable settings -----

set "TWITCH_BASE_URL=https://www.twitch.tv"
set "MEDIAMTX_RTMP_BASE_URL=rtmp://127.0.0.1:1935"

REM Examples: best, 1080p60, 720p60, 720p,best
set "TWITCH_QUALITY=best"

set "PYTHON_EXE=python"
set "STREAMLINK_MODULE=-m streamlink"
set "FFMPEG_EXE=ffmpeg"

REM Twitch-side live edge.
REM Lower = less latency, higher stall risk.
REM Recommended starting point: 2
set "STREAMLINK_EXTRA_ARGS=--hls-live-edge 1"

REM Keep empty unless needed.
set "FFMPEG_INPUT_EXTRA_ARGS="
set "FFMPEG_OUTPUT_EXTRA_ARGS="

REM Delay after alt account ends/offline before trying main account.
set "ALT_TO_MAIN_WAIT_SECONDS=5"

REM Delay before restarting the whole check loop.
set "RELOOP_WAIT_SECONDS=60"

REM ----- End editable settings -----

if "%~1"=="" (
    echo Error: missing main Twitch channel name.
    echo.
    echo Usage:
    echo   %~nx0 MAIN_CHANNEL
    echo   %~nx0 MAIN_CHANNEL ALT_CHANNEL
    exit /b 1
)

if not "%~3"=="" (
    echo Error: too many arguments.
    echo.
    echo Usage:
    echo   %~nx0 MAIN_CHANNEL
    echo   %~nx0 MAIN_CHANNEL ALT_CHANNEL
    exit /b 1
)

call :NormalizeTwitchChannel "%~1" MAIN_CHANNEL
if not "%~2"=="" call :NormalizeTwitchChannel "%~2" ALT_CHANNEL
set "MEDIAMTX_RTMP_URL=%MEDIAMTX_RTMP_BASE_URL%/%MAIN_CHANNEL%"

echo Main channel: %MAIN_CHANNEL%
if not "%ALT_CHANNEL%"=="" echo Alt channel:  %ALT_CHANNEL%
echo MediaMTX:     %MEDIAMTX_RTMP_URL%
echo Quality:      %TWITCH_QUALITY%
echo.
echo Press Ctrl+C to stop.
echo.

:LOOP

if not "%ALT_CHANNEL%"=="" (
    call :RunChannel "%ALT_CHANNEL%" "%MAIN_CHANNEL%"

    echo.
    echo Alt channel ended or unavailable. Waiting %ALT_TO_MAIN_WAIT_SECONDS% seconds before checking main channel...
    timeout /t %ALT_TO_MAIN_WAIT_SECONDS% /nobreak >nul
)

call :RunChannel "%MAIN_CHANNEL%" "%MAIN_CHANNEL%"

echo.
echo Waiting %RELOOP_WAIT_SECONDS% seconds before re-looping...
timeout /t %RELOOP_WAIT_SECONDS% /nobreak >nul
goto :LOOP


:RunChannel
set "SOURCE_CHANNEL=%~1"
set "PATH_CHANNEL=%~2"
set "TWITCH_URL=%TWITCH_BASE_URL%/%SOURCE_CHANNEL%"
set "OUTPUT_URL=%MEDIAMTX_RTMP_BASE_URL%/%PATH_CHANNEL%"

echo ------------------------------------------------------------
echo Checking source channel: %SOURCE_CHANNEL%
echo Twitch URL:             %TWITCH_URL%
echo MediaMTX path channel:  %PATH_CHANNEL%
echo Output:                 %OUTPUT_URL%
echo ------------------------------------------------------------

"%PYTHON_EXE%" %STREAMLINK_MODULE% %STREAMLINK_EXTRA_ARGS% --stdout "%TWITCH_URL%" "%TWITCH_QUALITY%" | "%FFMPEG_EXE%" -hide_banner -loglevel info -re %FFMPEG_INPUT_EXTRA_ARGS% -i pipe:0 -map 0:v:0 -map 0:a:0 -c copy -dn %FFMPEG_OUTPUT_EXTRA_ARGS% -f flv "%OUTPUT_URL%"

set "RUN_EXIT_CODE=%ERRORLEVEL%"
echo.
echo Source channel %SOURCE_CHANNEL% ended or failed. Exit code: %RUN_EXIT_CODE%
exit /b %RUN_EXIT_CODE%


:NormalizeTwitchChannel
setlocal EnableDelayedExpansion
set "NORMALIZED_CHANNEL=%~1"
set "NORMALIZED_CHANNEL=!NORMALIZED_CHANNEL:https://www.twitch.tv/=!"
set "NORMALIZED_CHANNEL=!NORMALIZED_CHANNEL:http://www.twitch.tv/=!"
set "NORMALIZED_CHANNEL=!NORMALIZED_CHANNEL:https://twitch.tv/=!"
set "NORMALIZED_CHANNEL=!NORMALIZED_CHANNEL:http://twitch.tv/=!"
set "NORMALIZED_CHANNEL=!NORMALIZED_CHANNEL:www.twitch.tv/=!"
set "NORMALIZED_CHANNEL=!NORMALIZED_CHANNEL:twitch.tv/=!"
for /f "tokens=1 delims=/?" %%A in ("!NORMALIZED_CHANNEL!") do set "NORMALIZED_CHANNEL=%%A"
endlocal & set "%~2=%NORMALIZED_CHANNEL%"
exit /b 0
