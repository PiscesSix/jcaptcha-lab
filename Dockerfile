# Build
FROM eclipse-temurin:21-jdk AS build
RUN apt-get update && apt-get install -y --no-install-recommends git && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY . .
RUN ./build.sh

# Run
FROM eclipse-temurin:21-jre
RUN apt-get update && apt-get install -y --no-install-recommends fontconfig fonts-dejavu-core && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /app/out ./out
ENV PORT=8080 DEBUG=0 RATE_LIMIT=60 TRUST_PROXY=0
EXPOSE 8080
CMD ["java", "-Djava.awt.headless=true", "-cp", "out", "demo.Server"]
