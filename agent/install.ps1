# PULSE - LAN system monitor with a sci-fi tablet console
# Copyright (C) 2026 Anandhu Suresh
# SPDX-License-Identifier: GPL-3.0-or-later

<#
.SYNOPSIS
  One-click installer for the PULSE agent on Windows.

.DESCRIPTION
  Installs to D:\Applications\PulseAgent (never C:), installs the Python packages
  into a private "lib" folder there, writes config.yaml, and registers the agent to
  start at boot in the background, either as:
    -Mode Service : a Windows service via NSSM (recommended; auto-restart, log rotation)
    -Mode Task    : a Task Scheduler task running pythonw.exe as SYSTEM at startup
  If NSSM is not found, Service mode falls back to Task mode.

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File install.ps1
  powershell -ExecutionPolicy Bypass -File install.ps1 -HubUrl ws://192.168.1.10:8765 -Token abc... -Id pc -Name BATTLESTATION -Accent "#00F0FF"
  powershell -ExecutionPolicy Bypass -File install.ps1 -Uninstall
#>
[CmdletBinding()]
param(
    [string]$HubUrl,
    [string]$Token,
    [string]$Id,
    [string]$Name,
    [string]$Accent = "#00F0FF",
    [ValidateSet("Service", "Task")][string]$Mode = "Service",
    [string]$InstallDir = "D:\Applications\PulseAgent",
    [string]$Python,
    [switch]$Uninstall
)
$ErrorActionPreference = "Stop"
$ServiceName = "PulseAgent"

# --- self-elevate (services and SYSTEM tasks need admin) -----------------------------
$principal = New-Object Security.Principal.WindowsPrincipal([Security.Principal.WindowsIdentity]::GetCurrent())
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    $argList = @("-NoProfile", "-ExecutionPolicy", "Bypass", "-File", "`"$PSCommandPath`"")
    foreach ($kv in $PSBoundParameters.GetEnumerator()) {
        if ($kv.Value -is [switch]) { if ($kv.Value) { $argList += "-$($kv.Key)" } }
        else { $argList += "-$($kv.Key)", "`"$($kv.Value)`"" }
    }
    Start-Process powershell.exe -Verb RunAs -ArgumentList $argList
    exit
}

function Find-Nssm {
    $c = Get-Command nssm.exe -ErrorAction SilentlyContinue
    if ($c) { return $c.Source }
    foreach ($p in @("$InstallDir\nssm.exe", "$PSScriptRoot\nssm.exe")) { if (Test-Path $p) { return $p } }
    return $null
}

function Remove-Existing {
    $nssm = Find-Nssm
    if (Get-Service $ServiceName -ErrorAction SilentlyContinue) {
        Write-Host "Removing service $ServiceName"
        Stop-Service $ServiceName -Force -ErrorAction SilentlyContinue
        if ($nssm) { & $nssm remove $ServiceName confirm | Out-Null } else { sc.exe delete $ServiceName | Out-Null }
    }
    if (Get-ScheduledTask -TaskName $ServiceName -ErrorAction SilentlyContinue) {
        Write-Host "Removing scheduled task $ServiceName"
        Stop-ScheduledTask -TaskName $ServiceName -ErrorAction SilentlyContinue
        Unregister-ScheduledTask -TaskName $ServiceName -Confirm:$false
    }
}

if ($Uninstall) {
    Remove-Existing
    Write-Host "Uninstalled. Files are left in $InstallDir (delete the folder to remove them)."
    exit
}

if ($InstallDir.Substring(0, 1).ToUpper() -eq "C") { throw "Refusing to install on the C: drive. Use -InstallDir on another drive." }

# --- find Python 3.11+ ------------------------------------------------------------------
function Test-Python([string]$exe) {
    try {
        $v = & $exe -c "import sys; print('%d.%d' % sys.version_info[:2]); print(sys.executable)" 2>$null
        if ($LASTEXITCODE -ne 0 -or -not $v) { return $null }
        $ver = [version]$v[0]
        if ($ver -ge [version]"3.11") { return $v[1] }
    } catch { }
    return $null
}
$py = $null
if ($Python) { $py = Test-Python $Python }
if (-not $py) { foreach ($cand in @("py", "python", "python3")) { if (Get-Command $cand -ErrorAction SilentlyContinue) { $py = Test-Python $cand; if ($py) { break } } } }
if (-not $py) { throw "Python 3.11+ not found. Install it (e.g. to D:\Applications\Python) and re-run, or pass -Python <path to python.exe>." }
$pyw = Join-Path (Split-Path $py) "pythonw.exe"
Write-Host "Using Python: $py"

# --- files -------------------------------------------------------------------------------
New-Item -ItemType Directory -Force -Path $InstallDir, "$InstallDir\logs" | Out-Null
Copy-Item "$PSScriptRoot\agent.py", "$PSScriptRoot\requirements.txt", "$PSScriptRoot\requirements-gpu.txt" $InstallDir -Force

# Packages go into a private lib folder next to agent.py (agent.py adds it to sys.path).
# --no-cache-dir keeps pip from writing its cache to C:.
Write-Host "Installing Python packages into $InstallDir\lib ..."
& $py -m pip install --disable-pip-version-check --no-cache-dir --quiet --upgrade --target "$InstallDir\lib" -r "$InstallDir\requirements.txt"
if ($LASTEXITCODE -ne 0) { throw "pip install failed" }

$cfg = "$InstallDir\config.yaml"
if (-not (Test-Path $cfg)) {
    if (-not $HubUrl) { $HubUrl = Read-Host "Hub URL (e.g. ws://192.168.1.10:8765)" }
    if (-not $Token)  { $Token  = Read-Host "Agent token" }
    if (-not $Id)     { $Id     = Read-Host "Device id [pc]"; if (-not $Id) { $Id = "pc" } }
    if (-not $Name)   { $Name   = Read-Host "Display name [$($env:COMPUTERNAME)]"; if (-not $Name) { $Name = $env:COMPUTERNAME } }
    @"
hub_url: $HubUrl
token: $Token
id: $Id
name: $Name
accent: "$Accent"
gpu: smi
# lhm_url: http://127.0.0.1:8085/data.json
log_file: $InstallDir\logs\agent.log
log_level: INFO
"@ | Set-Content -Encoding utf8 $cfg
    # Only Administrators and SYSTEM may read the token.
    icacls $cfg /inheritance:r /grant:r "*S-1-5-32-544:F" "*S-1-5-18:F" | Out-Null
}

Write-Host "Self-test (one sample):"
& $py "$InstallDir\agent.py" -c $cfg --print | Select-Object -First 2 | ForEach-Object { if ($_.Length -gt 160) { $_.Substring(0, 160) + "..." } else { $_ } }

# --- background registration ----------------------------------------------------------
Remove-Existing
$nssm = Find-Nssm
if ($Mode -eq "Service" -and -not $nssm) {
    Write-Warning "nssm.exe not found (put it in $InstallDir or on PATH; https://nssm.cc). Falling back to Task Scheduler."
    $Mode = "Task"
}

if ($Mode -eq "Service") {
    # Separate arguments: Windows PowerShell 5.1 strips embedded quotes from a single
    # combined argument, which would hand NSSM "agent.py -c config.yaml" as ONE word.
    & $nssm install $ServiceName $py "$InstallDir\agent.py" -c $cfg | Out-Null
    & $nssm set $ServiceName AppDirectory $InstallDir | Out-Null
    & $nssm set $ServiceName DisplayName "PULSE Agent" | Out-Null
    & $nssm set $ServiceName Description "Pushes system metrics to the PULSE hub. Send-only, opens no ports." | Out-Null
    & $nssm set $ServiceName Start SERVICE_DELAYED_AUTO_START | Out-Null
    & $nssm set $ServiceName AppPriority BELOW_NORMAL_PRIORITY_CLASS | Out-Null
    & $nssm set $ServiceName AppExit Default Restart | Out-Null
    & $nssm set $ServiceName AppRestartDelay 2000 | Out-Null
    & $nssm set $ServiceName AppStdout "$InstallDir\logs\service.log" | Out-Null
    & $nssm set $ServiceName AppStderr "$InstallDir\logs\service.log" | Out-Null
    & $nssm set $ServiceName AppRotateFiles 1 | Out-Null
    & $nssm set $ServiceName AppRotateBytes 1048576 | Out-Null
    Start-Service $ServiceName
    Write-Host "Installed Windows service '$ServiceName' (NSSM)."
} else {
    $action = New-ScheduledTaskAction -Execute $pyw -Argument "`"$InstallDir\agent.py`" -c `"$cfg`"" -WorkingDirectory $InstallDir
    $trigger = New-ScheduledTaskTrigger -AtStartup
    $trigger.Delay = "PT30S"
    $principal = New-ScheduledTaskPrincipal -UserId "SYSTEM" -LogonType ServiceAccount -RunLevel Highest
    $settings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -StartWhenAvailable `
        -ExecutionTimeLimit ([TimeSpan]::Zero) -RestartCount 999 -RestartInterval (New-TimeSpan -Minutes 1) -Priority 7
    Register-ScheduledTask -TaskName $ServiceName -Action $action -Trigger $trigger -Principal $principal -Settings $settings `
        -Description "PULSE agent (send-only telemetry)" | Out-Null
    Start-ScheduledTask -TaskName $ServiceName
    Write-Host "Installed scheduled task '$ServiceName' (runs at boot as SYSTEM)."
}
# Verify it is still running after a few seconds (a crash shows up here, not later).
Start-Sleep -Seconds 6
if ($Mode -eq "Service") { $state = (Get-Service $ServiceName).Status }
else { $state = (Get-ScheduledTask -TaskName $ServiceName).State }
Write-Host "State after 6 s: $state"
foreach ($log in @("$InstallDir\logs\agent.log", "$InstallDir\logs\service.log")) {
    if (Test-Path $log) { Write-Host "--- $log"; Get-Content $log -Tail 5 }
}
Write-Host "Logs: $InstallDir\logs"
Write-Host "Done. The device should appear on the tablet within a few seconds."
