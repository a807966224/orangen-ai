# Orange AI - Agent Notes

## Build & Run

```bash
cd backend
build.bat      # Windows build
run.bat        # Windows run
# or
gradlew.bat run
```

- **JVM 21 required** (`kotlin.jvmToolchain=21` in gradle.properties)
- **Default server port**: 8088 (configured in `application.yml`)
- **Gradle 9.3.0** (from gradle-wrapper.properties)

## Architecture

- **Backend**: Kotlin/Spring Boot 3.2.0 + Ktor 2.3.x (appears in docs only; actual code uses Spring Boot)
- **Frontend**: Static HTML/CSS/JS in `frontend/` — served separately or deployed to GitHub Pages
- **Entry point**: `com.orange.ai.OrangeAiApplicationKt`
- **Storage**: `InMemoryChatStorage` (default) or `JpaChatStorage` (when `app.storage-type=POSTGRES`)
- **AI proxy**: Requests forwarded to `cliproxy` at `http://127.0.0.1:8317` (configured in `application.yml`)

## Key Config (`application.yml`)

```yaml
app:
  storage-type: IN_MEMORY   # or POSTGRES
  cliproxy:
    api-url: http://127.0.0.1:8317
    api-key: your-api-key-1
```

## CI/CD

- GitHub Pages deployment: pushes `frontend/` folder on `main` branch push
- Artifact upload: `actions/upload-pages-artifact@v3`

## No Tests

`backend/src/test/` does not exist — no test suite configured.

## Project Structure

```
backend/src/main/kotlin/com/orange/ai/
├── OrangeAiApplication.kt    # Spring Boot entry
├── controller/               # REST endpoints (/api/v1/*)
├── service/                  # Business logic
├── chat/                     # Storage abstraction (InMemory vs JPA)
├── entity/                   # JPA entities (ChatSession, ChatMessage)
├── repository/               # JPA repositories
└── config/                   # AppConfig, WebConfig
```
