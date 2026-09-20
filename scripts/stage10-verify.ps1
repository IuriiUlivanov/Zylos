[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"
$root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path

function Assert-File([string]$Rel) {
    $path = Join-Path $root $Rel
    if (-not (Test-Path $path)) {
        throw "Missing $Rel"
    }
}

function Assert-Contains([string]$Rel, [string]$Needle) {
    $path = Join-Path $root $Rel
    $text = Get-Content -Raw -Path $path
    if ($text -notmatch [regex]::Escape($Needle)) {
        throw "$Rel does not contain '$Needle'"
    }
}

Write-Host "Stage 10 structural checks..."

@(
    "Docs\STAGE-10-android.md",
    "scripts\build-mbtiles.ps1",
    "scripts\build-mbtiles.sh",
    "docker-compose.mobile.yml",
    "apps\android\settings.gradle",
    "apps\android\app\build.gradle",
    "apps\android\app\src\main\java\rs\zylos\app\MapActivity.kt",
    "apps\android\app\src\main\java\rs\zylos\app\map\MapStyleFactory.kt",
    "apps\android\app\src\main\java\rs\zylos\app\data\api\SearchDto.kt",
    "apps\android\app\src\main\java\rs\zylos\app\data\api\BuildingDto.kt",
    "apps\android\app\src\main\java\rs\zylos\app\data\api\OrgDto.kt",
    "apps\android\app\src\main\res\mipmap-anydpi-v26\ic_launcher.xml",
    "apps\android\branding\icon-512.png"
) | ForEach-Object { Assert-File $_ }

Assert-Contains "infra\preview\style.json" '"type": "fill-extrusion"'
Assert-Contains "apps\android\app\build.gradle" "org.maplibre.gl:android-sdk"
Assert-Contains "apps\android\app\src\main\java\rs\zylos\app\MapActivity.kt" "setMaxPitchPreference"
Assert-Contains "apps\android\app\src\main\java\rs\zylos\app\map\MapStyleFactory.kt" "mbtiles://"
Assert-Contains "apps\android\app\src\main\java\rs\zylos\app\data\api\SearchDto.kt" "building_id"
Assert-Contains "apps\android\app\src\main\java\rs\zylos\app\data\api\OrgDto.kt" "category_slug"

$mbtiles = Join-Path $root "data\tiles\novi-sad.mbtiles"
$pmtiles = Join-Path $root "data\tiles\novi-sad.pmtiles"
if (-not (Test-Path $mbtiles)) {
    throw "Missing data/tiles/novi-sad.mbtiles — run scripts/build-mbtiles.ps1"
}

$size = (Get-Item $mbtiles).Length
if ($size -lt 1MB -or $size -gt 30MB) {
    throw "MBTiles size $size is outside 1–30 MB"
}

$mount = "type=bind,source=$(Join-Path $root 'data'),target=/data"
$meta = & docker run --rm --mount $mount python:3.12-alpine python -c "import sqlite3; c=sqlite3.connect('/data/tiles/novi-sad.mbtiles'); print(dict(c.execute('SELECT name, value FROM metadata')).get('json',''))"
if ($LASTEXITCODE -ne 0) {
    throw "Failed to read MBTiles metadata"
}
$metaText = $meta -join "`n"
foreach ($layer in @("building", "housenumber", "transportation")) {
    if ($metaText -notmatch [regex]::Escape($layer)) {
        throw "MBTiles missing layer $layer"
    }
}

if (Test-Path $pmtiles) {
    Write-Host "PMTiles still present (untouched check is in build-mbtiles)."
}

$javaHome = $env:JAVA_HOME
if (-not $javaHome) {
    $jbr = "C:\Program Files\Android\Android Studio\jbr"
    if (Test-Path (Join-Path $jbr "bin\java.exe")) {
        $javaHome = $jbr
    }
}

$sdk = $env:ANDROID_HOME
if (-not $sdk) {
    $guess = Join-Path $env:LOCALAPPDATA "Android\Sdk"
    if (Test-Path $guess) { $sdk = $guess }
}

if ($javaHome -and $sdk) {
    Write-Host "Running Android unit tests..."
    $env:JAVA_HOME = $javaHome
    $env:ANDROID_HOME = $sdk
    $android = Join-Path $root "apps\android"
    Push-Location $android
    try {
        & .\gradlew.bat :app:testDebugUnitTest
        if ($LASTEXITCODE -ne 0) {
            throw "gradlew testDebugUnitTest failed (exit $LASTEXITCODE)"
        }
    }
    finally {
        Pop-Location
    }
}
else {
    Write-Warning "JDK 17 or Android SDK not found; skipped :app:testDebugUnitTest"
}

Write-Host "stage10-verify OK"
