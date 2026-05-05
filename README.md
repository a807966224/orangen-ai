# Orange AI

> 你的 AI 伙伴，随时待命

轻量优雅的 AI 对话平台，Kotlin + Spring Boot 后端 + 原生前端。

## 快速开始

```bash
cd backend-v2
build.bat      # 构建
run.bat        # 运行
```

或直接运行 `gradlew.bat run`。启动后访问 http://localhost:8088

前端无需构建，直接打开 `frontend/chat/index.html` 即可。

## 项目结构

```
orange/
├── frontend/                        # 前端
│   └── chat/index.html             # 聊天界面
│
└── backend-v2/                      # 后端 (Spring Boot + Ktor)
    └── src/main/kotlin/xyz/chengzi/backendv2/
        ├── controller/              # REST 控制器
        ├── service/                # 业务逻辑
        ├── chat/                   # 聊天存储抽象
        ├── entity/                 # JPA 实体
        ├── repository/             # 数据访问
        ├── model/                  # 模型代理
        └── config/                 # 配置
```

## 技术栈

| 层级 | 技术 |
|------|------|
| 后端框架 | Spring Boot 3.2 + Ktor 2.3 |
| 语言 | Kotlin 2.0 |
| 前端 | 原生 HTML5 + CSS3 + JS |
| 存储 | 内存 (默认) / PostgreSQL (可选) |
| AI 代理 | cliproxy (127.0.0.1:8317) |

## 配置

`backend-v2/src/main/resources/application.yml`:

```yaml
app:
  storage-type: IN_MEMORY   # POSTGRES
  cliproxy:
    api-url: http://127.0.0.1:8317
    api-key: your-api-key-1
server:
  port: 8088
```

## API

| 端点 | 方法 | 说明 |
|------|------|------|
| `/api/v1/chat` | POST | 发送消息 |
| `/api/v1/chat/history` | GET/POST | 聊天历史 |
| `/api/v1/chat/history/{id}` | GET/PUT/DELETE | 单个会话 |
| `/api/v1/models` | GET | 可用模型 |
| `/api/v1/web-search` | POST | 联网搜索 |

## 支持模型

DeepSeek V4 / R1、通义千问 Max、Kimi K2.6、GPT-4o、Claude 3.5、Gemini 2.0

## 主题

午夜紫 🌙 / 暖阳橙 🌅 / 薄荷绿 🌿

## License

MIT
