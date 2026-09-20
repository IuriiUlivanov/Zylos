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

Write-Host "Stage 12 structural checks..."

@(
    "Docs\mobile\STAGE-12-android-search.md",
    "apps\android\app\src\main\java\rs\zylos\app\MapActivity.kt",
    "apps\android\app\src\main\java\rs\zylos\app\map\SearchMarker.kt",
    "apps\android\app\src\main\java\rs\zylos\app\data\api\ZylosApi.kt",
    "apps\android\app\src\main\java\rs\zylos\app\data\repository\SearchRepository.kt",
    "apps\android\app\src\main\java\rs\zylos\app\data\local\ZylosDatabase.kt",
    "apps\android\app\src\main\java\rs\zylos\app\data\local\SearchHistoryDao.kt",
    "apps\android\app\src\main\java\rs\zylos\app\data\local\SearchHistoryEntity.kt",
    "apps\android\app\src\main\java\rs\zylos\app\ui\search\SearchDropdownAdapter.kt",
    "apps\android\app\src\main\res\layout\item_search_hit.xml",
    "apps\android\app\src\main\res\layout\item_search_history.xml",
    "apps\android\app\src\test\java\rs\zylos\app\data\repository\SearchRepositoryTest.kt",
    "apps\android\app\src\test\java\rs\zylos\app\viewmodel\SearchLogicTest.kt",
    "apps\android\app\src\test\java\rs\zylos\app\data\local\SearchHistoryDaoTest.kt",
    "scripts\stage12-verify.ps1",
    "scripts\stage12-verify.sh"
) | ForEach-Object { Assert-File $_ }

Assert-Contains "apps\android\app\src\main\java\rs\zylos\app\data\api\ZylosApi.kt" "v1/search"
Assert-Contains "apps\android\app\src\main\java\rs\zylos\app\map\MapDefaults.kt" "SEARCH_DEBOUNCE_MS = 150"
Assert-Contains "apps\android\app\src\main\java\rs\zylos\app\map\MapDefaults.kt" "SEARCH_MIN_LENGTH = 2"
Assert-Contains "apps\android\app\src\main\java\rs\zylos\app\map\MapDefaults.kt" "SEARCH_LIMIT = 10"
Assert-Contains "apps\android\app\src\main\java\rs\zylos\app\map\MapDefaults.kt" "FLY_DURATION_MS = 800"
Assert-Contains "apps\android\app\src\main\java\rs\zylos\app\map\SearchMarker.kt" "selected-marker"
Assert-Contains "apps\android\app\src\main\java\rs\zylos\app\data\local\SearchHistoryEntity.kt" "search_history"
Assert-Contains "apps\android\app\src\main\res\layout\activity_map.xml" "searchInput"
Assert-Contains "apps\android\app\src\main\res\layout\activity_map.xml" "searchDropdown"
Assert-Contains "apps\android\app\src\main\res\layout\activity_map.xml" "layout_gravity=`"bottom`""
Assert-Contains "apps\android\app\src\main\res\values\strings.xml" "Pretraga…"
Assert-Contains "apps\android\app\src\main\res\values\strings.xml" "Ništa nije pronađeno"
Assert-Contains "apps\android\app\src\main\res\values\strings.xml" "Pretraga privremeno nedostupna"
Assert-Contains "apps\android\app\src\main\res\values\strings.xml" "Nedavno"
Assert-Contains "apps\android\app\src\main\java\rs\zylos\app\MapActivity.kt" "TODO(stage-5)"
Assert-Contains "apps\android\app\src\main\java\rs\zylos\app\MapActivity.kt" "SearchMarker.LAYER_ID"
Assert-Contains "apps\android\app\build.gradle" "androidx.room:room-runtime"

Write-Host "Stage 12 API checks ($apiBase)..."

$health = Get-Http "$apiBase/v1/health"
if ([int]$health.StatusCode -ne 200) {
    throw "P2 GET /v1/health expected 200, got $($health.StatusCode). Start: docker compose up -d postgis meilisearch api"
}

$f1 = Get-Http "$apiBase/v1/search?q=apotek&limit=15"
if ([int]$f1.StatusCode -ne 200) {
    throw "A1 F1 expected 200, got $($f1.StatusCode)"
}
$f1Json = $f1.Content | ConvertFrom-Json
$f1Hits = @($f1Json.hits)
if ($f1Hits.Count -lt 5) {
    throw "A1 F1 hits=$($f1Hits.Count), expected >= 5"
}
$orgHits = @($f1Hits | Where-Object { $_.kind -eq "organization" })
if ($orgHits.Count -ne $f1Hits.Count) {
    throw "A1 F1 expected all hits kind=organization"
}
$pharmacy = @($f1Hits | Where-Object { $_.category_slug -eq "pharmacy" })
if ($pharmacy.Count -lt 1) {
    throw "A1 F1 expected >= 1 category_slug=pharmacy"
}
if ($f1Hits.Count -gt 15) {
    throw "A5 limit: hits=$($f1Hits.Count) exceeds 15"
}
Write-Host "A1 F1 hits=$($f1Hits.Count) pharmacy=$($pharmacy.Count)"

$f3 = Get-Http "$apiBase/v1/search?q=bulevar&limit=15"
if ([int]$f3.StatusCode -ne 200) {
    throw "A2 F3 expected 200, got $($f3.StatusCode)"
}
$f3Json = $f3.Content | ConvertFrom-Json
$f3Hits = @($f3Json.hits)
if ($f3Hits.Count -lt 10) {
    throw "A2 F3 hits=$($f3Hits.Count), expected >= 10"
}
$addrHits = @($f3Hits | Where-Object { $_.kind -eq "address" })
if ($addrHits.Count -lt 5) {
    throw "A2 F3 address hits=$($addrHits.Count), expected >= 5"
}
Write-Host "A2 F3 hits=$($f3Hits.Count) address=$($addrHits.Count)"

$f5 = Get-Http "$apiBase/v1/search?q=a"
if ([int]$f5.StatusCode -ne 200) {
    throw "A3 F5 expected 200, got $($f5.StatusCode)"
}
$f5Json = $f5.Content | ConvertFrom-Json
if (@($f5Json.hits).Count -ne 0) {
    throw "A3 F5 expected hits=[]"
}
Write-Host "A3 F5 empty OK"

$f6 = Get-Http "$apiBase/v1/search?q="
if ([int]$f6.StatusCode -ne 200) {
    throw "A4 F6 expected 200, got $($f6.StatusCode)"
}
$f6Json = $f6.Content | ConvertFrom-Json
if (@($f6Json.hits).Count -ne 0) {
    throw "A4 F6 expected hits=[]"
}
if ([int]$f6Json.processingTimeMs -gt 20) {
    throw "A4 F6 processingTimeMs=$($f6Json.processingTimeMs), expected <= 20"
}
Write-Host "A4 F6 processingTimeMs=$($f6Json.processingTimeMs)"

Write-Host "S1 p95 curl localhost..."
$times = New-Object System.Collections.Generic.List[double]
for ($i = 0; $i -lt 33; $i++) {
    $ms = [double](& curl.exe -sS -o NUL -w "%{time_total}" --max-time 15 "$apiBase/v1/search?q=apotek&limit=10")
    if ($LASTEXITCODE -ne 0) {
        throw "S1 curl failed"
    }
    $times.Add($ms * 1000.0)
}
$warm = $times.GetRange(3, 30)
$sorted = $warm | Sort-Object
$p95 = $sorted[27]
if ($p95 -gt 200) {
    throw "S1 p95=${p95}ms exceeds 200ms e2e localhost"
}
Write-Host "S1 p95=${p95}ms (n=30 after warmup)"

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
    throw "JDK 17 or Android SDK not found; :app:testDebugUnitTest is required for STAGE-12"
}

Write-Host "stage12-verify OK (F1 hits=$($f1Hits.Count) F3 hits=$($f3Hits.Count) S1 p95=${p95}ms)"
