FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
COPY src src
COPY deployment/application.properties deployment/application.properties
RUN mvn -B -DskipTests package

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/target/classes /app/classes
COPY --from=build /app/target/dependency /app/lib
COPY deployment/application.properties deployment/application.properties
ENTRYPOINT ["java", "-Dstorage.directory=/data", "-cp", "classes:lib/*"]
