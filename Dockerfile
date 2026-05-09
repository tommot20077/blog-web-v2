FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY blog-start/target/blog-start-1.0.jar app.jar
EXPOSE 9010
ENTRYPOINT ["java", "-jar", "app.jar"]
