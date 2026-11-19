@echo off
cd /d C:\Users\dezzy\antigravity\OpenTune
echo BUILD-START %DATE% %TIME% > C:\Users\dezzy\antigravity\OpenTune\build-video-fix.log
call gradlew.bat :app:assembleArm64Debug --console=plain >> C:\Users\dezzy\antigravity\OpenTune\build-video-fix.log 2>&1
echo BUILD-EXIT %ERRORLEVEL% %DATE% %TIME% >> C:\Users\dezzy\antigravity\OpenTune\build-video-fix.log
