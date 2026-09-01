FROM maven:3.9.9-eclipse-temurin-21 AS builder

WORKDIR /build

COPY service-java/pom.xml service-java/pom.xml
RUN mvn -f service-java/pom.xml -DskipTests dependency:go-offline

COPY service-java/src service-java/src
# RUN mvn -f service-java/pom.xml -DskipTests package
RUN mvn -f service-java/pom.xml -Dmaven.test.skip=true package

FROM eclipse-temurin:21-jre-jammy

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

ENV TZ=Asia/Shanghai

COPY --from=builder /build/service-java/target/service-java-0.0.1-SNAPSHOT.jar /app/app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
