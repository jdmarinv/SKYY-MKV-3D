[CmdletBinding()]
param(
    [string]$Apk,
    [ValidateSet(
        "/sdcard/K3DX/config/white_list2.config",
        "/sdcard/K3DX/config/.white_list2.config"
    )]
    [string]$Config,
    [switch]$InspectOnly,
    [switch]$Yes
)

$ErrorActionPreference = "Stop"

$Package = "com.iqh3d.geoexplorer"
$Activity = "com.iqh3d.geoexplorer.MainActivity"
$ServicePackage = "com.wztech.service3d"
$Entry = "30@$Activity"
$ConfigDirectory = "/sdcard/K3DX/config"
$VisibleConfig = "$ConfigDirectory/white_list2.config"
$HiddenConfig = "$ConfigDirectory/.white_list2.config"

function Invoke-AdbCommand {
    param([string[]]$Arguments)

    $Output = @(& adb @Arguments 2>&1)
    if ($LASTEXITCODE -ne 0) {
        throw "adb $($Arguments -join ' ') failed:`n$($Output -join [Environment]::NewLine)"
    }

    return ($Output -join [Environment]::NewLine).Trim()
}

function Test-RemoteFile {
    param([string]$Path)

    & adb shell "[ -f '$Path' ]" *> $null
    return $LASTEXITCODE -eq 0
}

function Test-RemoteContains {
    param(
        [string]$Path,
        [string]$Needle
    )

    & adb shell "tr -d '\r' < '$Path' | grep -Fqx '$Needle'" *> $null
    return $LASTEXITCODE -eq 0
}

function Test-RemoteChromeEntry {
    param([string]$Path)

    & adb shell "tr -d '\r' < '$Path' | grep -Eiq '^30@.*chrome'" *> $null
    return $LASTEXITCODE -eq 0
}

if (-not (Get-Command adb -ErrorAction SilentlyContinue)) {
    throw "adb.exe was not found. Install Android SDK Platform-Tools and add its folder to PATH."
}

$DeviceLines = @(& adb devices 2>&1)
if ($LASTEXITCODE -ne 0) {
    throw "adb devices failed:`n$($DeviceLines -join [Environment]::NewLine)"
}

$ConnectedDevices = @($DeviceLines | Where-Object { $_ -match "\sdevice$" })
if ($ConnectedDevices.Count -ne 1) {
    throw "Connect and authorize exactly one tablet. Found $($ConnectedDevices.Count)."
}

if ($Apk) {
    $ResolvedApk = (Resolve-Path -LiteralPath $Apk -ErrorAction Stop).Path
    Write-Host "Installing APK: $ResolvedApk"
    Invoke-AdbCommand @("install", "-r", $ResolvedApk) | Write-Host
}

Invoke-AdbCommand @("shell", "pm path '$Package'") | Out-Null
Invoke-AdbCommand @("shell", "pm path '$ServicePackage'") | Out-Null

$PackageDump = Invoke-AdbCommand @("shell", "dumpsys package '$Package'")
if (-not $PackageDump.Contains($Activity)) {
    throw "Expected Activity was not found in the installed package: $Activity"
}

$ConfigPath = $Config
if ($ConfigPath) {
    if (-not (Test-RemoteFile $ConfigPath)) {
        throw "Configuration file does not exist: $ConfigPath"
    }
} else {
    $VisibleExists = Test-RemoteFile $VisibleConfig
    $HiddenExists = Test-RemoteFile $HiddenConfig

    if ($VisibleExists -and -not $HiddenExists) {
        $ConfigPath = $VisibleConfig
    } elseif (-not $VisibleExists -and $HiddenExists) {
        $ConfigPath = $HiddenConfig
    } elseif ($VisibleExists -and $HiddenExists) {
        $VisibleRegistered = Test-RemoteContains $VisibleConfig $Entry
        $HiddenRegistered = Test-RemoteContains $HiddenConfig $Entry
        $VisibleChrome = Test-RemoteChromeEntry $VisibleConfig
        $HiddenChrome = Test-RemoteChromeEntry $HiddenConfig

        if ($VisibleRegistered -and -not $HiddenRegistered) {
            $ConfigPath = $VisibleConfig
        } elseif (-not $VisibleRegistered -and $HiddenRegistered) {
            $ConfigPath = $HiddenConfig
        } elseif ($VisibleChrome -and -not $HiddenChrome) {
            $ConfigPath = $VisibleConfig
        } elseif (-not $VisibleChrome -and $HiddenChrome) {
            $ConfigPath = $HiddenConfig
        } else {
            throw "Both whitelist files exist and the active one is ambiguous. Rerun with -Config PATH."
        }
    } else {
        throw "No 3DFV whitelist was found under $ConfigDirectory."
    }
}

$Timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$BackupPath = "$ConfigPath.bak.$Timestamp"
$Model = Invoke-AdbCommand @("shell", "getprop ro.product.model")
$AndroidVersion = Invoke-AdbCommand @("shell", "getprop ro.build.version.release")
$DisplayLines = Invoke-AdbCommand @("shell", "wm size")
$Display = ($DisplayLines -split "`r?`n")[-1]

Write-Host ""
Write-Host "Tablet: $Model"
Write-Host "Android: $AndroidVersion"
Write-Host "Display: $Display"
Write-Host "Detected package: $Package"
Write-Host "Detected Activity: $Activity"
Write-Host "Proposed entry: $Entry"
Write-Host "Active whitelist: $ConfigPath"
Write-Host "Exact backup path: $BackupPath"
Write-Host ""

if ($InspectOnly) {
    if (Test-RemoteContains $ConfigPath $Entry) {
        Write-Host "Status: the Activity is already registered."
    } else {
        Write-Host "Status: the Activity is not registered on this tablet."
    }
    exit 0
}

if (-not $Yes) {
    $Reply = Read-Host "Continue with this backup and whitelist entry? [y/N]"
    if ($Reply -notmatch "^(y|yes)$") {
        Write-Host "No changes were made."
        exit 0
    }
}

Invoke-AdbCommand @("shell", "cp '$ConfigPath' '$BackupPath'") | Out-Null
if (-not (Test-RemoteFile $BackupPath)) {
    throw "Backup verification failed: $BackupPath"
}

if (Test-RemoteContains $ConfigPath $Entry) {
    Write-Host "Whitelist entry already exists; leaving the file unchanged."
} else {
    Invoke-AdbCommand @("shell", "printf '%s\r\n' '$Entry' >> '$ConfigPath'") | Out-Null
    if (-not (Test-RemoteContains $ConfigPath $Entry)) {
        throw "Whitelist verification failed."
    }
    Write-Host "Whitelist entry added successfully."
}

# Reload only the vendor service. Never uninstall it or clear its data.
& adb shell am stopservice -n "$ServicePackage/.Service3D" *> $null
Invoke-AdbCommand @("shell", "am startservice -a com.wztech.service -p '$ServicePackage'") | Write-Host
Invoke-AdbCommand @("shell", "am force-stop '$Package'") | Out-Null
Invoke-AdbCommand @("shell", "am start -n '$Package/.MainActivity'") | Write-Host

Write-Host ""
Write-Host "3DFV provisioning completed. Open a video and verify the native edge selector visually."
