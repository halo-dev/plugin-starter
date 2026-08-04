param(
    [Parameter(Mandatory = $true)]
    [string]$HaloJar,
    [Parameter(Mandatory = $true)]
    [string]$PluginProject,
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$WorkDir = (Join-Path $env:TEMP ("halo-traffic-smoke-" + [DateTime]::UtcNow.ToString('yyyyMMddHHmmss'))),
    [int]$Port = 8099
)

$ErrorActionPreference = 'Stop'

function Wait-HaloReady {
    param([System.Diagnostics.Process]$Process, [int]$Port, [int]$TimeoutSeconds = 180)
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        Start-Sleep -Seconds 2
        if ($Process.HasExited) {
            throw "Halo process exited with code $($Process.ExitCode)."
        }
        try {
            $response = Invoke-WebRequest -UseBasicParsing "http://127.0.0.1:$Port/actuator/health" -TimeoutSec 5
            if ($response.StatusCode -eq 200) {
                return
            }
        } catch {
            # Keep polling until the deadline; startup may take over one minute on the first run.
        }
    } while ((Get-Date) -lt $deadline)
    throw "Halo startup timed out after $TimeoutSeconds seconds."
}

$haloJarPath = (Resolve-Path -LiteralPath $HaloJar).Path
$pluginPath = (Resolve-Path -LiteralPath $PluginProject).Path.Replace('\', '/')
$java = Join-Path $JavaHome 'bin\java.exe'
if (-not (Test-Path -LiteralPath $java -PathType Leaf)) {
    throw "JDK executable was not found at $java"
}
if (Test-Path -LiteralPath $WorkDir) {
    throw "Choose a new smoke-test work directory: $WorkDir"
}

New-Item -ItemType Directory -Path $WorkDir | Out-Null
$stdout = Join-Path $WorkDir 'halo.stdout.log'
$stderr = Join-Path $WorkDir 'halo.stderr.log'
$arguments = @(
    '-jar', $haloJarPath,
    "--server.port=$Port",
    "--halo.work-dir=$($WorkDir.Replace('\', '/'))",
    '--halo.security.basic-auth.disabled=false',
    '--halo.plugin.runtime-mode=development',
    "--halo.plugin.fixed-plugin-path[0]=$pluginPath",
    '--logging.level.run.halo.traffic.monitor=DEBUG'
)

$process = $null
try {
    $process = Start-Process -FilePath $java -ArgumentList $arguments -WorkingDirectory $WorkDir `
        -RedirectStandardOutput $stdout -RedirectStandardError $stderr -WindowStyle Hidden -PassThru
    Set-Content -LiteralPath (Join-Path $WorkDir 'halo.pid') -Value $process.Id -Encoding ascii
    Wait-HaloReady -Process $process -Port $Port

    $baseUrl = "http://127.0.0.1:$Port"
    $setupBody = @{
        username    = 'admin'
        password    = 'admin123'
        email       = 'traffic-test@example.com'
        siteTitle   = 'Traffic Monitor Smoke Test'
        language    = 'zh-CN'
        externalUrl = "$baseUrl/"
    }
    Invoke-WebRequest -UseBasicParsing "$baseUrl/system/setup" -Method Post `
        -ContentType 'application/x-www-form-urlencoded' -Headers @{ Accept = 'application/json' } `
        -Body $setupBody -TimeoutSec 120 | Out-Null

    1..4 | ForEach-Object {
        try {
            Invoke-WebRequest -UseBasicParsing "$baseUrl/?smoke=$_" -TimeoutSec 20 | Out-Null
        } catch {
            # A public route may be unavailable before a theme is fully reconciled; the filter still records it.
        }
    }
    try {
        Invoke-WebRequest -UseBasicParsing "$baseUrl/wp-admin/" -TimeoutSec 20 | Out-Null
    } catch {
        # The expected 404 is still a valid monitored request.
    }

    Start-Sleep -Seconds 2
    $token = [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes('admin:admin123'))
    $headers = @{ Authorization = "Basic $token"; Accept = 'application/json' }
    $api = "$baseUrl/apis/console.api.traffic-monitor.halo.run/v1alpha1/dashboard?minutes=60"
    $dashboard = Invoke-RestMethod -Uri $api -Headers $headers -TimeoutSec 30

    if ($dashboard.summary.requests -lt 5) {
        throw "Expected at least 5 monitored requests, got $($dashboard.summary.requests)."
    }
    if (-not ($dashboard.alerts | Where-Object type -eq 'SUSPICIOUS_PATH')) {
        throw 'Expected a SUSPICIOUS_PATH alert from /wp-admin/.'
    }

    [pscustomobject]@{
        haloVersion = '2.25.4'
        plugin = 'traffic-monitor'
        requests = $dashboard.summary.requests
        uniqueIps = $dashboard.summary.uniqueIps
        totalBytes = $dashboard.summary.totalBytes
        alerts = $dashboard.alerts.Count
        suspiciousPathAlert = $true
        status = 'passed'
        stdout = $stdout
        stderr = $stderr
    } | ConvertTo-Json -Depth 4
} finally {
    if ($process -and -not $process.HasExited) {
        Stop-Process -Id $process.Id -Force
        $process.WaitForExit(10000) | Out-Null
    }
    Write-Host "Smoke runtime retained for evidence: $WorkDir"
}
