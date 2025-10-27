# === builder ===
FROM mirror.gcr.io/library/maven:3.9-eclipse-temurin-21 AS builder
WORKDIR /app

COPY pom.xml .
RUN mvn -q -DskipTests dependency:go-offline

COPY src ./src
RUN mvn -q -DskipTests package

# === runtime ===
FROM mirror.gcr.io/library/eclipse-temurin:21-jre
WORKDIR /app

# Fonty + fontconfig (polskie znaki w PDF)
RUN apt-get update && apt-get install -y --no-install-recommends \
      fonts-dejavu fontconfig \
  && rm -rf /var/lib/apt/lists/*

COPY --from=builder /app/target/*.jar app.jar
ENV JAVA_OPTS=""

EXPOSE 8080
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
