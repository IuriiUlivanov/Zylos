[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"
$root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$apiBase = "http://127.0.0.1:3000"
$f6Lon = "19.843486795879464"
$f6Lat = "45.24576475"

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

Write-Host "Stage 11 structural checks..."

@(
    "Docs\mobile\STAGE-11-android-building.md",
    "apps\android\app\src\main\java\rs\zylos\app\MapActivity.kt",
    "apps\android\app\src\main\java\rs\zylos\app\map\BuildingHighlight.kt",
    "apps\android\app\src\main\java\rs\zylos\app\data\api\ZylosApi.kt",
    "apps\android\app\src\main\java\rs\zylos\app\data\api\ApiClient.kt",
    "apps\android\app\src\main\java\rs\zylos\app\data\repository\BuildingRepository.kt",
    "apps\android\app\src\main\java\rs\zylos\app\data\repository\OrgRepository.kt",
    "apps\android\app\src\main\java\rs\zylos\app\viewmodel\MapViewModel.kt",
    "apps\android\app\src\test\java\rs\zylos\app\map\BuildingHighlightTest.kt",
    "apps\android\app\src\test\java\rs\zylos\app\data\api\ApiDtoContractTest.kt",
    "scripts\stage11-verify.ps1",
    "scripts\stage11-verify.sh"
) | ForEach-Object { Assert-File $_ }

Assert-Contains "apps\android\app\build.gradle" "com.squareup.retrofit2:retrofit"
Assert-Contains "apps\android\app\src\main\java\rs\zylos\app\data\api\ApiClient.kt" "BuildConfig.API_URL" 
Assert-Contains "apps\android\app\src\main\java\rs\zylos\app\data\api\ZylosApi.kt" "v1/buildings/at"
Assert-Contains "apps\android\app\src\main\java\rs\zylos\app\data\api\ZylosApi.kt" "v1/buildings/{id}"
Assert-Contains "apps\android\app\src\main\java\rs\zylos\app\data\api\ZylosApi.kt" "v1/orgs/{id}"
Assert-Contains "apps\android\app\src\main\java\rs\zylos\app\MapActivity.kt" "addOnMapClickListener"
Assert-Contains "apps\android\app\src\main\java\rs\zylos\app\MapActivity.kt" "TODO(stage-5)"
Assert-Contains "apps\android\app\src\main\java\rs\zylos\app\map\BuildingHighlight.kt" "selected-building"
Assert-Contains "apps\android\app\src\main\java\rs\zylos\app\map\BuildingHighlight.kt" "selected-building-outline"
Assert-Contains "apps\android\app\src\main\res\layout\activity_map.xml" "BottomSheetBehavior"
Assert-Contains "apps\android\app\src\main\res\values\strings.xml" "Nema zgrade na ovoj tački"
Assert-Contains "apps\android\app\src\main\res\values\strings.xml" "Van grada Novi Sad"
Assert-Contains "apps\android\app\src\main\res\values\strings.xml" "Nema veze sa serverom"
Assert-Contains "apps\android\app\src\main\java\rs\zylos\app\map\MapDefaults.kt" "SHEET_ANIMATION_MS = 250"
Assert-Contains "apps\android\app\src\main\java\rs\zylos\app\MapActivity.kt" "isAttributionEnabled = true"

Write-Host "Stage 11 API checks ($apiBase)..."

$health = Get-Http "$apiBase/v1/health"
if ([int]$health.StatusCode -ne 200) {
    throw "P2 GET /v1/health expected 200, got $($health.StatusCode). Start: docker compose up -d postgis api"
}

$atProbe = Get-Http "$apiBase/v1/buildings/at?lon=19.845&lat=45.255"
$probeCode = [int]$atProbe.StatusCode
if ($probeCode -ge 500) {
    throw "P3 /buildings/at returned $probeCode"
}

$at = Get-Http "$apiBase/v1/buildings/at?lon=$f6Lon&lat=$f6Lat"
if ([int]$at.StatusCode -ne 200) {
    throw "A1 F6 /buildings/at expected 200, got $($at.StatusCode)"
}
$atJson = $at.Content | ConvertFrom-Json
if (-not $atJson.id) {
    throw "A1 F6 response missing id"
}

$detail = Get-Http "$apiBase/v1/buildings/$($atJson.id)"
if ([int]$detail.StatusCode -ne 200) {
    throw "A2 F6 /buildings/:id expected 200, got $($detail.StatusCode)"
}
$detailJson = $detail.Content | ConvertFrom-Json
$orgCount = @($detailJson.organizations).Count
if ($orgCount -lt 2) {
    throw "A2 F6 organizations.length=$orgCount, expected >= 2"
}

$geom = $detailJson.geometry | ConvertTo-Json -Compress -Depth 20
$geomBytes = [System.Text.Encoding]::UTF8.GetByteCount($geom)
if ($geomBytes -gt 51200) {
    throw "A4 geometry is $geomBytes bytes, budget 50 KB"
}

$orgId = @($detailJson.organizations)[0].id
$org = Get-Http "$apiBase/v1/orgs/$([uri]::EscapeDataString($orgId))"
if ([int]$org.StatusCode -ne 200) {
    throw "A3 /orgs/:id expected 200, got $($org.StatusCode)"
}
$orgJson = $org.Content | ConvertFrom-Json
if (-not $orgJson.name) {
    throw "A3 org missing name"
}

$outside = Get-Http "$apiBase/v1/buildings/at?lon=20.46&lat=44.817"
if ([int]$outside.StatusCode -ne 422) {
    throw "outside city expected 422, got $($outside.StatusCode)"
}

$emptyId = $null
Push-Location $root
try {
    $emptyRaw = docker compose exec -T postgis sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -t -A -c "SELECT b.id::text FROM public.building b WHERE NOT EXISTS (SELECT 1 FROM public.organization o WHERE o.building_id = b.id) LIMIT 1;"' | Out-String
    if ($emptyRaw -match '([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})') {
        $emptyId = $Matches[1]
    } else {
        throw "A5 no empty building id from PostGIS (psql output: $emptyRaw)"
    }
} catch {
    throw "A5 could not query empty building via docker compose exec postgis: $($_.Exception.Message)"
} finally {
    Pop-Location
}

$empty = Get-Http "$apiBase/v1/buildings/$emptyId"
if ([int]$empty.StatusCode -ne 200) {
    throw "A5 empty building HTTP $($empty.StatusCode)"
}
$emptyJson = $empty.Content | ConvertFrom-Json
$emptyOrgs = @($emptyJson.organizations).Count
if ($emptyOrgs -ne 0) {
    throw "A5 expected organizations=[], got $emptyOrgs"
}
Write-Host "A5 empty building $emptyId orgs=0"

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
    throw "JDK 17 or Android SDK not found; :app:testDebugUnitTest is required for STAGE-11"
}

Write-Host "stage11-verify OK (F6 id=$($atJson.id) orgs=$orgCount geometry=${geomBytes}B)"
