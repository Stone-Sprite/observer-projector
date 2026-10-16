# 把 Observer Projector 推上 GitHub 并建 release。
#
# 用法（token 需要 repo 权限，经典 PAT 勾 repo 即可；fine-grained 需要
# Contents: Read and write + Administration: Read and write 来建仓库）：
#
#     .\publish-github.ps1 -Token ghp_xxxxxxxx
#
# 或者先设环境变量再直接跑：
#
#     $env:GH_TOKEN = "ghp_xxxxxxxx"; .\publish-github.ps1

param(
    [string]$Token = $env:GH_TOKEN,
    [string]$Owner,
    [string]$RepoName = "observer-projector",
    [string]$Tag = "v0.1.0",
    [string]$Version = "0.1.0"
)

$ErrorActionPreference = "Stop"

if (-not $Token) { throw "需要 -Token，或先设置 `$env:GH_TOKEN" }

$api = "https://api.github.com"
$hdr = @{
    Authorization          = "Bearer $Token"
    Accept                 = "application/vnd.github+json"
    "X-GitHub-Api-Version" = "2022-11-28"
    "User-Agent"           = "observer-projector-publish"
}

function Api([string]$Method, [string]$Url, $Body) {
    $p = @{ Method = $Method; Uri = $Url; Headers = $hdr }
    if ($Body) { $p.Body = ($Body | ConvertTo-Json -Depth 6); $p.ContentType = "application/json" }
    return Invoke-RestMethod @p
}

# 1) 确认身份
$me = Api GET "$api/user" $null
if (-not $Owner) { $Owner = $me.login }
Write-Host "身份: $($me.login)   目标仓库: $Owner/$RepoName"

# 2) 仓库不存在就建
$exists = $true
try { Api GET "$api/repos/$Owner/$RepoName" $null | Out-Null } catch { $exists = $false }
if (-not $exists) {
    Write-Host "创建仓库 $Owner/$RepoName ..."
    Api POST "$api/user/repos" @{
        name        = $RepoName
        description = "把 Minecraft 的侦测器变成一台投影仪：本视频、B站、CCTV 都能投到墙上。Minecraft 1.21.11 / NeoForge + Fabric / client-side."
        homepage    = ""
        private     = $false
        has_issues  = $true
        has_wiki    = $false
    } | Out-Null
} else {
    Write-Host "仓库已存在，直接推。"
}

# 3) 推代码
$repoDir = "D:\dsh-build\observer-projector\repo"
Push-Location $repoDir
$remote = "https://$Owner`:$Token@github.com/$Owner/$RepoName.git"
& git remote remove origin 2>$null
& git remote add origin $remote
& git branch -M main
Write-Host "推送 main ..."
& git -c credential.helper= push -u origin main --force
if ($LASTEXITCODE -ne 0) { Pop-Location; throw "推送失败" }
# 把带 token 的 remote 换回干净的地址，别把 token 留在配置里
& git remote set-url origin "https://github.com/$Owner/$RepoName.git"
Pop-Location

# 4) 建 release + 传 jar
$relDir = "D:\dsh-build\observer-projector\release\$Version"
$notes = Get-Content (Join-Path $repoDir "CHANGELOG.md") -Raw -Encoding UTF8

$rel = $null
try { $rel = Api GET "$api/repos/$Owner/$RepoName/releases/tags/$Tag" $null } catch { }
if (-not $rel) {
    Write-Host "创建 release $Tag ..."
    $rel = Api POST "$api/repos/$Owner/$RepoName/releases" @{
        tag_name         = $Tag
        target_commitish = "main"
        name             = "Observer Projector $Version"
        body             = $notes
        draft            = $false
        prerelease       = $false
    }
}

foreach ($jar in Get-ChildItem $relDir -Filter "*.jar") {
    Write-Host "上传 $($jar.Name) ..."
    $url = "$api/repos/$Owner/$RepoName/releases/$($rel.id)/assets?name=$([uri]::EscapeDataString($jar.Name))"
    $up = @{
        Authorization          = "Bearer $Token"
        Accept                 = "application/vnd.github+json"
        "Content-Type"         = "application/java-archive"
        "User-Agent"           = "observer-projector-publish"
    }
    Invoke-RestMethod -Method POST -Uri $url -Headers $up -InFile $jar.FullName | Out-Null
}

Write-Host ""
Write-Host "完成：" -ForegroundColor Green
Write-Host "  https://github.com/$Owner/$RepoName"
Write-Host "  https://github.com/$Owner/$RepoName/releases/tag/$Tag"