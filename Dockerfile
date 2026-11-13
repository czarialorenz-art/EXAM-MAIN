# Web version of the Quiz & Examination Management System.
#   docker build -t quiz-web .
#   docker run -p 8080:8080 -v quizdata:/app/data quiz-web --demo
# Mount /app/data on a persistent volume, otherwise all records are lost when the container restarts.
FROM eclipse-temurin:17-jdk AS build
WORKDIR /src
COPY src ./src
RUN find src -name '*.java' > sources.txt && javac --release 17 -encoding UTF-8 -d out @sources.txt

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /src/out ./out
COPY webroot ./webroot
RUN mkdir -p data
VOLUME /app/data
ENV PORT=8080
EXPOSE 8080
ENTRYPOINT ["java", "-cp", "out", "web.WebMain", "--data", "data", "--webroot", "webroot"]
