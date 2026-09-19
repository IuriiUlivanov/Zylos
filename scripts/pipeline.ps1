[CmdletBinding()]
param(
    [switch]$Force
)

$ErrorActionPreference = "Stop"
$root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$data = Join-Path $root "data"
$osmDir = Join-Path $data "osm"
$tilesDir = Join-Path $data "tiles"
$boundaryDir = Join-Path $data "boundary"
$tmpDir = Join-Path $data "tmp"
$planetilerTmp = Join-Path $data "planetiler-tmp"
$serbiaPbf = Join-Path $osmDir "serbia-latest.osm.pbf"
$noviSadPbf = Join-Path $osmDir "novi-sad.osm.pbf"
$boundary = Join-Path $boundaryDir "grad-novi-sad.geojson"
$pmtiles = Join-Path $tilesDir "novi-sad.pmtiles"
$sourceUrl = "https://download.geofabrik.de/europe/serbia-latest.osm.pbf"
$mount = "type=bind,source=$data,target=/data"

function Assert-LastExitCode([string]$Message) {
    if ($LASTEXITCODE -ne 0) {
        throw "$Message (exit code $LASTEXITCODE)"
    }
}

function Invoke-DockerCapture([string[]]$DockerArguments) {
    $previousErrorAction = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        $output = & docker @DockerArguments 2>&1
        $exitCode = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $previousErrorAction
    }
    if ($exitCode -ne 0) {
        throw "Docker command failed (exit code $exitCode): $($output -join [Environment]::NewLine)"
    }
    return $output
}

function Invoke-Osmium {
    & docker run --rm --mount $mount iboates/osmium:latest @args
    Assert-LastExitCode "osmium failed"
}

foreach ($directory in @($osmDir, $tilesDir, $boundaryDir, $tmpDir, $planetilerTmp)) {
    New-Item -ItemType Directory -Force -Path $directory | Out-Null
}

if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    throw "Docker is required but was not found in PATH."
}
Invoke-DockerCapture @("info") | Out-Null

$downloaded = $false
$serbiaSize = if (Test-Path $serbiaPbf) { (Get-Item $serbiaPbf).Length } else { 0 }
if (-not $Force -and $serbiaSize -gt 100MB) {
    Write-Host "Serbia PBF already exists ($serbiaSize bytes); skipping download."
}
else {
    if ($Force -and (Test-Path $serbiaPbf)) {
        Remove-Item $serbiaPbf -Force
    }
    Write-Host "Downloading Serbia extract (resume enabled)..."
    & curl.exe --fail --location --retry 5 --retry-delay 3 --continue-at - `
        --output $serbiaPbf $sourceUrl
    Assert-LastExitCode "Serbia PBF download failed"
    $downloaded = $true
}

$md5Path = "$serbiaPbf.md5"
& curl.exe --fail --location --retry 5 --output $md5Path "$sourceUrl.md5"
Assert-LastExitCode "Checksum download failed"
$expectedMd5 = ((Get-Content $md5Path -Raw) -split "\s+")[0].ToUpperInvariant()
$actualMd5 = (Get-FileHash -Algorithm MD5 $serbiaPbf).Hash.ToUpperInvariant()
if ($actualMd5 -ne $expectedMd5) {
    throw "MD5 mismatch: expected $expectedMd5, got $actualMd5"
}
Write-Host "MD5 verified."

$downloadedAtPath = Join-Path $tmpDir "serbia-downloaded-at.txt"
if ($downloaded -or -not (Test-Path $downloadedAtPath)) {
    (Get-Item $serbiaPbf).LastWriteTimeUtc.ToString("yyyy-MM-ddTHH:mm:ssZ") |
        Set-Content -Encoding ascii $downloadedAtPath
}

if (-not $Force -and (Test-Path $boundary) -and (Get-Item $boundary).Length -gt 0) {
    Write-Host "Boundary already exists; skipping extraction."
}
else {
    try {
        Invoke-Osmium getid --overwrite -r -t `
            /data/osm/serbia-latest.osm.pbf r1649672 `
            -o /data/tmp/grad-novi-sad.osm.pbf
        Invoke-Osmium export --overwrite --geometry-types=polygon `
            /data/tmp/grad-novi-sad.osm.pbf `
            -o /data/boundary/grad-novi-sad.geojson
        Write-Host "Boundary extracted from the Serbia PBF."
    }
    catch {
        Write-Warning "osmium boundary export failed; using the documented polygon fallback."
        & curl.exe --fail --location --retry 5 --output $boundary `
            "https://polygons.openstreetmap.fr/get_geojson.py?id=1649672&params=0"
        Assert-LastExitCode "Boundary fallback download failed"
    }
}

$geojson = Get-Content $boundary -Raw | ConvertFrom-Json
$geometryTypes = if ($geojson.type -eq "FeatureCollection") {
    @($geojson.features | ForEach-Object { $_.geometry.type })
}
elseif ($geojson.type -eq "Feature") {
    @($geojson.geometry.type)
}
else {
    @($geojson.type)
}
if (-not ($geometryTypes | Where-Object { $_ -in @("Polygon", "MultiPolygon") })) {
    throw "Boundary GeoJSON does not contain a Polygon or MultiPolygon."
}

if (-not $Force -and (Test-Path $noviSadPbf) -and (Get-Item $noviSadPbf).Length -gt 0) {
    Write-Host "Novi Sad extract already exists; skipping extraction."
}
else {
    Invoke-Osmium extract --overwrite `
        --strategy=complete_ways `
        --polygon=/data/boundary/grad-novi-sad.geojson `
        /data/osm/serbia-latest.osm.pbf `
        -o /data/osm/novi-sad.osm.pbf
}

$fileInfo = Invoke-DockerCapture @(
    "run", "--rm", "--mount", $mount, "iboates/osmium:latest",
    "fileinfo", "-e", "/data/osm/novi-sad.osm.pbf"
)
$fileInfo | Tee-Object -FilePath (Join-Path $tmpDir "novi-sad-fileinfo.txt")

if (-not $Force -and (Test-Path $pmtiles) -and (Get-Item $pmtiles).Length -gt 0) {
    Write-Host "Novi Sad PMTiles already exists; skipping build."
}
else {
    & docker run --rm `
        -e "JAVA_TOOL_OPTIONS=-Xmx4g" `
        --mount $mount `
        ghcr.io/onthegomap/planetiler:latest `
        --osm-path=/data/osm/novi-sad.osm.pbf `
        --output=/data/tiles/novi-sad.pmtiles `
        --download `
        --maxzoom=14 `
        --force
    Assert-LastExitCode "Planetiler build failed"
}

$pmtilesInfo = Invoke-DockerCapture @(
    "run", "--rm", "--mount", $mount, "protomaps/go-pmtiles:latest",
    "show", "--metadata", "/data/tiles/novi-sad.pmtiles"
)
$pmtilesInfo | Tee-Object -FilePath (Join-Path $tmpDir "novi-sad-pmtiles.txt")
$pmtilesText = $pmtilesInfo -join "`n"
foreach ($layer in @("building", "housenumber", "transportation")) {
    if ($pmtilesText -notmatch [regex]::Escape($layer)) {
        throw "Required layer '$layer' was not found in PMTiles metadata."
    }
}

function Read-FileInfoValue([string]$Label) {
    $pattern = "^\s*$([regex]::Escape($Label))\s*:"
    $line = $fileInfo | Where-Object { $_ -match $pattern } |
        Select-Object -First 1
    if ($line) { return ($line -split ":", 2)[1].Trim() }
    return "unknown"
}

$copyright = [char]0x00A9
$readme = @"
# Extract Novi Sad

- OSM source: $sourceUrl
- Downloaded at: $((Get-Content $downloadedAtPath -Raw).Trim())
- Boundary: OSM relation 1649672 (Grad Novi Sad)
- License: ODbL, $copyright OpenStreetMap contributors
- serbia-latest.osm.pbf: $((Get-Item $serbiaPbf).Length) bytes
- novi-sad.osm.pbf: $((Get-Item $noviSadPbf).Length) bytes
- novi-sad.pmtiles: $((Get-Item $pmtiles).Length) bytes
- osmium fileinfo bbox: $(Read-FileInfoValue "Bounding box")
- OSM entities: nodes=$(Read-FileInfoValue "Number of nodes"), ways=$(Read-FileInfoValue "Number of ways"), relations=$(Read-FileInfoValue "Number of relations")
- planetiler maxzoom: 14
"@
$readme.TrimEnd() | Set-Content -Encoding utf8 (Join-Path $data "README.md")

Push-Location $root
try {
    & docker compose up -d --wait
    Assert-LastExitCode "docker compose failed to become healthy"

    & docker compose exec -T postgis sh -c `
        'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "SELECT PostGIS_Version();"'
    Assert-LastExitCode "PostGIS extension check failed"
}
finally {
    Pop-Location
}

& (Join-Path $root "scripts\stage01-verify.ps1")

Write-Host ""
Write-Host "Stage 1 services are ready:"
Write-Host "  Preview: http://localhost:8080/"
Write-Host "  Map verify: passed"
