# Lemonade Code's updater for Windows: waits for the app to quit, installs the new MSI over the old
# version (for this user, so no admin rights) and opens the app.
param([int]$AppPid, [string]$Msi, [string]$Cleanup, [string]$App)

Wait-Process -Id $AppPid -ErrorAction SilentlyContinue

Start-Process -FilePath msiexec.exe -ArgumentList @('/i', "`"$Msi`"", '/passive', '/norestart') -Wait
Remove-Item -LiteralPath $Cleanup -Recurse -Force -ErrorAction SilentlyContinue
Start-Process -FilePath $App
Remove-Item -LiteralPath $PSCommandPath -Force -ErrorAction SilentlyContinue
