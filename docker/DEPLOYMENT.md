# Orange AI Docker 部署指南

## 目录结构

```
orange/
├── backend-v2/                      # Orange AI 后端 (7777端口)
└── docker/                         # Docker 配置
    ├── docker-compose.yaml         # 统一编排配置
    ├── Dockerfile                  # CLIProxyAPI 构建文件 (从GitHub下载)
    ├── Dockerfile.backend          # backend-v2 构建文件
    ├── Dockerfile.management-center # 管理UI 构建文件 (从GitHub下载)
    └── nginx.conf                  # 管理UI nginx配置 (备用)
```

**注意**: 所有依赖项目均从 GitHub 自动下载构建，无需手动克隆。

## 快速启动

### 前置条件

1. Docker 和 Docker Compose 已安装

### 首次运行（下载配置文件）

```bash
# 安装wsl & docker
wsl --install

# 进 WSL2 装 Docker（复制粘贴就行）
wsl bash -c "curl -fsSL https://get.docker.com | sudo sh && sudo usermod -aG docker $USER"

# 进 WSL2
wsl

# 添加docker用户组
groups $USER
sudo usermod -aG docker $USER
sudo usermod -aG docker $USER

# 启动 Docker
sudo service docker start

# 测试
docker run hello-world


cd orange/docker

# 创建必要目录
mkdir -p auths logs data

# 首次运行：下载默认配置文件（仅首次需要）
docker-compose --profile init up

# 然后编辑 config.yaml 自定义配置
# nano config.yaml

# 启动所有服务
docker-compose up -d --build
```

### 后续运行（跳过下载）

```bash
cd orange/docker
docker-compose up -d --build
```

### 查看服务状态

```bash
docker-compose ps
```

### 查看日志

```bash
docker-compose logs -f
```

## 服务端口

| 服务 | 端口 | 说明 |
|------|------|------|
| cli-proxy-api | 8317 | AI代理API服务 |
| backend-v2 | 7777 | Orange AI 后端服务 |
| management-center | 5173 | CLIProxyAPI 管理UI |

## 配置说明

### 关于 config.yaml

首次运行 `docker-compose --profile init up` 会自动从 GitHub 下载默认配置文件到 `docker/config.yaml`。

之后你可以编辑 `config.yaml` 自定义配置，重新启动服务即可生效：

```bash
nano config.yaml
docker-compose restart cli-proxy-api
```

### 环境变量

可以在 `orange/docker/.env` 文件中设置以下配置：

```env
# CLIProxyAPI 构建参数
CLI_PROXY_BRANCH=main
CLI_PROXY_CONFIG_PATH=./config.yaml
CLI_PROXY_AUTH_PATH=./auths
CLI_PROXY_LOG_PATH=./logs

# Management Center 构建参数
MANAGEMENT_CENTER_BRANCH=main

# Backend
CLIPROXY_API_KEY=your-api-key-1
BACKEND_DATA_PATH=./data

# Build args
VERSION=dev
COMMIT=none
BUILD_DATE=unknown
```

### 目录结构（运行后）

```
docker/
├── config.yaml         # CLIProxyAPI 配置文件（首次从GitHub下载）
├── auths/              # 认证文件目录
├── logs/               # 日志目录
└── data/               # 后端数据目录
```

## 访问服务

- **管理UI**: http://localhost:5173
- **后端API**: http://localhost:7777
- **CLIProxyAPI**: http://localhost:8317

## 添加AI提供商

1. 打开 http://localhost:5173
2. 进入管理界面
3. 添加 API Key 或认证文件
4. 配置AI提供商

## 停止服务

```bash
docker-compose down
```

如需删除所有数据：

```bash
docker-compose down -v
```

## 故障排除

### 服务无法启动

```bash
# 查看详细日志
docker-compose logs -f <service-name>

# 例如查看backend-v2日志
docker-compose logs -f backend-v2
```

### 网络连接问题

确保所有服务在同一个 `orange-network` 网络中：

```bash
docker network inspect orange-ai-network
```

### 端口冲突

如果端口被占用，修改 `docker-compose.yaml` 中的端口映射。

### 如何使用

- 管理界面: http://localhost:5173
- 后端 API: http://localhost:7777
- AI 代理: http://localhost:8317  