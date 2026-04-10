FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

COPY acmq-prov-backend/target/mq-provisioning-service.jar app.jar

# UseContainerSupport gör att JVM respekterar Kubernetes memory limits.
# MaxRAMPercentage=75 innebär att heap får använda max 75% av containerns tillåtna RAM.
# InitialRAMPercentage=50 ger en rimlig startpunkt utan att reservera för mycket direkt.
ENV JAVA_OPTS="-XX:+UseContainerSupport \
               -XX:MaxRAMPercentage=75.0 \
               -XX:InitialRAMPercentage=50.0 \
               -XX:+ExitOnOutOfMemoryError \
               -Djdk.attach.allowAttachSelf=true"

EXPOSE 8080

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
