param (
    [Parameter(Position = 0, Mandatory = $true)]
    [ValidateSet("package", "module", "ram")]
    $Type,

    [Parameter(Mandatory = $false)]
    [Alias("m")]
    [string]$ModuleName
)

# Handle RAM analysis on a connected Android device
if ($Type -eq "ram") {
    # Check if ADB tool is installed and in the system PATH
    if (-not (Get-Command adb -ErrorAction SilentlyContinue)) {
        Write-Error "ADB command not found. Please make sure Android Platform Tools are in your PATH and a device is connected."
        return
    }

    # Ensure there is a device currently connected and authorized
    $devices = adb devices
    $deviceCount = ($devices | ? { $_ -match '\tdevice$' }).Count
    if ($deviceCount -eq 0) {
        Write-Warning "No Android devices or emulators detected via ADB. Please connect a device with USB Debugging enabled."
        return
    }

    Write-Host "Fetching and parsing active RAM metrics from device via ADB..." -ForegroundColor Cyan
    $meminfo = adb shell dumpsys meminfo
    $processList = @()

    foreach ($line in $meminfo) {
        # Capture raw memory numbers (ignoring commas) and process names with standard PID tags
        # Works on multiple formats (e.g., "  358,901K: org.package (pid 123)" and "  33714 kB: system (pid 456)")
        if ($line -match '^\s*([\d,]+)\s*(?:K|kB|KB|K:)\s*:?\s*([a-zA-Z0-9_\-\.\:\+]+)\s*\(pid\s*(\d+)') {
            $rawKb = $Matches[1].Replace(",", "")
            $kbValue = [double]$rawKb
            $procName = $Matches[2]
            $procId = $Matches[3]
            $bytes = $kbValue * 1024

            $processList += [PSCustomObject]@{
                PID = $procId
                Name = $procName
                Bytes = $bytes
            }
        }
    }

    if ($processList.Count -eq 0) {
        Write-Warning "Could not parse any running processes. Ensure your device is authorized and running."
        return
    }

    # Group by PID to eliminate duplicate entries across different dumpsys sections, keeping the highest reading
    $deduplicatedList = $processList | Group-Object PID | ForEach-Object {
        $_.Group | Sort-Object Bytes -Descending | Select-Object -First 1
    }

    # Sort processes by memory, translate bytes to human-readable scales, and filter columns for clean output
    $sortedList = $deduplicatedList | Sort-Object Bytes -Descending | Select-Object PID, Name, @{
        n="Memory"
        e={
            if ($_.Bytes -ge 1GB) {
                "{0:N2} GB" -f ($_.Bytes / 1GB)
            } else {
                "{0:N2} MB" -f ($_.Bytes / 1MB)
            }
        }
    }, Bytes

    # Remove raw Bytes calculation column to print a pristine table
    $displayList = $sortedList | Select-Object PID, Name, Memory
    $displayList

    # Calculate global total RAM utilization across all unique active system processes
    $totalBytes = ($deduplicatedList | Measure-Object Bytes -Sum).Sum
    $totalFormatted = if ($totalBytes -ge 1GB) { "{0:N2} GB" -f ($totalBytes / 1GB) } else { "{0:N2} MB" -f ($totalBytes / 1MB) }

    ""
    [PSCustomObject]@{PID="-"; Name="TOTAL RUNNING PROCESS RAM"; Memory=$totalFormatted}
    return
}

# Core logic to fetch and filter files for codebase analysis
$files = gci -r -fi *.kt | ? {$_.FullName -match '\\src\\'}

# If a specific module is requested, filter the files down early
if ($ModuleName) {
    $files = $files | ? { ($_.FullName -split '\\src\\')[0].Split('\')[-1] -eq $ModuleName }
    if (-not $files) {
        Write-Warning "No Kotlin files found in module: $ModuleName"
        return
    }
}

# Determine grouping logic based on the user's positional command
if ($Type -eq "package") {
    $groupExp = { $_.Directory.Name }
} else {
    $groupExp = { ($_.FullName -split '\\src\\')[0].Split('\')[-1] }
}

# Execute the metrics calculation
$r = $files | group $groupExp | select Name, @{n="Lines"; e={($_.Group | gc | measure -l).Lines}} | sort Lines -desc
$r
""
[PSCustomObject]@{Name="TOTAL"; Lines=($r | measure Lines -sum).Sum}
