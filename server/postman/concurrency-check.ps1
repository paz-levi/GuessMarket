# Ex3 Stage 2 concurrency check: fires genuinely parallel HTTP requests at the live server (a RunspacePool, not
# sequential loops) to exercise races Stage 1's single-threaded engine tests could never reach -- the servlet layer
# adds nothing of its own to guard against this, so this is really proving EngineImpl's ReentrantReadWriteLock
# holds up under real concurrent Tomcat request threads, not just concurrent JVM-internal threads.
# Windows PowerShell 5.1 note: Invoke-WebRequest needs -UseBasicParsing here, or it tries to use IE's rendering
# engine and fails with "NonInteractive mode" in a non-interactive session; it also has no -SkipHttpErrorCheck
# (that's PS7+ only), so a non-2xx response is read from the thrown exception's own Response instead.
param(
    [string]$BaseUrl = "http://localhost:8080/GuessMarket"
)

function Get-StatusCode {
    param([scriptblock]$Call)
    try {
        $response = & $Call
        return [int]$response.StatusCode
    } catch {
        if ($_.Exception.Response) {
            return [int]$_.Exception.Response.StatusCode.value__
        }
        return -1
    }
}

function Invoke-Parallel {
    param([scriptblock]$Body, [array]$InputItems)
    $pool = [runspacefactory]::CreateRunspacePool(1, [Math]::Max(20, $InputItems.Count))
    $pool.Open()
    $handles = foreach ($item in $InputItems) {
        $ps = [powershell]::Create()
        $ps.RunspacePool = $pool
        [void]$ps.AddScript($Body).AddArgument($item)
        [PSCustomObject]@{ PS = $ps; Handle = $ps.BeginInvoke() }
    }
    $results = foreach ($h in $handles) {
        try { $h.PS.EndInvoke($h.Handle) } catch { -1 }
        $h.PS.Dispose()
    }
    $pool.Close()
    $pool.Dispose()
    return $results
}

Write-Host "=== Test 1: 20 concurrent registrations of the SAME name ===" -ForegroundColor Cyan
$body = {
    param($url)
    try {
        $r = Invoke-WebRequest -Uri "$url/login" -Method Post -Body @{ username = "Contested" } -UseBasicParsing
        [int]$r.StatusCode
    } catch {
        if ($_.Exception.Response) { [int]$_.Exception.Response.StatusCode.value__ } else { -1 }
    }
}
$results = Invoke-Parallel -Body $body -InputItems (1..20 | ForEach-Object { $BaseUrl })
$succeeded = ($results | Where-Object { $_ -eq 200 }).Count
$conflicted = ($results | Where-Object { $_ -eq 409 }).Count
Write-Host "  200 (succeeded): $succeeded   409 (conflict): $conflicted   other: $($results.Count - $succeeded - $conflicted) [$($results -join ',')]"
$test1Pass = ($succeeded -eq 1) -and ($conflicted -eq 19)
Write-Host "  PASS = $test1Pass" -ForegroundColor $(if ($test1Pass) { "Green" } else { "Red" })

Write-Host "`n=== Test 2: 20 concurrent registrations of DISTINCT names ===" -ForegroundColor Cyan
$body2 = {
    param($name)
    $url = "http://localhost:8080/GuessMarket"
    try {
        $r = Invoke-WebRequest -Uri "$url/login" -Method Post -Body @{ username = $name } -UseBasicParsing
        [int]$r.StatusCode
    } catch {
        if ($_.Exception.Response) { [int]$_.Exception.Response.StatusCode.value__ } else { -1 }
    }
}
$names = 1..20 | ForEach-Object { "ConcurrentUser-$_" }
$results2 = Invoke-Parallel -Body $body2 -InputItems $names
$succeeded2 = ($results2 | Where-Object { $_ -eq 200 }).Count
Write-Host "  200 (succeeded): $succeeded2 / 20  [$($results2 -join ',')]"
$usersAfter = Invoke-RestMethod -Uri "$BaseUrl/users" -Method Get -UseBasicParsing
$distinctCount = ($usersAfter | Where-Object { $_.username -like "ConcurrentUser-*" } | Select-Object -ExpandProperty username -Unique).Count
Write-Host "  Distinct ConcurrentUser-* rows in GET /users: $distinctCount / 20"
$test2Pass = ($succeeded2 -eq 20) -and ($distinctCount -eq 20)
Write-Host "  PASS = $test2Pass" -ForegroundColor $(if ($test2Pass) { "Green" } else { "Red" })

Write-Host "`n=== Test 3: 20 concurrent deposits of 10.0 to ONE account (shared WebRequestSession replayed by every parallel call) ===" -ForegroundColor Cyan
# -Headers @{ Cookie = ... } is silently dropped by Windows PowerShell 5.1's Invoke-WebRequest (Cookie is a
# restricted header on the underlying HttpWebRequest) -- confirmed by a direct probe while building this script.
# -WebSession is the real mechanism: it carries the CookieContainer, and since RunspacePool runspaces share this
# same process (unlike Start-Job), passing the WebRequestSession .NET object itself through AddArgument works by
# reference, unlike a serialized/reconstructed cookie string.
$loginResp = Invoke-WebRequest -Uri "$BaseUrl/login" -Method Post -Body @{ username = "Saver" } -SessionVariable saverSession -UseBasicParsing
$body3 = {
    param($session)
    $url = "http://localhost:8080/GuessMarket"
    try {
        $r = Invoke-WebRequest -Uri "$url/user/deposit" -Method Post -Body @{ amount = "10" } -WebSession $session -UseBasicParsing
        [int]$r.StatusCode
    } catch {
        if ($_.Exception.Response) { [int]$_.Exception.Response.StatusCode.value__ } else { -1 }
    }
}
$results3 = Invoke-Parallel -Body $body3 -InputItems (1..20 | ForEach-Object { $saverSession })
$succeeded3 = ($results3 | Where-Object { $_ -eq 200 }).Count
Write-Host "  200 (succeeded): $succeeded3 / 20  [$($results3 -join ',')]"
$saverDetail = Invoke-RestMethod -Uri "$BaseUrl/user?username=Saver" -Method Get -UseBasicParsing
$expectedBalance = 200.0
Write-Host "  Balance after 20x10.0 concurrent deposits: $($saverDetail.balance) (expected $expectedBalance)"
$sequences = @($saverDetail.transactions | ForEach-Object { $_.sequence } | Sort-Object)
$expectedSequences = @(1..20)
$sequencesMatch = ($sequences.Count -eq 20) -and (@(Compare-Object $sequences $expectedSequences).Count -eq 0)
Write-Host "  Ledger sequences ($($sequences.Count) entries): $($sequences -join ',')"
Write-Host "  Gap-free 1..20 sequences: $sequencesMatch"
$test3Pass = ($succeeded3 -eq 20) -and ([Math]::Abs($saverDetail.balance - $expectedBalance) -lt 0.0001) -and $sequencesMatch
Write-Host "  PASS = $test3Pass" -ForegroundColor $(if ($test3Pass) { "Green" } else { "Red" })

Write-Host "`n=== Test 4: 2 concurrent uploads of DIFFERENT files by DIFFERENT registered uploaders ===" -ForegroundColor Cyan
# HttpClientHandler.DefaultRequestHeaders.Add("Cookie", ...) is silently ineffective in Windows PowerShell 5.1's
# .NET Framework HttpClient (Cookie is a restricted header there too, same root cause as Test 3's -Headers finding)
# -- confirmed by a direct probe while building this script. A System.Net.CookieContainer, attached to the
# handler and pre-loaded with the real JSESSIONID cookie, is the mechanism that actually authenticates.
$loginA = Invoke-WebRequest -Uri "$BaseUrl/login" -Method Post -Body @{ username = "UploaderA" } -SessionVariable sessA -UseBasicParsing
$loginB = Invoke-WebRequest -Uri "$BaseUrl/login" -Method Post -Body @{ username = "UploaderB" } -SessionVariable sessB -UseBasicParsing
$cookieObjA = $sessA.Cookies.GetCookies($BaseUrl) | Select-Object -First 1
$cookieObjB = $sessB.Cookies.GetCookies($BaseUrl) | Select-Object -First 1
$projectRoot = (Resolve-Path "$PSScriptRoot\..\..").Path
$body4 = {
    param($item)
    $url = "http://localhost:8080/GuessMarket"
    Add-Type -AssemblyName System.Net.Http
    $handler = New-Object System.Net.Http.HttpClientHandler
    $handler.CookieContainer = New-Object System.Net.CookieContainer
    $handler.CookieContainer.Add((New-Object System.Uri($url)), (New-Object System.Net.Cookie($item.CookieName, $item.CookieValue)))
    $client = New-Object System.Net.Http.HttpClient($handler)
    $content = New-Object System.Net.Http.MultipartFormDataContent
    $fileBytes = [System.IO.File]::ReadAllBytes($item.FilePath)
    $fileContent = New-Object System.Net.Http.ByteArrayContent(,$fileBytes)
    $fileContent.Headers.ContentType = [System.Net.Http.Headers.MediaTypeHeaderValue]::Parse("application/xml")
    $content.Add($fileContent, "file", (Split-Path $item.FilePath -Leaf))
    try {
        $response = $client.PostAsync("$url/events/upload", $content).GetAwaiter().GetResult()
        [int]$response.StatusCode
    } catch { -1 } finally { $client.Dispose() }
}
$uploadItems = @(
    [PSCustomObject]@{ CookieName = $cookieObjA.Name; CookieValue = $cookieObjA.Value; FilePath = "$projectRoot\test_files\commission-zero.xml" }
    [PSCustomObject]@{ CookieName = $cookieObjB.Name; CookieValue = $cookieObjB.Value; FilePath = "$projectRoot\test_files\commission-ninety.xml" }
)
$results4 = Invoke-Parallel -Body $body4 -InputItems $uploadItems
Write-Host "  Upload statuses: $($results4 -join ', ')"
# Where-Object returning exactly one match yields a bare PSCustomObject here, not a 1-element array -- @(...) is
# required or .Count reads $null instead of 1 (confirmed by a direct probe while building this script; the server
# side was already independently verified correct via curl before this was traced to a test-script-only bug).
$eventsAfter = Invoke-RestMethod -Uri "$BaseUrl/events" -Method Get -UseBasicParsing
$hasZero = @($eventsAfter | Where-Object { $_.eventName -eq "Zero Commission" }).Count -eq 1
$hasNinety = @($eventsAfter | Where-Object { $_.eventName -eq "Ninety Commission" }).Count -eq 1
Write-Host "  'Zero Commission' present exactly once: $hasZero"
Write-Host "  'Ninety Commission' present exactly once: $hasNinety"
$test4Pass = (($results4 | Where-Object {$_ -eq 200}).Count -eq 2) -and $hasZero -and $hasNinety
Write-Host "  PASS = $test4Pass" -ForegroundColor $(if ($test4Pass) { "Green" } else { "Red" })

Write-Host "`n=== SUMMARY ===" -ForegroundColor Yellow
Write-Host "Test 1 (same-name registration race): $test1Pass"
Write-Host "Test 2 (distinct-name registrations):  $test2Pass"
Write-Host "Test 3 (concurrent deposits, one acct): $test3Pass"
Write-Host "Test 4 (concurrent distinct uploads):   $test4Pass"
$allPass = $test1Pass -and $test2Pass -and $test3Pass -and $test4Pass
Write-Host "ALL PASS = $allPass" -ForegroundColor $(if ($allPass) { "Green" } else { "Red" })
