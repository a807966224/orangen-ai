# Orange AI - Agent Notes

## Build & Run

```bash
cd backend-v2
./gradlew build   # Build
./gradlew run    # Run
# or
gradlew.bat build
gradlew.bat run
```

- **JVM 21 required** (`java.toolchain.languageVersion = 21` in `build.gradle.kts`)
- **Default server port**: 7777 (configured in `application.yml`)
- **Gradle 9.4.1** (from `gradle-wrapper.properties`)

## Architecture

- **Backend**: Kotlin 2.2.21 + Spring Boot 4.0.6
- **Frontend**: Static HTML in `frontend/status/index.html` and `frontend/chat/` (note: `frontend/chat/` does not exist in this repo — frontend is served separately)
- **Entry point**: `xyz.chengzi.backendv2.BackendV2Application`
- **Storage**: `FILE` (default) storing to `./data/chat/`
- **AI proxy**: Requests forwarded to `cliproxy` at `http://192.168.0.115:8317` (configured in `application.yml`)

## Key Config (`application.yml`)

```yaml
server:
  port: 7777

app:
  storage-type: FILE
  storage:
    file:
      path: ./data/chat
  cliproxy:
    api-url: http://192.168.0.115:8317
    api-key: your-api-key-1
```

## Project Structure

```
backend-v2/src/main/kotlin/xyz/chengzi/backendv2/
├── BackendV2Application.kt    # Spring Boot entry
├── controller/
│   └── ChatController.kt      # REST endpoints (/api/v1/*)
├── service/
│   └── CliproxyService.kt    # AI proxy forwarding
├── chat/                      # Chat storage
├── entity/                    # JPA entities
├── repository/                # Data access
├── model/                    # Model types
└── config/
    └── JpaConfig.kt           # JPA configuration
```

## Important Notes

- **No test suite**: `backend-v2/src/test/` does not exist
- **Storage is FILE-based**, not IN_MEMORY — chat data persists in `./data/chat/`
- The cliproxy endpoint is on `192.168.0.115`, not `127.0.0.1` — this is a LAN address

## CI/CD

- GitHub Pages deployment pushes `frontend/` folder on `main` branch push
