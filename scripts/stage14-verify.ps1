[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"
$root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$apiBase = "http://127.0.0.1:3000"

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

Write-Host "Stage 14 structural checks..."

@(
    "Docs\STAGE-14-android-transit.md",
    "apps\api\src\types\route.ts",
    "apps\api\src\routes\route.ts",
    "apps\api\src\services\otpClient.ts",
    "apps\api\src\routes\route.test.ts",
    "infra\otp\Dockerfile",
    "infra\otp\router-config.json",
    "infra\otp\build-config.json",
    "infra\otp\otp-config.json",
    "apps\android\app\src\main\java\rs\zylos\app\data\api\RouteDto.kt",
    "apps\android\app\src\main\java\rs\zylos\app\data\api\ZylosApi.kt",
    "apps\android\app\src\main\java\rs\zylos\app\data\repository\RouteRepository.kt",
    "apps\android\app\src\main\java\rs\zylos\app\map\RouteLayers.kt",
    "apps\android\app\src\main\java\rs\zylos\app\viewmodel\RouteLogic.kt",
    "apps\android\app\src\main\res\layout\route_chrome.xml",
    "apps\android\app\src\test\java\rs\zylos\app\data\api\RouteDtoTest.kt",
    "apps\android\app\src\test\java\rs\zylos\app\map\RouteLogicTest.kt",
    "apps\android\app\src\test\java\rs\zylos\app\viewmodel\RouteModeTest.kt",
    "scripts\stage14-verify.ps1",
    "scripts\stage14-verify.sh",
    "scripts\stage14-verify.mjs"
) | ForEach-Object { Assert-File $_ }

@(
    "data\gtfs\jgsp\agency.txt",
    "data\gtfs\jgsp\routes.txt",
    "data\gtfs\jgsp\stops.txt",
    "data\gtfs\jgsp\stop_times.txt",
    "data\gtfs\jgsp\trips.txt",
    "data\gtfs\jgsp\calendar.txt"
) | ForEach-Object { Assert-File $_ }

Assert-Contains "apps\android\app\src\main\java\rs\zylos\app\data\api\ZylosApi.kt" "v1/route"
Assert-Contains "apps\android\app\src\main\java\rs\zylos\app\map\MapDefaults.kt" "ROUTE_CLIENT_TIMEOUT_MS = 8_000"
Assert-Contains "apps\android\app\src\main\java\rs\zylos\app\map\RouteLayers.kt" "route-walk"
Assert-Contains "apps\android\app\src\main\java\rs\zylos\app\map\RouteLayers.kt" "route-transit"
Assert-Contains "apps\android\app\src\main\java\rs\zylos\app\map\RouteLayers.kt" "lineDasharray"
Assert-Contains "apps\android\app\src\main\res\values\strings.xml" "Napravi rutu"
Assert-Contains "apps\android\app\src\main\res\values\strings.xml" "Tačke moraju biti u Novom Sadu"
Assert-Contains "apps\android\app\src\main\java\rs\zylos\app\MapActivity.kt" "TODO(stage-5)"
Assert-Contains "docker-compose.yml" "container_name: otp"
Assert-Contains "apps\api\src\services\otpClient.ts" "ROUTE_UPSTREAM_TIMEOUT_MS = 6_000"

Write-Host "Stage 14 API fixtures..."
$node = Get-Command node -ErrorAction SilentlyContinue
if (-not $node) {
    throw "node is required for scripts/stage14-verify.mjs"
}
& node (Join-Path $root "scripts\stage14-verify.mjs")
if ($LASTEXITCODE -ne 0) {
    throw "stage14-verify.mjs failed (exit $LASTEXITCODE)"
}

$apiDir = Join-Path $root "apps\api"
if (Test-Path (Join-Path $apiDir "package.json")) {
    Write-Host "Running API vitest..."
    Push-Location $apiDir
    try {
        if (-not (Test-Path (Join-Path $apiDir "node_modules\vitest"))) {
            npm ci
            if ($LASTEXITCODE -ne 0) { throw "npm ci failed in apps/api" }
        }
        npm test
        if ($LASTEXITCODE -ne 0) { throw "apps/api vitest failed" }
    }
    finally {
        Pop-Location
    }
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
    throw "JDK 17 or Android SDK not found; :app:testDebugUnitTest is required for STAGE-14"
}

Write-Host "stage14-verify OK"
