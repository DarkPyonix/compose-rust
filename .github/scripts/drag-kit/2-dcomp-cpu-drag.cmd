@echo off
rem Windows drag kit. Route 2: DirectComposition, drag steps drawn on the CPU (the default).
rem
rem Starts the sample-*.exe in this folder on this route, with each resize step's timing
rem printed (DXC_REPORT_LATENCY=1), and writes everything it prints to a log beside it,
rem named for the route and the time it started. The route variables are set or cleared
rem here and only here, so what the machine has set elsewhere does not change the route.
rem The executable is started directly rather than with start, so this window waits for
rem it and the redirection reaches it. It needs nothing but cmd.exe.
setlocal
set "DXC_DCOMP="
set "DXC_DCOMP_GPU_DRAG="
set "DXC_REPORT_LATENCY=1"
set "exe="
for %%f in ("%~dp0sample-*.exe") do set "exe=%%~ff"
if not defined exe goto :missing
for %%f in ("%exe%") do set "name=%%~nf"
rem The time, made safe for a file name whatever the machine's date and time format is.
set "stamp=%date%-%time%"
set "stamp=%stamp:/=-%"
set "stamp=%stamp::=-%"
set "stamp=%stamp:.=-%"
set "stamp=%stamp:,=-%"
set "stamp=%stamp: =0%"
set "stamp=%stamp:(=%"
set "stamp=%stamp:)=%"
set "log=%~dp0%name%-2-dcomp-cpu-drag-%stamp%.log"
echo 2-dcomp-cpu-drag: %name% is starting. Leave this window open; it closes with the app.
echo Log: "%log%"
> "%log%" echo drag kit: %name% 2-dcomp-cpu-drag started %date% %time%, DXC_DCOMP=[%DXC_DCOMP%] DXC_DCOMP_GPU_DRAG=[%DXC_DCOMP_GPU_DRAG%] DXC_REPORT_LATENCY=[%DXC_REPORT_LATENCY%]
"%exe%" >> "%log%" 2>&1
>> "%log%" echo drag kit: %name% 2-dcomp-cpu-drag ended %date% %time%, exit code %errorlevel%
endlocal
exit /b 0

:missing
echo There is no sample-*.exe beside %~nx0. Unzip the whole kit and run this from its folder.
pause
exit /b 1
