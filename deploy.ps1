# deploy.ps1 — 构建并部署模块到真机（含强制重启宿主，Xposed 模块更新必须重启宿主才生效）
Set-Location $PSScriptRoot
.\gradlew.bat :app:assembleDebug --console=plain 2>&1 | Select-String 'BUILD'
if ($LASTEXITCODE -ne 0) { Write-Host "构建失败"; exit 1 }
adb install -r app\build\outputs\apk\debug\app-debug.apk
adb shell am force-stop com.kugou.android.lite
Start-Sleep -Seconds 2
adb shell monkey -p com.kugou.android.lite -c android.intent.category.LAUNCHER 1 | Out-Null
Write-Host "已部署并重启酷狗。日志: adb shell su -c 'tail -50 /data/data/com.kugou.android.lite/files/kugou_vip_module.log'"
