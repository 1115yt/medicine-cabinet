$ErrorActionPreference = 'Stop'
$projectDir = Split-Path $PSScriptRoot -Parent
$buildScript = Get-Content -LiteralPath (Join-Path $projectDir 'app/build.gradle.kts') -Raw

# 读取实际构建规则，只做地址边界检查，不向示例域名发起网络请求。
$patternMatch = [regex]::Match($buildScript, 'val catalogOriginPattern\s*=\s*Regex\(\s*"([^"]+)"\s*\)')
$lengthMatch = [regex]::Match($buildScript, 'catalogAddress\.length\s*<=\s*(\d+)')
if (!$patternMatch.Success -or !$lengthMatch.Success) {
    throw '无法读取构建地址校验规则，请核对 build.gradle.kts。'
}
$originPattern = '\A' + $patternMatch.Groups[1].Value.Replace('\\', '\') + '\z'
$maximumLength = [int]$lengthMatch.Groups[1].Value

$validOrigins = @(
    '',
    'https://medicine-cabinet-catalog.2635178231.workers.dev',
    'https://medicine-api.eecld.icu',
    'https://catalog.example.test',
    'https://api.example.cn',
    'https://catalog.xn--fiqs8s',
    ('https://' + ('a' * 63) + '.example.com'),
    ('https://' + ((('a' * 63) + '.') * 3) + ('b' * 61))
)
$invalidOrigins = @(
    'http://catalog.example.test',
    'HTTPS://catalog.example.test',
    'https://localhost',
    'https://127.0.0.1',
    'https://user:sample@catalog.example.test',
    'https://catalog.example.test:443',
    'https://catalog.example.test/path',
    'https://catalog.example.test/',
    'https://catalog.example.test?sample=1',
    'https://catalog.example.test#sample',
    'https://-catalog.example.test',
    'https://catalog-.example.test',
    'https://catalog_1.example.test',
    'https://catalog..example.test',
    ('https://' + ('a' * 64) + '.example.test'),
    ('https://' + ((('a' * 63) + '.') * 3) + ('b' * 62))
)

foreach ($originTestCase in $validOrigins) {
    if ($originTestCase.Length -ne 0 -and
        ($originTestCase.Length -gt $maximumLength -or ![regex]::IsMatch($originTestCase, $originPattern))) {
        throw "合法示例被拒绝：$originTestCase"
    }
}
foreach ($originTestCase in $invalidOrigins) {
    if ($originTestCase.Length -le $maximumLength -and [regex]::IsMatch($originTestCase, $originPattern)) {
        throw "无效示例被接受：$originTestCase"
    }
}
Write-Output ('固定 HTTPS 地址规则检查通过：{0} 个合法示例、{1} 个无效示例。' -f $validOrigins.Count, $invalidOrigins.Count)
