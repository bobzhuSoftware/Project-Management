# Builds throwaway git repos for the branch-management scenarios and checks them.
# Without -ApiBase it only validates the git-level facts of each fixture.
# With -ApiBase it also registers each fixture as a scratch project, reads
# /git/status, compares it with the expectations, and deletes the project again.
# Usage: powershell -ExecutionPolicy Bypass -File <abs path>\git-scenarios.ps1 [-ApiBase http://localhost:8090] [-Keep]
param(
    [string]$ApiBase = '',
    [switch]$Keep
)

$ErrorActionPreference = 'Stop'
$env:GIT_AUTHOR_NAME = 'pm-scenario'
$env:GIT_AUTHOR_EMAIL = 'pm-scenario@example.invalid'
$env:GIT_COMMITTER_NAME = 'pm-scenario'
$env:GIT_COMMITTER_EMAIL = 'pm-scenario@example.invalid'

$base = Join-Path ([IO.Path]::GetTempPath()) ('pm-git-scenarios-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $base | Out-Null

function G {
    param([string]$Dir, [Parameter(ValueFromRemainingArguments = $true)][string[]]$GitArgs)
    $old = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    $out = & git -C $Dir @GitArgs 2>&1 | Out-String
    $code = $LASTEXITCODE
    $ErrorActionPreference = $old
    [pscustomobject]@{ Code = $code; Out = $out.Trim() }
}

function GOk {
    param([string]$Dir, [Parameter(ValueFromRemainingArguments = $true)][string[]]$GitArgs)
    $r = G $Dir @GitArgs
    if ($r.Code -ne 0) { throw "git $($GitArgs -join ' ') failed in ${Dir}: $($r.Out)" }
    $r.Out
}

function Commit-File {
    param([string]$Dir, [string]$Name, [string]$Text)
    Set-Content -Path (Join-Path $Dir $Name) -Value $Text
    GOk $Dir add -A | Out-Null
    GOk $Dir commit -m "add $Name" | Out-Null
}

# Shared bare remote seeded with main.
$remote = Join-Path $base 'origin.git'
New-Item -ItemType Directory -Path $remote | Out-Null
GOk $remote init --bare | Out-Null
GOk $remote symbolic-ref HEAD refs/heads/main | Out-Null

$seed = Join-Path $base 'seed'
GOk $base clone $remote $seed 2>&1 | Out-Null
GOk $seed symbolic-ref HEAD refs/heads/main | Out-Null
Commit-File $seed 'readme.txt' 'seed'
GOk $seed push -u origin main | Out-Null

function New-Clone([string]$Name) {
    $dir = Join-Path $base $Name
    GOk $base clone $remote $dir | Out-Null
    $dir
}

# Each scenario: Name, Dir, expected hasUpstream, expected remote branch exists, expected hasRemote.
$scenarios = @()

$d = New-Clone 'tracked-main'
$scenarios += [pscustomobject]@{ Name = 'tracked-main'; Dir = $d; Branch = 'main'; Upstream = $true; RemoteBranch = $true; HasRemote = $true }

$d = New-Clone 'local-only-branch'
GOk $d switch -c feature-local | Out-Null
Commit-File $d 'a.txt' 'a'
$scenarios += [pscustomobject]@{ Name = 'local-only-branch'; Dir = $d; Branch = 'feature-local'; Upstream = $false; RemoteBranch = $false; HasRemote = $true }

# Remote already has the branch (pushed from another clone) but this clone does not track it.
$other = New-Clone 'helper-clone'
GOk $other switch -c feature-remote | Out-Null
Commit-File $other 'b.txt' 'b'
GOk $other push -u origin feature-remote | Out-Null
$d = New-Clone 'remote-same-name-untracked'
GOk $d fetch origin | Out-Null
GOk $d switch --no-track -c feature-remote origin/feature-remote | Out-Null
$scenarios += [pscustomobject]@{ Name = 'remote-same-name-untracked'; Dir = $d; Branch = 'feature-remote'; Upstream = $false; RemoteBranch = $true; HasRemote = $true }

$d = New-Clone 'published-tracked'
GOk $d switch -c feature-pub | Out-Null
Commit-File $d 'c.txt' 'c'
GOk $d push -u origin feature-pub | Out-Null
$scenarios += [pscustomobject]@{ Name = 'published-tracked'; Dir = $d; Branch = 'feature-pub'; Upstream = $true; RemoteBranch = $true; HasRemote = $true }

$d = New-Clone 'detached-head'
GOk $d checkout --detach | Out-Null
$scenarios += [pscustomobject]@{ Name = 'detached-head'; Dir = $d; Branch = $null; Upstream = $false; RemoteBranch = $false; HasRemote = $true }

$d = Join-Path $base 'no-remote'
New-Item -ItemType Directory -Path $d | Out-Null
GOk $d init | Out-Null
GOk $d symbolic-ref HEAD refs/heads/main | Out-Null
Commit-File $d 'd.txt' 'd'
$scenarios += [pscustomobject]@{ Name = 'no-remote'; Dir = $d; Branch = 'main'; Upstream = $false; RemoteBranch = $false; HasRemote = $false }

$fail = 0
$rows = @()
$createdIds = @()

try {
    foreach ($s in $scenarios) {
        $br = (G $s.Dir symbolic-ref --short -q HEAD).Out
        $hasUp = (G $s.Dir rev-parse --abbrev-ref '@{u}').Code -eq 0
        $remoteBranch = $false
        if ($br) { $remoteBranch = (G $s.Dir show-ref --verify --quiet "refs/remotes/origin/$br").Code -eq 0 }
        $hasRemote = (G $s.Dir config --get remote.origin.url).Code -eq 0

        $gitOk = ($hasUp -eq $s.Upstream) -and ($remoteBranch -eq $s.RemoteBranch) -and ($hasRemote -eq $s.HasRemote)
        if ($s.Branch -and $br -ne $s.Branch) { $gitOk = $false }
        if (-not $gitOk) { $fail++ }

        $apiText = '-'
        if ($ApiBase) {
            $body = @{
                name = "pm-scenario-$($s.Name)"
                rootDirectory = $s.Dir
                category = 'APPLICATION'
                pushEnabled = $false
                launches = @(@{ name = 'noop'; startCommand = 'echo noop' })
            } | ConvertTo-Json -Depth 5
            $proj = Invoke-RestMethod -Method Post -Uri "$ApiBase/api/projects" -ContentType 'application/json' -Body $body
            $createdIds += $proj.id
            $st = Invoke-RestMethod -Method Get -Uri "$ApiBase/api/projects/$($proj.id)/git/status?refresh=true"
            $apiOk = ($st.hasUpstream -eq $s.Upstream) -and ($st.remoteBranchExists -eq $s.RemoteBranch) -and ($st.hasRemote -eq $s.HasRemote) -and ($st.detached -eq ($null -eq $s.Branch))
            if ($s.Branch -and $st.branch -ne $s.Branch) { $apiOk = $false }
            if ($null -eq $s.Branch -and $st.branch) { $apiOk = $false }
            if (-not $apiOk) { $fail++ }
            $apiText = "branch=$($st.branch) detached=$($st.detached) up=$($st.upstreamName) default=$($st.defaultBranch) " + $(if ($apiOk) { 'PASS' } else { 'FAIL' })
        }

        $rows += [pscustomobject]@{
            Scenario = $s.Name
            Git = $(if ($gitOk) { 'PASS' } else { 'FAIL' })
            Branch = $br
            HasUpstream = $hasUp
            RemoteBranch = $remoteBranch
            HasRemote = $hasRemote
            Api = $apiText
        }
    }
}
finally {
    foreach ($id in $createdIds) {
        try { Invoke-RestMethod -Method Delete -Uri "$ApiBase/api/projects/$id" | Out-Null }
        catch { Write-Warning "Could not delete scratch project ${id}: $($_.Exception.Message)" }
    }
}

$rows | Format-Table -AutoSize | Out-String -Width 250 | Write-Output

if ($Keep) {
    Write-Output "Fixtures kept at $base"
} else {
    Remove-Item -Recurse -Force $base -ErrorAction SilentlyContinue
}

if ($fail -gt 0) { Write-Output "FAILED checks: $fail"; exit 1 }
Write-Output 'All scenario checks passed.'
