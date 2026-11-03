
FROM eclipse-temurin:21-jdk

WORKDIR /app

COPY DigitalGramPanchayat.java .

RUN javac DigitalGramPanchayat.java

EXPOSE 8080

CMD ["java", "DigitalGramPanchayat"]
