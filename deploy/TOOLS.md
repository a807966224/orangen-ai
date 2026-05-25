# TOOLS.md - Local Notes

Skills define _how_ tools work. This file is for _your_ specifics — the stuff that's unique to your setup.

---

## ⚠️ 工具限制备忘

| 工具 | 限制 | 应对 |
|------|------|------|
| `write` | 单次写入有长度上限（约 8000 字符） | 长文档必须拆分多个文件 |

---

## 🖥️ 开发环境

### 代码编辑器
- 主编辑器：IntelliJ IDEA (Ultimate)
- 插件：Kotlin, Spring Boot, Solidity, Rust

### 终端
- Windows Terminal + PowerShell 7
- WSL2 (Ubuntu 22.04)

---

## 🗄️ 本地服务 & 数据库

### 数据库
- PostgreSQL 15 → `localhost:5432`, 用户: `postgres`, 库: `slots_db`, `web3_db`
- MySQL 8.0 → `localhost:3306`, 用户: `root`, 库: `game_data`
- Redis 7 → `localhost:6379`

### Docker
- Docker Desktop for Windows
- 常用镜像：postgres:15, mysql:8, redis:7, jenkins/jenkins:lts
- Compose 文件位置：`~/projects/docker-compose.yml`

### Kubernetes
- 本地测试：minikube / kind
- 生产集群：kubectl 上下文 `prod-cluster`

---

## 📁 项目目录

- 所有项目根目录：`~/IdeaProjects/`
- Slots 逆向项目：`~/IdeaProjects/slots-reverse/`
- Web3 数据采集：`~/IdeaProjects/web3-collector/`
- Kotlin 学习笔记：`~/IdeaProjects/kotlin-learning/`

---

## 🌐 SSH & 远程  -> 暂无

### 常用 SSH 主机
- 开发服务器：`dev-server` → `192.168.1.100`, 用户: `scott`
- 生产跳板机：`prod-jump` → `jump.example.com`, 用户: `deploy`

### Git 平台
- GitHub：`github.com/a807966224` (主账号)
- GitLab：`gitlab.internal.com/scott` (公司私有 -> 暂未配置)
- 常用分支策略：`main` (生产) / `dev` (开发) / `feature/*`

---

## 🔧 CI/CD

- Jenkins：`http://localhost:8080`, 用户: `admin`
- 常用流水线：`slots-build`, `web3-deploy`

---

## 📊 逆向专用工具

- APK 分析：JADX, GDA, APKTool
- 网络抓包：Charles, Fiddler, Wireshark
- 内存分析：Frida, Xposed
- 数据采集脚本：`~/scripts/slots-scraper/`

---

## 🔔 Web3 资讯源

- 每日汇总脚本：`~/scripts/web3-news.sh`
- 推送通道：Telegram Bot, 企业微信
- 监控项目：CoinGecko API, DappRadar, DeFiLlama

---

## 💡 为什么分开存放？

Skills 定义的是通用工具，这里的配置是你专属的。分开存放便于更新技能时保留本地配置，也能安心分享技能而不泄露个人环境。

---

随时按需补充，这就是你的环境速查手册。

## 相关内容

- [Agent workspace](/concepts/agent-workspace)