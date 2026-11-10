FROM eclipse-temurin:17-jdk AS compile
WORKDIR /app
COPY src/main/java/ /app/src/
RUN mkdir -p /app/classes && find src -name '*.java' > /tmp/sources && javac --release 17 -d /app/classes @/tmp/sources
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=compile /app/classes /app/classes
ENV LAB3_BIND_HOST=0.0.0.0
ENTRYPOINT ["java","-cp","/app/classes","md.lab3.Main"]
CMD ["all"]
