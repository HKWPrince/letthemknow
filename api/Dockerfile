# ---- build stage ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -q -B -e dependency:go-offline
COPY src ./src
RUN mvn -q -B -DskipTests package

# ---- runtime stage ----
FROM eclipse-temurin:21-jre
RUN groupadd --system ltk && useradd --system --gid ltk --no-create-home ltk
WORKDIR /app
COPY --from=build /build/target/*.jar /app/app.jar
# Startup-tuned for a 1 GB e2-micro (a quarter of a shared vCPU). Measured there with the defaults:
# 8m32s from "Starting" to "Started", with 279 MB swapped. C1-only compilation and the serial
# collector remove the C2 compiler threads and the parallel GC workers, which is most of the CPU
# and non-heap memory a JVM burns during boot; peak throughput is irrelevant at this scale.
ENV TZ=UTC \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:TieredStopAtLevel=1 -XX:+UseSerialGC -Xss512k -Djava.security.egd=file:/dev/./urandom"
USER ltk
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
