<#
.SYNOPSIS
    OpenClaw 生产级AI开发团队部署脚本 v5 - 修复进程调用问题
.DESCRIPTION
    1. 彻底卸载现有 OpenClaw 环境
    2. 按6人团队（Helper/Developer/Reviewer/DevOps/Businesser/Tester）全新部署
    3. 移除 Start-Process，全部使用直接调用，避免“不是有效的Win32应用程序”错误
.NOTES
    版本: 5.0 | 要求: PowerShell 7+ (以管理员身份运行)
#>

#Requires -Version 7.0
#Requires -RunAsAdministrator

$ErrorActionPreference = "Stop"
$ProgressPreference = 'SilentlyContinue'

$Global:LogFile = "openclaw-team-deploy.log"
$Global:BaseDir = "$env:USERPROFILE\.openclaw"
$Model = "anthropic/claude-sonnet-4.6"

Function Write-Log {
    param([string]$Message, [string]$Level = "INFO")
    $time = Get-Date -Format "yyyy-MM-dd HH:mm:ss"
    "$time [$Level] $Message" | Tee-Object -FilePath $Global:LogFile -Append
}

Function Test-CommandExists {
    param([string]$Command)
    return [bool](Get-Command $Command -ErrorAction SilentlyContinue)
}

# 简单的命令执行函数，直接使用 & 调用，不依赖 Start-Process
Function Invoke-CliCommand {
    param(
        [string]$Command,
        [string[]]$Arguments,
        [string]$ErrorMessage,
        [bool]$IgnoreExitCode = $false
    )
    try {
        $fullArgs = $Arguments -join ' '
        Write-Log "执行: $Command $fullArgs"

        # 直接调用，不捕获输出，进度条实时显示
        & $Command @Arguments
        $exitCode = $LASTEXITCODE

        if ($exitCode -ne 0 -and -not $IgnoreExitCode) {
            throw "$ErrorMessage (退出码: $exitCode)"
        }
        Write-Log "执行成功: $Command $fullArgs"
    } catch {
        if ($IgnoreExitCode) {
            Write-Log "$ErrorMessage (已忽略): $_" "WARN"
        } else {
            throw
        }
    }
}

# ================== 阶段一：彻底卸载 ==================
Write-Log "========== 开始卸载现有 OpenClaw 环境 ==========" "INFO"

# 1. 停止网关（直接调用）
Write-Log "停止网关服务..." "INFO"
try {
    openclaw gateway stop 2>$null
    Start-Sleep 2
    Write-Log "网关已停止"
} catch {
    Write-Log "网关停止失败或未运行，继续..." "WARN"
}

# 2. 移除计划任务
Write-Log "移除计划任务..." "INFO"
$taskName = "OpenClawGateway"
if (Get-ScheduledTask -TaskName $taskName -ErrorAction SilentlyContinue) {
    Unregister-ScheduledTask -TaskName $taskName -Confirm:$false
    Write-Log "已移除计划任务: $taskName"
} else {
    Write-Log "未找到计划任务，跳过"
}

# 3. 卸载全局包（兼容 npm/pnpm/yarn，直接调用）
Write-Log "卸载全局 CLI 包..." "INFO"
$packages = @("@openclaw/cli", "clawhub", "clawdhub")
$managers = @("npm", "pnpm", "yarn")
foreach ($pkg in $packages) {
    foreach ($mgr in $managers) {
        if (Test-CommandExists $mgr) {
            try {
                Invoke-CliCommand $mgr @('uninstall', '-g', $pkg) "卸载 $pkg 失败" -IgnoreExitCode $true
            } catch {}
        }
    }
}

# 4. 删除配置目录
Write-Log "删除配置目录 $Global:BaseDir ..." "WARN"
if (Test-Path $Global:BaseDir) {
    Remove-Item -Path $Global:BaseDir -Recurse -Force -ErrorAction Stop
    Write-Log "配置目录已删除"
} else {
    Write-Log "配置目录不存在，跳过"
}

# 5. 清理残留进程
Write-Log "清理残留进程..." "INFO"
Get-Process -Name "node" -ErrorAction SilentlyContinue | Where-Object { $_.CommandLine -like "*openclaw*" } | Stop-Process -Force -ErrorAction SilentlyContinue

Write-Log "========== 卸载完成，开始全新部署 ==========" "INFO"
Start-Sleep 2

# ================== 阶段二：环境检查 ==================
Write-Log "🔍 检查系统环境..." "INFO"
if (-not (Test-CommandExists "node")) {
    Write-Log "❌ 未找到 Node.js，请安装 Node.js 22+ 后重试" "FATAL"
    exit 1
}

$PackageManager = if (Test-CommandExists "pnpm") { "pnpm" } else { "npm" }
Write-Log "📦 使用包管理器: $PackageManager" "INFO"

# 检查 PATH 是否包含 pnpm 全局 bin（如果使用了 pnpm）
if ($PackageManager -eq "pnpm") {
    $pnpmBin = & $PackageManager bin -g
    if (-not ($env:PATH -split ';' -contains $pnpmBin)) {
        Write-Log "⚠️ pnpm 全局 bin 目录不在 PATH 中，执行 pnpm setup 以修复，或重新打开终端。" "WARN"
        # 临时添加到当前会话的 PATH
        $env:PATH += ";$pnpmBin"
    }
}

# ================== 阶段三：安装 OpenClaw ==================
Write-Log "🚀 安装 OpenClaw CLI..." "INFO"
Invoke-CliCommand $PackageManager @('install', '-g', 'openclaw@latest') "安装 OpenClaw CLI 失败"
Write-Log "OpenClaw CLI 安装完成"

# 安装技能管理器
$skillInstaller = "clawhub"
try {
    Invoke-CliCommand $PackageManager @('install', '-g', $skillInstaller) "安装 clawhub 失败"
    Write-Log "技能管理器 $skillInstaller 安装完成"
} catch {
    Write-Log "尝试安装 clawdhub..." "WARN"
    $skillInstaller = "clawdhub"
    Invoke-CliCommand $PackageManager @('install', '-g', $skillInstaller) "安装 clawdhub 也失败"
}

# ================== 阶段四：初始化配置 ==================
Write-Log "⚙️ 执行非交互式初始化..." "INFO"
Invoke-CliCommand "openclaw" @('onboard', '--non-interactive', '--accept-risk', '--skip-health') "初始化配置失败"

# ================== 阶段五：安装技能 ==================
Write-Log "🧩 安装团队技能..." "INFO"

$commonSkills = @("agent-browser", "elite-longterm-memory", "duckduckgo-search")
foreach ($skill in $commonSkills) {
    Write-Log "安装: $skill"
    Invoke-CliCommand $skillInstaller @('install', $skill) "安装技能 $skill 失败" -IgnoreExitCode $true
}

$teamSkills = @(
    "backend-patterns", "api-dev", "coding-agent", "git-essentials",
    "pr-reviewer", "github",
    "deploy-agent", "docker-essentials",
    "business-model-canvas", "product-manager", "product-strategist", "document-generator",
    "tdd-guide"
)
foreach ($skill in $teamSkills) {
    Write-Log "安装: $skill"
    Invoke-CliCommand $skillInstaller @('install', $skill) "安装技能 $skill 失败" -IgnoreExitCode $true
}

# ================== 阶段六：创建目录 ==================
Write-Log "📁 创建 Agent 工作区..." "INFO"
$agents = @("helper", "developer", "reviewer", "devops", "businesser", "tester")
foreach ($agent in $agents) {
    $null = New-Item -ItemType Directory -Force -Path "$Global:BaseDir\ws-$agent"
    $null = New-Item -ItemType Directory -Force -Path "$Global:BaseDir\agents\$agent"
}

# ================== 阶段七：生成配置 ==================
Write-Log "🤖 生成团队协同配置..." "INFO"
$agentList = @(
    @{
        id = "helper"; workspace = "$Global:BaseDir\ws-helper"; agentDir = "$Global:BaseDir\agents\helper"
        model = $Model
        skills = @("agent-browser","elite-longterm-memory","duckduckgo-search","backend-patterns","api-dev","coding-agent","git-essentials","pr-reviewer","github","deploy-agent","docker-essentials","business-analysis","product-planning","documentation","tdd-guide")
    },
    @{
        id = "developer"; workspace = "$Global:BaseDir\ws-developer"; agentDir = "$Global:BaseDir\agents\developer"
        model = $Model
        skills = @("backend-patterns","api-dev","coding-agent","git-essentials")
    },
    @{
        id = "reviewer"; workspace = "$Global:BaseDir\ws-reviewer"; agentDir = "$Global:BaseDir\agents\reviewer"
        model = $Model
        skills = @("pr-reviewer","github")
    },
    @{
        id = "devops"; workspace = "$Global:BaseDir\ws-devops"; agentDir = "$Global:BaseDir\agents\devops"
        model = $Model
        skills = @("deploy-agent","docker-essentials")
    },
    @{
        id = "businesser"; workspace = "$Global:BaseDir\ws-businesser"; agentDir = "$Global:BaseDir\agents\businesser"
        model = $Model
        skills = @("business-analysis","product-planning","documentation")
    },
    @{
        id = "tester"; workspace = "$Global:BaseDir\ws-tester"; agentDir = "$Global:BaseDir\agents\tester"
        model = $Model
        skills = @("tdd-guide")
    }
)

$config = @{
    agents = @{ list = $agentList }
    tools = @{ agentToAgent = @{ enabled = $true; allow = $agents } }
}

$configPath = "$Global:BaseDir\openclaw.json"
$tempConfigPath = "$configPath.tmp"
$config | ConvertTo-Json -Depth 10 | Set-Content -Path $tempConfigPath -Force
Move-Item -Path $tempConfigPath -Destination $configPath -Force
Write-Log "配置文件已写入: $configPath"

# ================== 阶段八：写入角色定义 ==================
Write-Log "🧠 写入角色定义 (SOUL.md)..." "INFO"
$souls = @{
    helper = "# 默认助手 (Helper)`n你是团队的总调度。接收所有任务，评估后分派给相应专家。监控进度，整合结果。"
    developer = "# 架构与开发者 (Developer)`n你负责系统设计、技术选型、编码实现和功能开发。"
    reviewer = "# 代码审查员 (Reviewer)`n你专注PR审查、代码质量把控，确保每一行代码都经过严格评审。"
    devops = "# 运维工程师 (DevOps)`n你负责部署、CI/CD流程，保障系统稳定运行。"
    businesser = "# 需求分析师 (Businesser)`n你整合业务诉求，规划产品界面，沉淀需求文档。"
    tester = "# 测试工程师 (Tester)`n你负责测试设计、执行与质量保证，驱动TDD实践。"
}
foreach ($agent in $agents) {
    Set-Content -Path "$Global:BaseDir\ws-$agent\SOUL.md" -Value $souls[$agent] -Force
}

# ================== 阶段九：启动网关 ==================
Write-Log "🚀 启动网关服务..." "INFO"
try { openclaw gateway stop 2>$null } catch {}

# 安装守护进程（可选）
Invoke-CliCommand "openclaw" @('onboard', '--non-interactive', '--install-daemon') "网关守护进程安装失败" -IgnoreExitCode $true
Start-Sleep 2
# 直接启动网关
Invoke-CliCommand "openclaw" @('gateway', 'run') "网关启动失败" -IgnoreExitCode $true

# ================== 阶段十：健康检查 ==================
Write-Log "⏳ 等待服务就绪..." "INFO"
$maxRetries = 8
$retryCount = 0
$healthy = $false
while ($retryCount -lt $maxRetries -and -not $healthy) {
    try {
        $output = openclaw gateway status --json 2>$null
        $status = ($output | Out-String) | ConvertFrom-Json
        if ($status -and $status.running) {
            Write-Log "✅ 网关服务运行正常。" "INFO"
            $healthy = $true
        } else {
            Write-Log "网关未就绪，等待... ($($retryCount+1)/$maxRetries)"
        }
    } catch {
        Write-Log "检查失败，重试... ($($retryCount+1)/$maxRetries)"
    }
    Start-Sleep 5
    $retryCount++
}
if (-not $healthy) {
    throw "网关启动失败，请检查日志: $Global:LogFile"
}

Write-Log "🎉 部署成功！团队已就绪:" "INFO"
Write-Log "========================" "INFO"
openclaw agents list
openclaw gateway status
Write-Log "========================" "INFO"
Write-Log "使用 'openclaw tui' 与团队交互，或访问 WebUI: http://127.0.0.1:18789"