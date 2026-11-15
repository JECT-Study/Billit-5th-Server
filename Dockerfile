# syntax=docker/dockerfile:1
# Billit 백엔드 실행 이미지. 배포 서버는 linux/arm64이며 공통 빌드 워크플로가 ARM64 러너에서 빌드한다.
# - 배포 스크립트 요구사항: 컨테이너 HEALTHCHECK, 앱 포트 8080의 /healthz·/readyz, 비root 실행
# - UID 65532: 서버의 비밀값 파일이 이 UID 소유다. 바꾸면 서버 설정도 함께 바꿔야 한다.
# - 베이스 이미지는 태그와 digest를 함께 고정한다(2026-11-09 기준). 올릴 때 둘 다 바꾼다.

FROM --platform=$BUILDPLATFORM eclipse-temurin:21-jdk@sha256:ebfbb18d11ff163ee83b21258acf12248af48d22a0fdfdc68fa1a1516c05976a AS build
WORKDIR /src
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
RUN --mount=type=cache,target=/root/.gradle ./gradlew --no-daemon dependencies > /dev/null
COPY src src
# 테스트는 PR 검사에서 실행한다. 이미지 빌드에서는 실행용 JAR만 만든다.
RUN --mount=type=cache,target=/root/.gradle ./gradlew --no-daemon bootJar \
 && find build/libs -name '*.jar' ! -name '*-plain.jar' -exec cp {} /app.jar \;

FROM eclipse-temurin:21-jre@sha256:0b99b3376fbb50dbf9c0af8535fdd5b8eb0d3fdfc8b1e4967f383b6919f6901a
ARG VERSION=dev
LABEL org.opencontainers.image.revision=$VERSION
RUN groupadd --system --gid 65532 app \
 && useradd --system --uid 65532 --gid app --home-dir /app --shell /usr/sbin/nologin app
WORKDIR /app
COPY --from=build --chown=65532:65532 /app.jar /app/app.jar
USER 65532:65532
EXPOSE 8080 8081
# 프로세스 생존만 확인한다. DB·Redis 연결은 배포 스크립트가 /readyz로 확인한다.
HEALTHCHECK --interval=10s --timeout=3s --start-period=120s --retries=3 \
  CMD curl -fsS -m 2 http://127.0.0.1:8080/healthz > /dev/null || exit 1
# 힙 상한은 서버 Compose의 JAVA_TOOL_OPTIONS가 컨테이너 메모리 상한 기준으로 정한다.
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
