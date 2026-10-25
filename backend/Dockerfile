FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B -DskipTests package

FROM eclipse-temurin:17-jre
RUN useradd -r -u 1001 app
USER app
COPY --from=build /src/target/staff-coord.jar /app.jar
ENTRYPOINT ["java","-jar","/app.jar"]
