# Use a lightweight Java image
FROM maven:3.8.5-openjdk-11-slim AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
# Create the fat-jar (executable)
RUN mvn clean package shade:shade

# Final Runtime Image
FROM openjdk:11-jre-slim
WORKDIR /app
COPY --from=build /app/target/pr-validator-1.0-SNAPSHOT.jar app.jar
CMD ["java", "-jar", "app.jar"]