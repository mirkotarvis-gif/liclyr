# Lyrics Overlay - media helper.
# Reads "what's playing" from Windows (the same info as the volume / media flyout)
# and prints one JSON line every ~350 ms. Started and stopped by the mod automatically.
param(
    [int]$ParentPid = 0,
    [string]$CoverDir = $env:TEMP
)

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = New-Object System.Text.UTF8Encoding $false

function Emit($obj) {
    [Console]::Out.WriteLine(($obj | ConvertTo-Json -Compress -Depth 3))
    [Console]::Out.Flush()
}

try {
    Add-Type -AssemblyName System.Runtime.WindowsRuntime
    $asTaskGeneric = ([System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object {
        $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and
        $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1'
    })[0]

    function Await($op, [Type]$type) {
        $task = $asTaskGeneric.MakeGenericMethod($type).Invoke($null, @($op))
        if (-not $task.Wait(4000)) { throw 'WinRT call timed out' }
        return $task.Result
    }

    $null = [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager,Windows.Media.Control,ContentType=WindowsRuntime]
    $null = [Windows.Storage.Streams.DataReader,Windows.Storage.Streams,ContentType=WindowsRuntime]

    $mgrType    = [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager]
    $propsType  = [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionMediaProperties]
    $streamType = [Windows.Storage.Streams.IRandomAccessStreamWithContentType]

    $mgr = Await ($mgrType::RequestAsync()) $mgrType
} catch {
    Emit @{ ok = $false; fatal = $true; err = "Windows media API unavailable: $($_.Exception.Message)" }
    exit 2
}

function Save-Cover($props, [string]$path) {
    $ref = $props.Thumbnail
    if ($null -eq $ref) { return $false }
    $rs = Await ($ref.OpenReadAsync()) $streamType
    try {
        if ($rs.Size -le 0) { return $false }
        # Method 1: .NET stream wrapper
        try {
            $net = [System.IO.WindowsRuntimeStreamExtensions]::AsStreamForRead($rs)
            $fs = [System.IO.File]::Create($path)
            try { $net.CopyTo($fs) } finally { $fs.Close(); $net.Close() }
            if ((Get-Item $path).Length -gt 0) { return $true }
        } catch {}
        # Method 2: DataReader
        $size = [uint32]$rs.Size
        $reader = [Windows.Storage.Streams.DataReader]::new($rs.GetInputStreamAt(0))
        $null = Await ($reader.LoadAsync($size)) ([uint32])
        $bytes = New-Object byte[] $size
        $reader.ReadBytes($bytes)
        [System.IO.File]::WriteAllBytes($path, $bytes)
        return $true
    } finally {
        try { $rs.Dispose() } catch {}
    }
}

$lastKey = ''
$coverPath = ''
$coverTries = 0
$coverN = 0

while ($true) {
    if ($ParentPid -gt 0 -and -not (Get-Process -Id $ParentPid -ErrorAction SilentlyContinue)) { exit 0 }

    $out = @{ ok = $false }
    try {
        $session = $null
        foreach ($s in $mgr.GetSessions()) {
            if ($s.GetPlaybackInfo().PlaybackStatus -eq 'Playing') { $session = $s; break }
        }
        if ($null -eq $session) { $session = $mgr.GetCurrentSession() }

        if ($null -ne $session) {
            $p  = Await ($session.TryGetMediaPropertiesAsync()) $propsType
            $tl = $session.GetTimelineProperties()
            $pb = $session.GetPlaybackInfo()

            $key = "$($session.SourceAppUserModelId)|$($p.Artist)|$($p.Title)"
            if ($key -ne $lastKey) {
                $lastKey = $key
                $coverPath = ''
                $coverTries = 0
            }
            # Apps often publish the cover a moment after the title, so retry a few times.
            if ($coverPath -eq '' -and $coverTries -lt 8) {
                $coverTries++
                try {
                    $coverN++
                    $path = Join-Path $CoverDir ("cover_" + ($coverN % 4) + ".img")
                    if (Save-Cover $p $path) { $coverPath = $path }
                } catch {}
            }

            $dur = ($tl.EndTime - $tl.StartTime).TotalMilliseconds
            $out = [ordered]@{
                ok     = $true
                app    = [string]$session.SourceAppUserModelId
                title  = [string]$p.Title
                artist = [string]$p.Artist
                album  = [string]$p.AlbumTitle
                status = [string]$pb.PlaybackStatus
                posMs  = [long]$tl.Position.TotalMilliseconds
                durMs  = [long]$dur
                updMs  = [long]$tl.LastUpdatedTime.ToUnixTimeMilliseconds()
                cover  = $coverPath
            }
        }
    } catch {
        $out = @{ ok = $false; err = $_.Exception.Message }
    }
    Emit $out
    Start-Sleep -Milliseconds 350
}
