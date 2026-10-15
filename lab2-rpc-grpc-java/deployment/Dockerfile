FROM maven:3-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
COPY contracts ./contracts
RUN mvn -q -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/target/lab2-rpc-grpc-1.0.0-all.jar /app/lab2.jar
ENTRYPOINT ["java", "-cp", "/app/lab2.jar", "md.lab2.server.BrokerMain"]
