FROM gradle:8.14.3-jdk21 AS build
WORKDIR /app
COPY . .
RUN gradle --no-daemon clean installDist

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/build/install/speaking-character-backend/ ./
ENV PORT=8080
EXPOSE 8080
CMD ["bin/speaking-character-backend"]
