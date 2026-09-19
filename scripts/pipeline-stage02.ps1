[CmdletBinding()]
param(
    [switch]$Force
)

$ErrorActionPreference = "Stop"
$root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$data = Join-Path $root "data"
$rgzDir = Join-Path $data "rgz"
$infra = Join-Path $root "infra\postgis"
$network = "zylos_default"
$mountData = "type=bind,source=$data,target=/data"
$mountInfra = "type=bind,source=$infra,target=/style"
$pgUser = "zylos"
$pgPass = "zylos"
$pgDb = "zylos"
$pgConn = "PG:host=postgis dbname=$pgDb user=$pgUser password=$pgPass"

function Assert-LastExitCode([string]$Message) {
    if ($LASTEXITCODE -ne 0) {
        throw "$Message (exit code $LASTEXITCODE)"
    }
}

function Invoke-PsqlFile([string]$RelativePath) {
    $path = Join-Path $root $RelativePath
    if (-not (Test-Path $path)) {
        throw "Required SQL file is missing: $RelativePath"
    }
    Get-Content -Raw $path |
        docker compose exec -T postgis `
            psql -U $pgUser -d $pgDb -v ON_ERROR_STOP=1 -f -
    Assert-LastExitCode "psql failed for $RelativePath"
}

function Wait-ForSqlFile([string]$RelativePath, [int]$TimeoutSeconds = 600) {
    $path = Join-Path $root $RelativePath
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while (-not (Test-Path $path)) {
        if ((Get-Date) -gt $deadline) {
            throw "Timed out waiting for $RelativePath"
        }
        Write-Host "Waiting for $RelativePath ..."
        Start-Sleep -Seconds 5
    }
}

function Invoke-DownloadRgz {
    $archive = Join-Path $rgzDir "kucni_br_gpkg.gpkg"
    $gpkg = Join-Path $rgzDir "kucni_broj.gpkg"
    $stamp = Join-Path $rgzDir "downloaded-at.txt"
    $url = "https://download.geosrbija.rs/download-api/opendata-proxy/export?category=ar&layer=kucni_broj_ar&geometry=true&fileName=kucni_br_gpkg&format=gpkg"

    function Test-GpkgOrZip([string]$Path) {
        if (-not (Test-Path $Path) -or (Get-Item $Path).Length -eq 0) { return $false }
        $bytes = [System.IO.File]::ReadAllBytes($Path)[0..15]
        $head = [System.Text.Encoding]::ASCII.GetString($bytes)
        if ($head.StartsWith("SQLite format 3")) { return $true }
        if ($head.StartsWith("PK")) { return $true }
        return $false
    }

    if (-not $Force -and (Test-Path $gpkg) -and (Test-GpkgOrZip $gpkg)) {
        Write-Host "RGZ GeoPackage already present; skipping download."
        return
    }

    if ($Force) {
        Remove-Item $archive, $gpkg -Force -ErrorAction SilentlyContinue
    }

    Write-Host "Downloading RGZ kućni brojevi (resume enabled)..."
    & curl.exe --fail --location --retry 5 --retry-delay 3 --continue-at - `
        --output $archive $url
    Assert-LastExitCode "RGZ download failed"

    if (-not (Test-GpkgOrZip $archive)) {
        throw "Downloaded RGZ file is not a GeoPackage or ZIP wrapper (HTML error page?)"
    }

    $zipMagic = [System.Text.Encoding]::ASCII.GetString([System.IO.File]::ReadAllBytes($archive)[0..1])
    if ($zipMagic -eq "PK") {
        Write-Host "Extracting GeoPackage from ZIP archive..."
        docker run --rm --mount "type=bind,source=$rgzDir,target=/rgz" alpine sh -c `
            "apk add --no-cache unzip >/dev/null && unzip -o /rgz/kucni_br_gpkg.gpkg -d /rgz"
        Assert-LastExitCode "RGZ ZIP extraction failed"
    }
    elseif (-not (Test-Path $gpkg)) {
        Copy-Item $archive $gpkg
    }

    if (-not (Test-Path $gpkg)) {
        throw "Expected extracted GeoPackage at $gpkg"
    }

    (Get-Date).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ssZ") |
        Set-Content -Encoding ascii $stamp
    Write-Host "RGZ download complete: $gpkg ($((Get-Item $gpkg).Length) bytes)"
}

function Invoke-ImportOsm([string]$Mode = "all") {
    $pbf = Join-Path $data "osm\novi-sad.osm.pbf"
    $boundary = Join-Path $data "boundary\grad-novi-sad.geojson"
    if (-not (Test-Path $pbf)) { throw "Missing $pbf" }
    if (-not (Test-Path $boundary)) { throw "Missing $boundary" }

    @"
CREATE SCHEMA IF NOT EXISTS osm_staging;
CREATE SCHEMA IF NOT EXISTS rgz_staging;
"@ | docker compose exec -T postgis psql -U $pgUser -d $pgDb -v ON_ERROR_STOP=1 -f -
    Assert-LastExitCode "Failed ensuring staging schemas"

    if ($Mode -in @("all", "boundary")) {
        Write-Host "Loading city boundary into osm_staging.city_boundary_src..."
        docker run --rm --network $network --mount $mountData -e "PGPASSWORD=$pgPass" `
            ghcr.io/osgeo/gdal:ubuntu-small-latest `
            ogr2ogr -overwrite -f PostgreSQL $pgConn `
            "/data/boundary/grad-novi-sad.geojson" grad-novi-sad `
            -nln osm_staging.city_boundary_src `
            -lco GEOMETRY_NAME=geom `
            -t_srs EPSG:4326 -nlt PROMOTE_TO_MULTI
        Assert-LastExitCode "Boundary ogr2ogr failed"

        Write-Host "Promoting boundary into public.city_boundary..."
        @"
INSERT INTO public.city_boundary (id, osm_relation_id, geom)
SELECT
  1,
  1649672,
  ST_Multi(
    ST_CollectionExtract(
      ST_MakeValid(ST_UnaryUnion(ST_Collect(geom))),
      3
    )
  )
FROM osm_staging.city_boundary_src
WHERE geom IS NOT NULL
ON CONFLICT (osm_relation_id) DO UPDATE
  SET geom = EXCLUDED.geom,
      loaded_at = now();
"@ | docker compose exec -T postgis psql -U $pgUser -d $pgDb -v ON_ERROR_STOP=1 -f -
        Assert-LastExitCode "Boundary promotion failed"
    }

    if ($Mode -in @("all", "osm")) {
        Write-Host "Running osm2pgsql flex into osm_staging..."
        docker run --rm --network $network --mount $mountData --mount $mountInfra `
            -e "PGPASSWORD=$pgPass" iboates/osm2pgsql:latest `
            osm2pgsql --create --slim --drop `
            --schema osm_staging --middle-schema osm_staging `
            --output flex --style /style/osm-flex.lua `
            --host postgis --port 5432 --username $pgUser --database $pgDb `
            /data/osm/novi-sad.osm.pbf
        Assert-LastExitCode "osm2pgsql flex import failed"
    }
}

function Invoke-ImportRgz {
    $gpkg = Join-Path $rgzDir "kucni_broj.gpkg"
    $boundary = Join-Path $data "boundary\grad-novi-sad.geojson"
    if (-not (Test-Path $gpkg)) { throw "Missing $gpkg (download RGZ first)" }
    if (-not (Test-Path $boundary)) { throw "Missing $boundary" }

    Write-Host "Inspecting RGZ GeoPackage..."
    docker run --rm --mount $mountData ghcr.io/osgeo/gdal:ubuntu-small-latest `
        ogrinfo -so /data/rgz/kucni_broj.gpkg
    docker run --rm --mount $mountData ghcr.io/osgeo/gdal:ubuntu-small-latest `
        ogrinfo -al -so /data/rgz/kucni_broj.gpkg
    Write-Host "RGZ layer: kucni_broj | CRS: EPSG:25834 | rg_id<-primary_key, street<-ulica_ime_lat, street_sr_cyrl<-ulica_ime, housenumber<-kucni_broj"

    "TRUNCATE TABLE rgz_staging.kucni_broj_src;" |
        docker compose exec -T postgis psql -U $pgUser -d $pgDb -v ON_ERROR_STOP=1 -f -
    Assert-LastExitCode "Failed truncating rgz_staging.kucni_broj_src"

    Write-Host "Clipping and loading RGZ house numbers..."
    docker run --rm --network $network --mount $mountData -e "PGPASSWORD=$pgPass" `
        ghcr.io/osgeo/gdal:ubuntu-small-latest `
        ogr2ogr -append -f PostgreSQL $pgConn `
        "/data/rgz/kucni_broj.gpkg" kucni_broj `
        -nln rgz_staging.kucni_broj_src `
        -sql "SELECT CAST(primary_key AS TEXT) AS rg_id, ulica_ime_lat AS street, ulica_ime AS street_sr_cyrl, kucni_broj AS housenumber, CAST(NULL AS TEXT) AS postcode, geom FROM kucni_broj" `
        -s_srs EPSG:25834 -t_srs EPSG:4326 `
        -clipsrc "/data/boundary/grad-novi-sad.geojson" `
        -lco GEOMETRY_NAME=geom -nlt POINT
    Assert-LastExitCode "RGZ ogr2ogr failed"

    docker compose exec -T postgis psql -U $pgUser -d $pgDb -t -A `
        -c "SELECT count(*) FROM rgz_staging.kucni_broj_src;"
}

function Get-StagingCounts {
    $query = @"
SELECT 'building_src', count(*)::text FROM osm_staging.building_src
UNION ALL SELECT 'address_src', count(*)::text FROM osm_staging.address_src
UNION ALL SELECT 'poi_src', count(*)::text FROM osm_staging.poi_src
UNION ALL SELECT 'kucni_broj_src', count(*)::text FROM rgz_staging.kucni_broj_src;
"@
    $query | docker compose exec -T postgis psql -U $pgUser -d $pgDb -t -A
}

function Update-ReadmeFromVerify {
    $verifyOutput = Get-Content -Raw (Join-Path $root "data\tmp\stage02-verify.log")
    $rgzDate = "unknown"
    $stampPath = Join-Path $rgzDir "downloaded-at.txt"
    if (Test-Path $stampPath) {
        $rgzDate = (Get-Content $stampPath -Raw).Trim()
    }

    $metrics = @{}
    foreach ($line in ($verifyOutput -split "`n")) {
        if ($line -match '^\s*(?:psql:<stdin>:\d+:\s+NOTICE:\s+)?\|\s*([A-Z0-9]+)\s*\|\s*([^|]+)\|\s*([^|]+)\|\s*([^|]+)\|\s*(OK|FAIL)\s*\|') {
            $metrics[$Matches[1].Trim()] = @{
                Metric = $Matches[2].Trim()
                Value = $Matches[3].Trim()
                Threshold = $Matches[4].Trim()
                Status = $Matches[5].Trim()
            }
        }
    }

    $readmePath = Join-Path $data "README.md"
    $existing = if (Test-Path $readmePath) { Get-Content $readmePath -Raw } else { "" }
    if ($existing -match '(?s)(.*?)(\r?\n## Stage 2.*)?$') {
        $existing = $Matches[1].TrimEnd()
    }

    $copyright = [char]0x00A9
    $section = @"

## Stage 2 PostGIS

- OSM license: ODbL, $copyright OpenStreetMap contributors
- RGZ license: open data from [data.gov.rs Adresni registar](https://data.gov.rs/sr/datasets/adresni-registar/) / GeoSrbija
- RGZ downloaded at: $rgzDate
- RGZ layer: ``kucni_broj`` (EPSG:25834 -> EPSG:4326, clipped to relation 1649672)

| # | Metric | Value | Required | Status |
|---|--------|------:|----------|--------|
"@

    foreach ($key in @("C1", "C2", "C3", "C4", "C5", "C6", "C7")) {
        if ($metrics.ContainsKey($key)) {
            $m = $metrics[$key]
            $section += "`n| $key | $($m.Metric) | $($m.Value) | $($m.Threshold) | $($m.Status) |"
        }
    }

    if ($metrics.ContainsKey("C5")) {
        $section += "`n`n- Share of RGZ addresses linked to buildings (C5): $($metrics['C5'].Value)"
    }

    ($existing + $section).TrimEnd() + "`n" | Set-Content -Encoding utf8 $readmePath
}

New-Item -ItemType Directory -Force -Path $rgzDir | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $data "tmp") | Out-Null

if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    throw "Docker is required but was not found in PATH."
}

Push-Location $root
try {
    Write-Host "Step 1: ensuring PostGIS is up..."
    docker compose up -d postgis --wait
    Assert-LastExitCode "docker compose up postgis failed"

    Write-Host "Step 2: applying schema and seed categories..."
    Invoke-PsqlFile "infra/postgis/schema.sql"
    Invoke-PsqlFile "infra/postgis/seed_categories.sql"

    Write-Host "Step 3: loading city boundary..."
    Invoke-ImportOsm -Mode "boundary"

    Write-Host "Step 4: OSM import..."
    Invoke-ImportOsm -Mode "osm"
    Wait-ForSqlFile "infra/postgis/load_osm.sql"
    Invoke-PsqlFile "infra/postgis/load_osm.sql"

    Write-Host "Step 5: downloading RGZ if needed..."
    Invoke-DownloadRgz

    Write-Host "Step 6: RGZ clip + import..."
    Invoke-ImportRgz
    Wait-ForSqlFile "infra/postgis/load_rgz.sql"
    Invoke-PsqlFile "infra/postgis/load_rgz.sql"

    Write-Host "Step 7: linking buildings..."
    Invoke-PsqlFile "infra/postgis/link_buildings.sql"

    if (Test-Path (Join-Path $root "infra/postgis/fixtures.sql")) {
        Write-Host "Step 7b: loading F4 fixtures..."
        Invoke-PsqlFile "infra/postgis/fixtures.sql"
    }

    Write-Host "Step 8: verification..."
    $verifyLog = Join-Path $data "tmp\stage02-verify.log"
    $verifySql = Join-Path $root "scripts\stage02-verify.sql"
    $verifyCommand = 'docker compose exec -T postgis psql -U {0} -d {1} -v ON_ERROR_STOP=1 -f - < "{2}" > "{3}" 2>&1' -f `
        $pgUser, $pgDb, $verifySql, $verifyLog
    & cmd.exe /d /s /c $verifyCommand
    $verifyExitCode = $LASTEXITCODE
    Get-Content $verifyLog | Out-Host
    if ($verifyExitCode -ne 0) {
        throw "stage02-verify.sql failed (exit code $verifyExitCode)"
    }

    Write-Host "Step 9: updating data/README.md..."
    Update-ReadmeFromVerify

    Write-Host ""
    Write-Host "Stage 2 staging counts:"
    Get-StagingCounts | ForEach-Object { Write-Host "  $_" }
}
finally {
    Pop-Location
}

Write-Host ""
Write-Host "Stage 2 pipeline finished."
