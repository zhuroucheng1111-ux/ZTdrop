[CmdletBinding()]
param([string]$BuildDirectory, [string]$OutputDirectory)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$build = if ($BuildDirectory) { [IO.Path]::GetFullPath($BuildDirectory) } else { Join-Path $root 'android/build/manual-release' }
$output = if ($OutputDirectory) { [IO.Path]::GetFullPath($OutputDirectory) } else { Join-Path $root 'release' }
$tools = "$env:LOCALAPPDATA/Android/Sdk/build-tools/36.0.0"
$androidJar = Join-Path $root 'release/android-toolchain/sdk/platforms/android-35/android.jar'
$qrJar = Join-Path $root 'release/android-toolchain/zxing/core-3.5.3.jar'
if ((Get-FileHash $qrJar -Algorithm SHA1).Hash -ne 'CA1349214A356CD7958651B2D5A0E1F3811A9C4B') { throw 'ZXing integrity mismatch' }
$cache = "$env:USERPROFILE/.gradle/caches/modules-2/files-2.1"
$jars = @('kotlin-compiler-embeddable-2.2.20.jar','kotlin-stdlib-2.2.0.jar','kotlin-script-runtime-2.2.20.jar',
    'kotlin-reflect-1.6.10.jar','kotlinx-coroutines-core-jvm-1.9.0.jar','annotations-13.0.jar') | ForEach-Object {
    $match = Get-ChildItem $cache -Recurse -Filter $_ -File | Select-Object -First 1 -ExpandProperty FullName
    if (-not $match) { throw "Missing $_" }; $match
}
function Check([string]$step) { if ($LASTEXITCODE -ne 0) { throw "$step exit=$LASTEXITCODE" } }
New-Item -ItemType Directory -Force $build,"$build/gen","$build/classes","$build/dex" | Out-Null
$source = Join-Path $root 'android/app/src/main'
$manifest = [System.IO.File]::ReadAllText("$source/AndroidManifest.xml")
$config = [System.IO.File]::ReadAllText((Join-Path $root 'android/app/build.gradle.kts'))
$versionCode = [regex]::Match($config,'versionCode = (\d+)').Groups[1].Value
$versionName = [regex]::Match($config,'versionName = "([^"]+)"').Groups[1].Value
$manifest = $manifest.Replace('<manifest xmlns:', "<manifest package=`"com.ztdrop.android`" android:versionCode=`"$versionCode`" android:versionName=`"$versionName`" xmlns:")
$manifest = $manifest.Replace('    <application', '    <uses-sdk android:minSdkVersion="26" android:targetSdkVersion="35" />' + "`n    <application")
[System.IO.File]::WriteAllText("$build/AndroidManifest.xml", $manifest, [System.Text.UTF8Encoding]::new($false))
& "$tools/aapt2.exe" compile --dir "$source/res" -o "$build/compiled.zip"; Check 'aapt2 compile'
& "$tools/aapt2.exe" link -o "$build/resources.apk" -I $androidJar --manifest "$build/AndroidManifest.xml" --java "$build/gen" "$build/compiled.zip"; Check 'aapt2 link'
$java = @(Get-ChildItem "$build/gen" -Filter '*.java' -Recurse -File | ForEach-Object FullName)
& javac -source 17 -target 17 -classpath $androidJar -d "$build/classes" $java; Check 'javac'
$sources = @(Get-ChildItem "$source/java/com/ztdrop/android" -Filter '*.kt' -File | ForEach-Object FullName)
& java -cp ($jars -join ';') org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 17 `
    -classpath "$androidJar;$($jars[1]);$($jars[5]);$build/classes;$qrJar" -d "$build/classes" $sources; Check 'Kotlin'
& jar cf "$build/app-classes.jar" -C "$build/classes" .; Check 'jar'
& "$tools/d8.bat" --min-api 26 --lib $androidJar --output "$build/dex" "$build/app-classes.jar" $jars[1] $jars[5] $qrJar; Check 'D8'
Copy-Item "$build/resources.apk" "$build/unsigned.apk"
& jar uf "$build/unsigned.apk" -C "$build/dex" classes.dex; Check 'dex packaging'
& "$tools/zipalign.exe" -f 4 "$build/unsigned.apk" "$build/aligned.apk"; Check 'zipalign'
New-Item -ItemType Directory -Force $output | Out-Null
$apk = Join-Path $output "ZTDrop-Android-$versionName-build$versionCode-unsigned.apk"
Copy-Item "$build/aligned.apk" $apk
& "$tools/zipalign.exe" -c 4 $apk; Check 'alignment verify'
& "$tools/aapt2.exe" dump badging $apk | Select-Object -First 1; Check 'badging'
Write-Output "APK_BUILD_EXIT=0"
Write-Output "APK_UNSIGNED=true"
Write-Output "APK=$apk"
Get-FileHash $apk -Algorithm SHA256 | Format-List
