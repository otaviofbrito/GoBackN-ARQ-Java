FROM openjdk:17-jdk-slim

WORKDIR /app

COPY . .

RUN javac -d ./class src/main/java/com/protocol/*.java

CMD ["sh", "-c", "while :; do sleep 2073600; done"]