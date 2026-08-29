# Phase 16: multi-stage build - the build stage (full Maven + JDK, ~600MB+)
# never ships; only its output jar crosses into the runtime stage, which
# starts from a minimal JRE (not a full JDK - this application never
# compiles anything at runtime) on a distroless-style Alpine base.

# --- build stage ---
FROM maven:3.9-eclipse-temurin-17-alpine AS build
WORKDIR /build

# Dependencies are resolved into a layer BEFORE the source is copied in, so
# an ordinary code change (which touches src/ but not pom.xml) doesn't
# invalidate Docker's cache for the (slow) dependency-download layer.
COPY pom.xml .
RUN mvn -q -B dependency:go-offline

COPY src src
RUN mvn -q -B clean package -DskipTests

# --- runtime stage ---
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

# Runs as a non-root, unprivileged user - a container escape or a
# dependency RCE lands as an account with no meaningful permissions on the
# host or the rest of the filesystem, rather than as root.
RUN addgroup -S app && adduser -S app -G app
USER app

COPY --from=build /build/target/ai-platform-*.jar app.jar

# Docker's own HEALTHCHECK asks the LIVENESS probe, not the aggregate health
# endpoint - and the distinction is the point of splitting them.
#
# Docker restarts an unhealthy container. The aggregate endpoint reports DOWN
# when any dependency is degraded, so wiring a restart to it means a Redis blip
# kills an instance that was serving requests fine, at the moment the deployment
# could least afford to lose one. Liveness answers the question a restarter
# should be asking: is this process broken beyond recovery?
#
# Readiness (/actuator/health/readiness) is the one a load balancer or
# orchestrator should route on - it goes down for a degraded dependency, which
# stops traffic without killing anything.
HEALTHCHECK --interval=15s --timeout=5s --start-period=40s --retries=3 \
    CMD wget -q -O- http://localhost:8080/actuator/health/liveness | grep -q '"status":"UP"' || exit 1

EXPOSE 8080

# No profile/environment baked in here - every environment-specific value
# (DB_URL, REDIS_HOST, OPENAI_API_KEY, ...) is supplied at run time via
# environment variables (see docker-compose.yml and application.yml's
# ${VAR:default} placeholders), never baked into the image - the same image
# is what gets promoted from staging to production, unchanged.
ENTRYPOINT ["java", "-jar", "app.jar"]
