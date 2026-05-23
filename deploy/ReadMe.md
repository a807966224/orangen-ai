
---

## ⚠️ 强烈建议：先备份现有配置

```powershell
Compress-Archive -Path "$env:USERPROFILE\.openclaw" -Destination "$env:USERPROFILE\openclaw-backup-$(Get-Date -Format 'yyyyMMdd-HHmmss').zip" -ErrorAction SilentlyContinue
```

---

## 1. 运行脚本

```powershell
Set-ExecutionPolicy -ExecutionPolicy RemoteSigned -Scope Process
```

---

## 2. 安装 PowerShell 7

```powershell
winget install --id Microsoft.PowerShell --source winget
```

---

## 3. 切换至 PowerShell 7

```powershell
pwsh
```

---

## 4. 确保 pnpm 已安装

```powershell
pnpm setup
```

---

## 5. 运行部署脚本

```powershell
.\deploy-openclaw-team.ps1
```

> 该脚本的最终目的是帮你把多 Agent 配置好，你还需要针对性调整 OpenClaw 的配置。

---

## 6. 后续配置步骤

| 命令 | 作用 |
|------|------|
| `openclaw onboard` | 配置模型及渠道 |
| `openclaw dashboard` | 启动网关 |
| `openclaw doctor` | 检查是否健康 |

> 💡 **提示**：以上这一大堆操作，都不如你把模型配置好，然后直接让它来帮你修复问题。

---

## 7. 写入灵魂文件

将灵魂文件粘贴到 **Helper 工作空间**中。

---

## 8. 重启网关生效

```powershell
openclaw gateway restart
```