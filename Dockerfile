FROM eclipse-temurin:21-jdk-jammy@sha256:4b1a74fbffbe6b4e7b6f0171a472478282fb463790a51edf10e465d578f22b69 AS build
WORKDIR /build
COPY servibox-backend/.mvn/ .mvn/
COPY servibox-backend/mvnw servibox-backend/pom.xml ./
RUN sed -i 's/\r$//' mvnw && chmod +x mvnw
COPY servibox-backend/src/ src/
RUN ./mvnw -B -DskipTests package

FROM eclipse-temurin:21-jre-jammy@sha256:1eac8b8fee11ca7d6725a48bc5d41992ed1561504ebae25490d2d062847e8269
WORKDIR /app
RUN groupadd --gid 10001 servibox && useradd --uid 10001 --gid servibox --no-create-home servibox
COPY --from=build --chown=10001:10001 /build/target/servibox-backend-0.0.1-SNAPSHOT.jar app.jar
USER 10001:10001
ENV SPRING_PROFILES_ACTIVE=prod
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
