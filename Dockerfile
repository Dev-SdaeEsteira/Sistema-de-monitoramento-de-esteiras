# =========================================================================
# SDAE — Dockerfile Multi-Stage (Java 21 + Spring Boot)
# Pronto para deploy em Render, Railway, Koyeb ou Docker local.
# =========================================================================

# Estágio 1: Build do JAR com Maven + JDK 21
FROM maven:3.9.9-eclipse-temurin-21-alpine AS builder
WORKDIR /app

# Baixa dependências primeiro para aproveitar cache de camadas
COPY pom.xml .
RUN mvn dependency:go-offline -B

# Compila e empacota o JAR executável
COPY src ./src
RUN mvn package -DskipTests -B

# Estágio 2: Runtime leve (JRE 21 Alpine) otimizado para planos gratuitos (512 MB RAM)
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Usuário não-root por segurança
RUN addgroup -S sdae && adduser -S sdae -G sdae

COPY --from=builder /app/target/*.jar /app/app.jar
RUN chown -R sdae:sdae /app
USER sdae

# Limita uso de memória da JVM para evitar OOM em containers de 512 MB (ex.: Render Free)
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+UseSerialGC -Djava.security.egd=file:/dev/./urandom"
ENV PORT=8080

EXPOSE 8080

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
