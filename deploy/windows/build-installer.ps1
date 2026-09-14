<#
.SYNOPSIS
    Builds a Windows installer (.exe or .msi) for the agent or the proxy.

.DESCRIPTION
    Wraps jpackage, which bundles a trimmed JRE alongside the application, so
    the target machines need no Java installed and no Java kept up to date --
    on an estate of Windows servers that is the difference between an install
    people accept and one they argue about.

    Registers the application as a Windows service, so it starts at boot and
    survives a logout. Without that an agent stops collecting the moment
    whoever installed it signs out, which is the sort of fault that is noticed
    weeks later.

    THIS SCRIPT MUST RUN ON WINDOWS. jpackage cannot cross-compile: a Windows
    installer can only be produced on Windows. Building on Linux or macOS
    yields a package for that platform instead.

.PARAMETER Component
    'agent' (default) or 'proxy'.

.PARAMETER Type
    'exe' (default, a self-contained installer) or 'msi' (for Group Policy
    and SCCM deployment).

.EXAMPLE
    .\build-installer.ps1
    .\build-installer.ps1 -Component proxy -Type msi
#>

[CmdletBinding()]
param(
    [ValidateSet('agent', 'proxy')]
    [string]$Component = 'agent',

    [ValidateSet('exe', 'msi')]
    [string]$Type = 'exe',

    [string]$AppVersion = '1.0.0'
)

$ErrorActionPreference = 'Stop'

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot '..\..')
$staging  = Join-Path $env:TEMP "nms-jpackage-$Component"
$outDir   = Join-Path $repoRoot 'dist'

# --- Prerequisites ---------------------------------------------------------
#
# Checked up front rather than allowed to surface as a jpackage error three
# minutes into a Maven build. The WiX requirement in particular fails with a
# message that does not name WiX.

if (-not (Get-Command jpackage -ErrorAction SilentlyContinue)) {
    throw "jpackage not found. Install a JDK 21 (not a JRE) and put its bin directory on PATH."
}

if (-not (Get-Command light.exe -ErrorAction SilentlyContinue)) {
    Write-Warning @"
WiX Toolset v3 was not found on PATH. jpackage needs it to produce an .exe or
.msi and will fail without it.

    winget install WiXToolset.WiXToolset

Then reopen this shell so the updated PATH is picked up.
"@
}

# --- Inputs ----------------------------------------------------------------
#
# Each component ships as one shaded jar, which is exactly what jpackage wants
# as --main-jar. The proxy is a Spring Boot application and the agent is not,
# so their main classes differ.

switch ($Component) {
    'agent' {
        $module      = 'nms-agent'
        $jarName     = 'nms-agent.jar'
        $mainClass   = 'com.nms.agent.AgentMain'
        $displayName = 'NMS Monitoring Agent'
        $description = 'Reports CPU, memory, disk and process metrics to the monitoring server'
        # A stable GUID is what lets a later version upgrade this install in
        # place. Change it and Windows treats the new build as a separate
        # product, leaving two services fighting over the same port.
        $upgradeUuid = 'f1c0a2d4-5b3e-4a17-9c88-6e2b7d40a911'
        # Lower than the default: this runs on machines doing real work, and an
        # agent that takes a quarter of a server's RAM gets uninstalled.
        $javaOptions = '-XX:MaxRAMPercentage=25 -XX:+UseSerialGC'
        $configPath  = 'C:\ProgramData\NMS\agent.conf'
    }
    'proxy' {
        $module      = 'nms-proxy'
        $jarName     = (Get-ChildItem (Join-Path $repoRoot "nms-proxy\target\nms-proxy-*.jar") |
                        Where-Object { $_.Name -notlike '*-sources.jar' } |
                        Select-Object -First 1 -ExpandProperty Name)
        $mainClass   = ''   # Spring Boot jars carry their own Main-Class
        $displayName = 'NMS Monitoring Proxy'
        $description = 'Collects from local devices and forwards results to the monitoring server'
        $upgradeUuid = 'b7e4d90c-1a62-4f35-8d0b-3c51e6a77d24'
        $javaOptions = '-XX:MaxRAMPercentage=50 -XX:+UseG1GC'
        $configPath  = ''
    }
}

# --- Build -----------------------------------------------------------------

Write-Host "Building $module..." -ForegroundColor Cyan
Push-Location $repoRoot
try {
    & mvn -B -q -pl $module -am package -DskipTests
    if ($LASTEXITCODE -ne 0) { throw "Maven build failed." }
}
finally {
    Pop-Location
}

# jpackage takes a directory and bundles everything in it, so the staging
# directory holds the one jar and nothing else -- otherwise stray build output
# ends up inside the installer.
if (Test-Path $staging) { Remove-Item $staging -Recurse -Force }
New-Item -ItemType Directory -Path $staging -Force | Out-Null

$builtJar = Join-Path $repoRoot "$module\target\$jarName"
if (-not (Test-Path $builtJar)) { throw "Expected jar not found: $builtJar" }
Copy-Item $builtJar $staging

New-Item -ItemType Directory -Path $outDir -Force | Out-Null

$jpackageArgs = @(
    '--type', $Type
    '--name', "nms-$Component"
    '--app-version', $AppVersion
    '--description', $description
    '--vendor', 'Internal'
    '--input', $staging
    '--main-jar', $jarName
    '--dest', $outDir
    '--java-options', $javaOptions
    '--launcher-as-service'
    '--win-upgrade-uuid', $upgradeUuid
    '--win-dir-chooser'
    # Note the absence of --win-per-user-install. That flag is presence-only,
    # and leaving it out is what gives a per-machine install -- which is what a
    # service needs, so that it runs with no user signed in.
)

if ($mainClass) {
    $jpackageArgs += @('--main-class', $mainClass)
}

if ($configPath) {
    # Passed to the service at startup. The file does not have to exist at
    # install time -- the agent falls back to environment variables and
    # defaults -- so a package can be rolled out before the configuration is.
    $jpackageArgs += @('--arguments', $configPath)
}

Write-Host "Packaging nms-$Component $AppVersion as .$Type..." -ForegroundColor Cyan
& jpackage @jpackageArgs
if ($LASTEXITCODE -ne 0) { throw "jpackage failed." }

$artifact = Get-ChildItem (Join-Path $outDir "nms-$Component*.$Type") |
            Sort-Object LastWriteTime -Descending | Select-Object -First 1

Write-Host ""
Write-Host "Built $($artifact.FullName)" -ForegroundColor Green
Write-Host ("  {0:N1} MB -- includes a bundled JRE, so the target needs no Java." -f ($artifact.Length / 1MB))
Write-Host ""
if ($Type -eq 'msi') {
    Write-Host "Install on a target machine with:" -ForegroundColor Cyan
    Write-Host "  msiexec /i `"$($artifact.Name)`" /qn"
    Write-Host "  (/qn is a silent install, which is what Group Policy and SCCM use.)"
} else {
    Write-Host "Install on a target machine by running it." -ForegroundColor Cyan
    Write-Host "  Build with -Type msi instead if you need an unattended rollout."
}
Write-Host ""
Write-Host "The service is registered as 'nms-$Component'. Check it with:"
Write-Host "  Get-Service nms-$Component"
