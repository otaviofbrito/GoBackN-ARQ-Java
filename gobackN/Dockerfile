# Use uma imagem base do Java
FROM openjdk:17-jdk-slim

# Defina o diretório de trabalho dentro do contêiner
WORKDIR /app

# Copie o código fonte para o diretório de trabalho
COPY src/ ./src

RUN javac -d ./class src/main/java/com/redes/*.java

# Comando para manter o contêiner rodando em loop
CMD ["sh", "-c", "while :; do sleep 2073600; done"]
