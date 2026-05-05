# CLIProxyAPI - Build from source (downloaded from GitHub)
FROM golang:1.26-alpine AS builder

ARG CLI_PROXY_REPO=https://github.com/router-for-me/CLIProxyAPI.git
ARG CLI_PROXY_BRANCH=main

WORKDIR /app

# Install git
RUN apk add --no-cache git

# Clone CLIProxyAPI from GitHub
RUN git clone --depth 1 --branch ${CLI_PROXY_BRANCH} ${CLI_PROXY_REPO} .

ARG VERSION=dev
ARG COMMIT=none
ARG BUILD_DATE=unknown

# Build
RUN CGO_ENABLED=0 GOOS=linux go build -ldflags="-s -w -X 'main.Version=${VERSION}' -X 'main.Commit=${COMMIT}' -X 'main.BuildDate=${BUILD_DATE}'" -o ./CLIProxyAPI ./cmd/server/

FROM alpine:3.23

RUN apk add --no-cache tzdata

RUN mkdir /CLIProxyAPI

COPY --from=builder /app/CLIProxyAPI /CLIProxyAPI/CLIProxyAPI

WORKDIR /CLIProxyAPI

EXPOSE 8317

ENV TZ=Asia/Shanghai

RUN cp /usr/share/zoneinfo/${TZ} /etc/localtime && echo "${TZ}" > /etc/timezone

CMD ["./CLIProxyAPI"]