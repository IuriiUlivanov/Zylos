[CmdletBinding()]
param(
    [switch]$Force
)

$ErrorActionPreference = "Stop"
$root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$data = Join-Path $root "data"
$tilesDir = Join-Path $data "tiles"
$tmpDir = Join-Path $data "tmp"
$pbf = Join-Path $data "osm\novi-sad.osm.pbf"
$mbtiles = Join-Path $tilesDir "novi-sad.mbtiles"
$pmtiles = Join-Path $tilesDir "novi-sad.pmtiles"
$mount = "type=bind,source=$data,target=/data"
$maxBytes = 30MB

function Assert-LastExitCode([string]$Message) {
    if ($LASTEXITCODE -ne 0) {
        throw "$Message (exit code $LASTEXITCODE)"
    }
}

if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    throw "Docker is required but was not found in PATH."
}

if (-not (Test-Path $pbf) -or (Get-Item $pbf).Length -le 0) {
    throw "Missing Novi Sad OSM extract: $pbf"
}

New-Item -ItemType Directory -Force -Path $tilesDir, $tmpDir | Out-Null

$pmtilesMtime = if (Test-Path $pmtiles) { (Get-Item $pmtiles).LastWriteTimeUtc } else { $null }

if (-not $Force -and (Test-Path $mbtiles) -and (Get-Item $mbtiles).Length -gt 0) {
    Write-Host "Novi Sad MBTiles already exists; skipping build."
}
else {
    & docker run --rm `
        -e "JAVA_TOOL_OPTIONS=-Xmx4g" `
        --mount $mount `
        ghcr.io/onthegomap/planetiler:latest `
        --osm-path=/data/osm/novi-sad.osm.pbf `
        --output=/data/tiles/novi-sad.mbtiles `
        --download `
        --maxzoom=14 `
        --force
    Assert-LastExitCode "Planetiler MBTiles build failed"
}

if ($null -ne $pmtilesMtime -and (Test-Path $pmtiles)) {
    $after = (Get-Item $pmtiles).LastWriteTimeUtc
    if ($after -ne $pmtilesMtime) {
        throw "R2 failed: data/tiles/novi-sad.pmtiles mtime changed"
    }
}

$size = (Get-Item $mbtiles).Length
if ($size -lt 1MB -or $size -gt $maxBytes) {
    throw "MBTiles size $size bytes is outside 1–30 MB budget"
}

$metadata = & docker run --rm --mount $mount python:3.12-alpine python -c "import sqlite3; c=sqlite3.connect('/data/tiles/novi-sad.mbtiles'); print(dict(c.execute('SELECT name, value FROM metadata')).get('json',''))"
Assert-LastExitCode "Failed to read MBTiles metadata"

$metaPath = Join-Path $tmpDir "novi-sad-mbtiles.json"
$metadata | Set-Content -Encoding utf8 $metaPath
$metaText = $metadata -join "`n"
foreach ($layer in @("building", "housenumber", "transportation")) {
    if ($metaText -notmatch [regex]::Escape($layer)) {
        throw "Required layer '$layer' was not found in MBTiles metadata."
    }
}

Write-Host "MBTiles OK: $mbtiles ($size bytes)"
