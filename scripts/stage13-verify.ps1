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

function Get-Http([string]$Url) {
    $tmp = [System.IO.Path]::GetTempFileName()
    try {
        $code = & curl.exe -sS -o $tmp -w "%{http_code}" --max-time 15 $Url
        if ($LASTEXITCODE -ne 0) {
            throw "Request failed $Url (curl exit $LASTEXITCODE)"
        }
        $body = Get-Content -Raw -Path $tmp -ErrorAction SilentlyContinue
        return [pscustomobject]@{ StatusCode = [int]$code; Content = $body }
    } finally {
        Remove-Item -Force $tmp -ErrorAction SilentlyContinue
    }
}

Write-Host "Stage 13 structural checks..."

@(
    "Docs\STAGE-13-android-poi.md",
    "Docs\design\MOBILE-POI-ZOOM.md",
    "infra\preview\style-mobile.json",
    "infra\preview\style.json",
    "apps\android\app\src\main\java\rs\zylos\app\MapActivity.kt",
    "apps\android\app\src\main\java\rs\zylos\app\map\OrgPinLogic.kt",
    "apps\android\app\src\main\java\rs\zylos\app\map\OrgPins.kt",
    "apps\android\app\src\main\java\rs\zylos\app\map\SearchPins.kt",
    "apps\android\app\src\main\java\rs\zylos\app\map\MapStyleFactory.kt",
    "apps\android\app\src\main\java\rs\zylos\app\data\api\ZylosApi.kt",
    "apps\android\app\src\main\java\rs\zylos\app\data\repository\OrgRepository.kt",
    "apps\android\app\src\test\java\rs\zylos\app\map\OrgPinLogicTest.kt",
    "apps\android\app\src\test\java\rs\zylos\app\data\repository\OrgRepositoryTest.kt",
    "apps\android\app\src\test\java\rs\zylos\app\viewmodel\SearchMultiLogicTest.kt",
    "apps\android\app\src\test\java\rs\zylos\app\map\MapStyleFactoryTest.kt",
    "scripts\stage13-verify.ps1",
    "scripts\stage13-verify.sh"
) | ForEach-Object { Assert-File $_ }

Assert-Contains "apps\android\app\src\main\java\rs\zylos\app\data\api\ZylosApi.kt" "v1/orgs"
Assert-Contains "apps\android\app\src\main\java\rs\zylos\app\data\api\ZylosApi.kt" "@Query(`"bbox`")"
Assert-Contains "apps\android\app\src\main\java\rs\zylos\app\data\repository\OrgRepository.kt" "inBbox"
Assert-Contains "apps\android\app\src\main\java\rs\zylos\app\map\MapDefaults.kt" "ORG_PINS_DEBOUNCE_MS = 300"
Assert-Contains "apps\android\app\src\main\java\rs\zylos\app\map\MapDefaults.kt" "ORG_PINS_MIN_ZOOM = 15"
Assert-Contains "apps\android\app\src\main\java\rs\zylos\app\map\MapDefaults.kt" "ORG_PINS_LIMIT_MAX = 200"
Assert-Contains "apps\android\app\src\main\java\rs\zylos\app\map\MapDefaults.kt" "SEARCH_MULTI_MAX_PINS = 15"
Assert-Contains "apps\android\app\src\main\java\rs\zylos\app\map\MapDefaults.kt" "STYLE_ASSET = `"style-mobile.json`""
Assert-Contains "apps\android\app\src\main\java\rs\zylos\app\map\OrgPins.kt" "org-pins"
Assert-Contains "apps\android\app\src\main\java\rs\zylos\app\map\SearchPins.kt" "search-pins"
Assert-Contains "apps\android\app\src\main\java\rs\zylos\app\map\SearchMarker.kt" "selected-marker"
Assert-Contains "apps\android\app\src\main\res\layout\activity_map.xml" "showAllOnMap"
Assert-Contains "apps\android\app\src\main\res\values\strings.xml" "Prikaži sve na karti"
Assert-Contains "apps\android\app\src\main\res\values\strings.xml" "Rezultati pretrage"
Assert-Contains "apps\android\app\src\main\java\rs\zylos\app\MapActivity.kt" "TODO(stage-5)"
Assert-Contains "apps\android\app\src\main\java\rs\zylos\app\MapActivity.kt" "OrgPins.CIRCLE_LAYER_ID"
Assert-Contains "apps\android\app\src\main\java\rs\zylos\app\viewmodel\SearchLogic.kt" "isMultiEligible"
Assert-Contains "apps\android\app\build.gradle" "style-mobile.json"
Assert-Contains "infra\preview\style-mobile.json" "poi-dot"
Assert-Contains "infra\preview\style-mobile.json" "poi-label"
Assert-Contains "infra\preview\style-mobile.json" """rank""], 99], 25"
Assert-Contains "infra\preview\style-mobile.json" """rank""], 99], 8"
Assert-Contains "infra\preview\style.json" "fill-extrusion"

Write-Host "Stage 13 API checks ($apiBase)..."

$health = Get-Http "$apiBase/v1/health"
if ([int]$health.StatusCode -ne 200) {
    throw "P2 GET /v1/health expected 200, got $($health.StatusCode). Start: docker compose up -d postgis api"
}

$bbox = Get-Http "$apiBase/v1/orgs?bbox=19.83,45.24,19.86,45.26&limit=40"
if ([int]$bbox.StatusCode -ne 200) {
    throw "A1 bbox expected 200, got $($bbox.StatusCode)"
}
$pins = @($bbox.Content | ConvertFrom-Json)
if ($pins.Count -lt 1) {
    throw "A1 bbox returned 0 pins"
}
if ($pins.Count -gt 40) {
    throw "A2 limit=40 returned $($pins.Count) pins"
}
foreach ($pin in $pins) {
    if (-not $pin.id -or -not $pin.name -or $null -eq $pin.lon -or $null -eq $pin.lat) {
        throw "A1 pin missing id/name/lon/lat"
    }
}
Write-Host "B5a/B5c pins=$($pins.Count) (limit 40)"

$bboxMax = Get-Http "$apiBase/v1/orgs?bbox=19.80,45.20,19.90,45.30&limit=200"
if ([int]$bboxMax.StatusCode -ne 200) {
    throw "A3 limit max expected 200 HTTP, got $($bboxMax.StatusCode)"
}
$maxPins = @($bboxMax.Content | ConvertFrom-Json)
if ($maxPins.Count -gt 200) {
    throw "A3 server returned $($maxPins.Count) pins, max 200"
}
Write-Host "A3 pins=$($maxPins.Count) (<=200)"

$bad = Get-Http "$apiBase/v1/orgs?bbox=foo"
if ([int]$bad.StatusCode -ne 400) {
    throw "A4 invalid bbox expected 400, got $($bad.StatusCode)"
}
$badJson = $bad.Content | ConvertFrom-Json
if ($badJson.error -ne "invalid_bbox") {
    throw "A4 expected error=invalid_bbox, got $($badJson.error)"
}
Write-Host "A4 invalid_bbox OK"

$search = Get-Http "$apiBase/v1/search?q=apotek&limit=15"
if ([int]$search.StatusCode -ne 200) {
    throw "X7 search regression expected 200, got $($search.StatusCode)"
}
$searchJson = $search.Content | ConvertFrom-Json
$searchHits = @($searchJson.hits)
if ($searchHits.Count -lt 3) {
    throw "X1 apotek hits=$($searchHits.Count), expected >= 3"
}
Write-Host "X1/X7 search hits=$($searchHits.Count)"

Write-Host "A5 p95 bbox curl localhost..."
$times = New-Object System.Collections.Generic.List[double]
for ($i = 0; $i -lt 33; $i++) {
    $ms = [double](& curl.exe -sS -o NUL -w "%{time_total}" --max-time 15 "$apiBase/v1/orgs?bbox=19.83,45.24,19.86,45.26&limit=40")
    if ($LASTEXITCODE -ne 0) {
        throw "A5 curl failed"
    }
    $times.Add($ms * 1000.0)
}
$warm = $times.GetRange(3, 30)
$sorted = $warm | Sort-Object
$p95 = $sorted[27]
if ($p95 -gt 150) {
    throw "A5 p95=${p95}ms exceeds 150ms bbox localhost"
}
Write-Host "A5 p95=${p95}ms (n=30 after warmup)"

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
    throw "JDK 17 or Android SDK not found; :app:testDebugUnitTest is required for STAGE-13"
}

Write-Host "stage13-verify OK (bbox pins=$($pins.Count) p95=${p95}ms search=$($searchHits.Count))"
