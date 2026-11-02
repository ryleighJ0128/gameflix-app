FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY target/module4-jwt-service-1.0.0.jar app.jar
RUN addgroup -S gameflix && adduser -S gameflix -G gameflix
USER gameflix
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
