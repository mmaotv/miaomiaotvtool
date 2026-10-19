$ErrorActionPreference = "Stop"

$SDK  = "C:\Users\Administrator\AppData\Local\Android\Sdk"
$BT   = "$SDK\build-tools\35.0.0"
$AJAR = "$SDK\platforms\android-34\android.jar"
$KS   = "C:\Users\Administrator\.android\debug.keystore"

$JAVAC = "javac"
if ($env:JAVA_HOME -and (Test-Path "$env:JAVA_HOME\bin\javac.exe")) {
    $JAVAC = "$env:JAVA_HOME\bin\javac.exe"
}

$root = $PSScriptRoot
Set-Location $root

if (Test-Path "$root\build") { Remove-Item -Recurse -Force "$root\build" }
New-Item -ItemType Directory -Path "$root\build\gen","$root\build\classes","$root\build\dex" | Out-Null

Write-Host "[1/5] aapt: generate R.java"
& "$BT\aapt.exe" package -f -m -J "$root\build\gen" -M "$root\AndroidManifest.xml" -S "$root\res" -I "$AJAR"
if ($LASTEXITCODE -ne 0) { throw "aapt(R) failed" }

Write-Host "[2/5] javac"
$srcs = @()
$srcs += (Get-ChildItem -Recurse -Filter *.java "$root\src" | ForEach-Object { $_.FullName })
$srcs += (Get-ChildItem -Recurse -Filter *.java "$root\build\gen" | ForEach-Object { $_.FullName })
& $JAVAC -encoding UTF-8 --release 8 -Xlint:-options -cp "$AJAR" -d "$root\build\classes" $srcs
if ($LASTEXITCODE -ne 0) { throw "javac failed" }

Write-Host "[3/5] d8"
$cls = (Get-ChildItem -Recurse -Filter *.class "$root\build\classes" | ForEach-Object { $_.FullName })
& "$BT\d8.bat" --lib "$AJAR" --min-api 21 --output "$root\build\dex" $cls
if ($LASTEXITCODE -ne 0) { throw "d8 failed" }

Write-Host "[4/5] package apk"
& "$BT\aapt.exe" package -f -M "$root\AndroidManifest.xml" -S "$root\res" -I "$AJAR" -F "$root\build\app.unsigned.apk"
if ($LASTEXITCODE -ne 0) { throw "aapt(package) failed" }
Copy-Item "$root\build\dex\classes.dex" "$root\build\classes.dex" -Force
Push-Location "$root\build"
& "$BT\aapt.exe" add app.unsigned.apk classes.dex
if ($LASTEXITCODE -ne 0) { Pop-Location; throw "aapt(add) failed" }
Pop-Location

Write-Host "[5/5] align + sign"
& "$BT\zipalign.exe" -f 4 "$root\build\app.unsigned.apk" "$root\build\app.aligned.apk"
if ($LASTEXITCODE -ne 0) { throw "zipalign failed" }
& "$BT\apksigner.bat" sign --ks "$KS" --ks-pass pass:android --key-pass pass:android --ks-key-alias androiddebugkey --v1-signing-enabled true --v2-signing-enabled true --out "$root\build\HomeRedirector.apk" "$root\build\app.aligned.apk"
if ($LASTEXITCODE -ne 0) { throw "sign failed" }
& "$BT\apksigner.bat" verify "$root\build\HomeRedirector.apk"

Write-Host "DONE -> $root\build\HomeRedirector.apk"
