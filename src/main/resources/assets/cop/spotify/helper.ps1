# cop spotify helper — talks to Windows SMTC via WinRT, emits JSON lines on stdout.
# Author: elvin
#
# Output format: line-delimited JSON.
#   {"type":"state", title, artist, album, posMs, durMs, paused, open, source, artVersion}
#   {"type":"art", version, b64} (only when album art changes)
#   {"type":"hello"} (once on startup so the host knows we're alive)
#
# Usage: powershell -NoProfile -ExecutionPolicy Bypass -File helper.ps1
# Stdout is consumed by the JVM side. stderr is logged.

$ErrorActionPreference = 'Continue'
$OutputEncoding = [System.Text.UTF8Encoding]::new()
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new()
$MaxAlbumArtBytes = 5MB
$MaxMetadataChars = 512

# Force-load the WinRT types we need.
$null = [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager, Windows.Media.Control, ContentType=WindowsRuntime]
$null = [Windows.Storage.Streams.DataReader, Windows.Storage.Streams, ContentType=WindowsRuntime]
$null = [Windows.Storage.Streams.IRandomAccessStreamReference, Windows.Storage.Streams, ContentType=WindowsRuntime]
$null = [Windows.Storage.Streams.IRandomAccessStream, Windows.Storage.Streams, ContentType=WindowsRuntime]
$null = [Windows.Storage.Streams.IRandomAccessStreamWithContentType, Windows.Storage.Streams, ContentType=WindowsRuntime]
$null = [Windows.Storage.Streams.InputStreamOptions, Windows.Storage.Streams, ContentType=WindowsRuntime]

# `System.WindowsRuntimeSystemExtensions` lives in `System.Runtime.WindowsRuntime`,
# shipped with .NET Framework 4.5+ but NOT auto-loaded by PowerShell 5.1.
# Without this explicit load, the AsTask() lookup below dies with TypeNotFound.
$null = [Reflection.Assembly]::Load('System.Runtime.WindowsRuntime, Version=4.0.0.0, Culture=neutral, PublicKeyToken=b77a5c561934e089')

# Resolve the AsStreamForRead(IRandomAccessStream) overload by exact signature.
# PowerShell's binder can't match `__ComObject` to any specific WinRT interface
# overload, so we must invoke this via reflection with the type pinned.
$asStreamForRead = [System.IO.WindowsRuntimeStreamExtensions].GetMethod(
    'AsStreamForRead',
    [Type[]]@([Windows.Storage.Streams.IRandomAccessStream])
)
if ($null -eq $asStreamForRead) {
    [Console]::Error.WriteLine("FATAL: AsStreamForRead(IRandomAccessStream) not resolved")
}

# Helper to synchronously block on a WinRT IAsyncOperation<T> from PowerShell.
# This is the canonical pattern for using WinRT from .NET / PowerShell.
$asTaskGeneric = ([System.WindowsRuntimeSystemExtensions].GetMethods() | ? {
    $_.Name -eq 'AsTask' -and
    $_.GetParameters().Count -eq 1 -and
    $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1'
})[0]

function Await($op, $resultType) {
    if ($null -eq $op) { return $null }
    $task = $asTaskGeneric.MakeGenericMethod($resultType).Invoke($null, @($op))
    $task.Wait(-1) | Out-Null
    return $task.Result
}

function Emit($obj) {
    try {
        $json = $obj | ConvertTo-Json -Compress -Depth 4
        [Console]::Out.WriteLine($json)
        [Console]::Out.Flush()
    } catch {
        [Console]::Error.WriteLine("emit failed: $_")
    }
}

function Test-SpotifySource($value) {
    $source = "$value".Trim().ToLowerInvariant()
    return $source -eq 'spotify' -or
        $source -eq 'spotify.exe' -or
        ($source.StartsWith('spotifyab.spotifymusic_') -and $source.EndsWith('!spotify'))
}

function Limit-Text($value, [int]$maxChars = $MaxMetadataChars) {
    $text = "$value"
    if ($text.Length -le $maxChars) { return $text }
    return $text.Substring(0, $maxChars)
}

Emit @{ type = 'hello'; pid = $PID }

# Acquire the session manager once.
$mgrTask = [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager]::RequestAsync()
$mgr = Await $mgrTask ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager])
if ($null -eq $mgr) {
    Emit @{ type = 'fatal'; reason = 'no session manager' }
    exit 1
}

$lastArtHash = ''
$artVersion = 0
$lastTitle = $null
$lastArtist = $null

while ($true) {
    try {
        # Try to find the Spotify session specifically. The WinRT IReadOnlyList<>
        # doesn't expose .Item() to PowerShell — iterate directly.
        $sessions = $mgr.GetSessions()
        $session = $null
        foreach ($s in $sessions) {
            $src = ''
            try { $src = $s.SourceAppUserModelId } catch {}
            if (Test-SpotifySource $src) {
                $session = $s
                break
            }
        }

        if ($null -eq $session) {
            # Never expose metadata/art from whichever unrelated app happens to
            # own the global current-media session. Wait for Spotify to publish
            # an identifiable session instead.
            $lastTitle = $null
            $lastArtist = $null
            $lastArtHash = ''
            Emit @{ type = 'state'; open = $false; paused = $false; title = ''; artist = ''; album = ''; posMs = 0; durMs = 0; source = ''; artVersion = $artVersion }
            Start-Sleep -Milliseconds 1000
            continue
        }

        $source = ''
        try { $source = $session.SourceAppUserModelId } catch {}
        if (-not (Test-SpotifySource $source)) {
            $lastTitle = $null
            $lastArtist = $null
            $lastArtHash = ''
            Emit @{ type = 'state'; open = $false; paused = $false; title = ''; artist = ''; album = ''; posMs = 0; durMs = 0; source = ''; artVersion = $artVersion }
            Start-Sleep -Milliseconds 1000
            continue
        }
        $source = Limit-Text $source

        $propsOp = $session.TryGetMediaPropertiesAsync()
        $props = Await $propsOp ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionMediaProperties])

        $title  = Limit-Text $(if ($props) { $props.Title }      else { '' })
        $artist = Limit-Text $(if ($props) { $props.Artist }     else { '' })
        $album  = Limit-Text $(if ($props) { $props.AlbumTitle } else { '' })

        $timeline = $session.GetTimelineProperties()
        $playback = $session.GetPlaybackInfo()

        # Position / Duration are TimeSpan (.Ticks: 100ns).
        $posMs = [int64]($timeline.Position.Ticks / 10000)
        $durMs = [int64]($timeline.EndTime.Ticks  / 10000)

        $paused = $playback.PlaybackStatus -ne [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionPlaybackStatus]::Playing

        # Album art: changes only when the track does, so we hash on title+artist as a cheap proxy
        # and re-fetch the thumbnail bytes only when that changes.
        $trackKey = "$title|$artist"
        if ($trackKey -ne ($lastTitle + '|' + $lastArtist)) {
            $lastTitle = $title
            $lastArtist = $artist
            $hasUsableArt = $false

            if ($props -and $props.Thumbnail) {
                try {
                    $streamOp = $props.Thumbnail.OpenReadAsync()
                    $stream = Await $streamOp ([Windows.Storage.Streams.IRandomAccessStreamWithContentType])
                    if ($stream) {
                        # PowerShell 5.1 can't access WinRT properties (e.g. `.Size`) through
                        # __ComObject — they come back null. Bridge to a real .NET Stream via
                        # `WindowsRuntimeStreamExtensions.AsStreamForRead`, invoked through the
                        # pre-resolved MethodInfo so the runtime QueryInterfaces the COM object
                        # to IRandomAccessStream itself.
                        $netStream = $null
                        $ms = $null
                        try {
                            $netStream = $asStreamForRead.Invoke($null, @($stream))
                            $ms = New-Object System.IO.MemoryStream
                            $buffer = New-Object byte[] 81920
                            $total = 0
                            while (($read = $netStream.Read($buffer, 0, $buffer.Length)) -gt 0) {
                                $total += $read
                                if ($total -gt $MaxAlbumArtBytes) {
                                    throw "album art exceeds $MaxAlbumArtBytes bytes"
                                }
                                $ms.Write($buffer, 0, $read)
                            }
                            $bytes = $ms.ToArray()
                        } finally {
                            if ($null -ne $netStream) { $netStream.Dispose() }
                            if ($null -ne $ms) { $ms.Dispose() }
                        }

                        if ($bytes.Length -gt 0) {
                            # Hash to detect actual content changes (different from track-key heuristic
                            # since some tracks share art, and live-updates can mutate it).
                            $sha = $null
                            try {
                                $sha = [System.Security.Cryptography.SHA1]::Create()
                                $hash = [System.BitConverter]::ToString($sha.ComputeHash($bytes)).Replace('-', '')
                                if ($hash -ne $lastArtHash) {
                                    $lastArtHash = $hash
                                    $artVersion++
                                    $b64 = [System.Convert]::ToBase64String($bytes)
                                    Emit @{ type = 'art'; version = $artVersion; source = "$source"; b64 = $b64 }
                                }
                                $hasUsableArt = $true
                            } finally {
                                if ($null -ne $sha) { $sha.Dispose() }
                            }
                        }
                    }
                } catch {
                    [Console]::Error.WriteLine("art fetch failed: $_")
                }
            }

            # Explicitly retire the previous track's cover if this track has no
            # usable thumbnail (including fetch/size failures). The versioned
            # signal prevents the JVM renderer from continuing to show stale art.
            if (-not $hasUsableArt) {
                $lastArtHash = ''
                $artVersion++
                Emit @{ type = 'art-clear'; version = $artVersion; source = "$source" }
            }
        }

        Emit @{
            type       = 'state'
            open       = $true
            paused     = [bool]$paused
            title      = "$title"
            artist     = "$artist"
            album      = "$album"
            posMs      = $posMs
            durMs      = $durMs
            source     = "$source"
            artVersion = $artVersion
        }
    } catch {
        [Console]::Error.WriteLine("loop error: $_")
        $lastTitle = $null
        $lastArtist = $null
        $lastArtHash = ''
        Emit @{ type = 'state'; open = $false; paused = $false; title = ''; artist = ''; album = ''; posMs = 0; durMs = 0; source = ''; artVersion = $artVersion }
    }

    # 250 ms is the sweet spot — fast enough that play/pause/skip feel instant,
    # slow enough that the JVM-side position extrapolation handles smooth playback
    # between polls without burning CPU.
    Start-Sleep -Milliseconds 250
}
