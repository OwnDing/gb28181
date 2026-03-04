param(
    [Parameter(Mandatory = $true)]
    [string]$ZlmExe,
    [Parameter(Mandatory = $true)]
    [string]$ZlmDir,
    [Parameter(Mandatory = $true)]
    [string]$VideoExe,
    [Parameter(Mandatory = $true)]
    [string]$VideoDir
)

$ErrorActionPreference = "Stop"

function Get-NormalizedPath([string]$Path) {
    try {
        return (Resolve-Path -LiteralPath $Path).Path
    } catch {
        return $Path
    }
}

function Is-RunningByPath([string]$TargetPath) {
    $normalized = Get-NormalizedPath $TargetPath
    $processes = Get-CimInstance Win32_Process | Where-Object { $_.ExecutablePath }
    foreach ($process in $processes) {
        if ([string]::Equals($process.ExecutablePath, $normalized, [System.StringComparison]::OrdinalIgnoreCase)) {
            return $true
        }
    }
    return $false
}

function Is-LinkLocalIpv4([string]$IpAddress) {
    return -not [string]::IsNullOrWhiteSpace($IpAddress) -and $IpAddress.StartsWith("169.254.")
}

function Get-CandidateIpsFromNetConfig {
    $rows = @()
    $configs = Get-NetIPConfiguration -ErrorAction SilentlyContinue
    foreach ($cfg in $configs) {
        if (-not $cfg.NetAdapter -or $cfg.NetAdapter.Status -ne "Up") {
            continue
        }
        if (-not $cfg.IPv4Address) {
            continue
        }
        foreach ($addr in $cfg.IPv4Address) {
            $ip = $addr.IPAddress
            if ([string]::IsNullOrWhiteSpace($ip)) {
                continue
            }
            if ($ip -eq "127.0.0.1") {
                continue
            }
            $metric = 9999
            if ($cfg.NetIPv4Interface -and $cfg.NetIPv4Interface.InterfaceMetric -is [int]) {
                $metric = $cfg.NetIPv4Interface.InterfaceMetric
            }
            $rows += [PSCustomObject]@{
                InterfaceAlias  = $cfg.InterfaceAlias
                InterfaceIndex  = $cfg.InterfaceIndex
                IPAddress       = $ip
                InterfaceMetric = $metric
                IsLinkLocal     = Is-LinkLocalIpv4 $ip
            }
        }
    }
    if (-not $rows) {
        return @()
    }
    $groupedByNic = $rows | Group-Object InterfaceAlias, InterfaceIndex
    $selected = foreach ($group in $groupedByNic) {
        $nic = $group.Group | Sort-Object IsLinkLocal, InterfaceMetric, IPAddress | Select-Object -First 1
        [PSCustomObject]@{
            InterfaceAlias  = $nic.InterfaceAlias
            InterfaceIndex  = $nic.InterfaceIndex
            IPAddress       = $nic.IPAddress
            InterfaceMetric = $nic.InterfaceMetric
            IsLinkLocal     = $nic.IsLinkLocal
        }
    }
    return @($selected | Sort-Object IsLinkLocal, InterfaceMetric, InterfaceIndex, IPAddress)
}

function Get-CandidateIpsFromWmi {
    $rows = @()
    $configs = Get-CimInstance Win32_NetworkAdapterConfiguration -Filter "IPEnabled=TRUE" -ErrorAction SilentlyContinue
    foreach ($cfg in $configs) {
        if (-not $cfg.IPAddress) {
            continue
        }
        foreach ($ip in $cfg.IPAddress) {
            if ([string]::IsNullOrWhiteSpace($ip)) {
                continue
            }
            if ($ip -notmatch "^\d{1,3}(\.\d{1,3}){3}$") {
                continue
            }
            if ($ip -eq "127.0.0.1") {
                continue
            }
            $rows += [PSCustomObject]@{
                InterfaceAlias  = if ($cfg.Description) { $cfg.Description } else { "Adapter-$($cfg.InterfaceIndex)" }
                InterfaceIndex  = $cfg.InterfaceIndex
                IPAddress       = $ip
                InterfaceMetric = 9999
                IsLinkLocal     = Is-LinkLocalIpv4 $ip
            }
        }
    }
    if (-not $rows) {
        return @()
    }
    $groupedByNic = $rows | Group-Object InterfaceAlias, InterfaceIndex
    $selected = foreach ($group in $groupedByNic) {
        $nic = $group.Group | Sort-Object IsLinkLocal, IPAddress | Select-Object -First 1
        [PSCustomObject]@{
            InterfaceAlias  = $nic.InterfaceAlias
            InterfaceIndex  = $nic.InterfaceIndex
            IPAddress       = $nic.IPAddress
            InterfaceMetric = $nic.InterfaceMetric
            IsLinkLocal     = $nic.IsLinkLocal
        }
    }
    return @($selected | Sort-Object IsLinkLocal, InterfaceAlias, InterfaceIndex, IPAddress)
}

function Get-CandidateIps {
    if (Get-Command Get-NetIPConfiguration -ErrorAction SilentlyContinue) {
        $viaNetConfig = @(Get-CandidateIpsFromNetConfig)
        if ($viaNetConfig.Count -gt 0) {
            return $viaNetConfig
        }
    }
    return @(Get-CandidateIpsFromWmi)
}

function Select-LocalIp {
    $candidates = @(Get-CandidateIps)
    if ($candidates.Count -eq 0) {
        throw "No usable IPv4 interface found. Please connect a network adapter first."
    }

    if ($candidates.Count -eq 1) {
        $only = $candidates[0]
        $tag = if ($only.IsLinkLocal) { " [link-local]" } else { "" }
        Write-Host ("[INFO] Single NIC detected: {0} -> {1}{2}" -f $only.InterfaceAlias, $only.IPAddress, $tag)
        return $only.IPAddress
    }

    Write-Host "[INFO] Multiple NICs detected. Select one for GB28181:"
    for ($i = 0; $i -lt $candidates.Count; $i++) {
        $item = $candidates[$i]
        $tag = if ($item.IsLinkLocal) { ", link-local" } else { "" }
        Write-Host ("  [{0}] {1} ({2}{3})" -f ($i + 1), $item.InterfaceAlias, $item.IPAddress, $tag)
    }

    while ($true) {
        $text = Read-Host ("Enter index [1-{0}] (default: 1)" -f $candidates.Count)
        if ([string]::IsNullOrWhiteSpace($text)) {
            $text = "1"
        }
        $index = 0
        if ([int]::TryParse($text, [ref]$index) -and $index -ge 1 -and $index -le $candidates.Count) {
            $selected = $candidates[$index - 1]
            Write-Host ("[INFO] Selected: {0} -> {1}" -f $selected.InterfaceAlias, $selected.IPAddress)
            return $selected.IPAddress
        }
        Write-Host "[WARN] Invalid selection, try again."
    }
}

$selectedIp = Select-LocalIp
$env:APP_GB28181_LOCAL_IP = $selectedIp
$env:APP_GB28181_MEDIA_IP = $selectedIp

Write-Host ("[INFO] APP_GB28181_LOCAL_IP={0}" -f $selectedIp)
Write-Host ("[INFO] APP_GB28181_MEDIA_IP={0}" -f $selectedIp)

if (Is-RunningByPath $ZlmExe) {
    Write-Host "[SKIP] ZLM already running from this directory."
} else {
    Write-Host "[START] Starting ZLM..."
    Start-Process -FilePath $ZlmExe -WorkingDirectory $ZlmDir -WindowStyle Hidden | Out-Null
    Start-Sleep -Milliseconds 300
}

if (Is-RunningByPath $VideoExe) {
    Write-Host "[SKIP] video.exe already running from this directory."
} else {
    Write-Host "[START] Starting video.exe ..."
    Start-Process -FilePath $VideoExe -WorkingDirectory $VideoDir | Out-Null
    Start-Sleep -Milliseconds 300
}

Write-Host "[OK] Startup script finished."
