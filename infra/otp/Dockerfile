FROM docker.io/opentripplanner/opentripplanner:2.5.0

USER root
RUN apt-get update \
  && apt-get install -y --no-install-recommends zip curl \
  && rm -rf /var/lib/apt/lists/*

COPY build-config.json router-config.json otp-config.json /otp-config-src/
COPY entrypoint.sh /docker-entrypoint.sh
RUN chmod +x /docker-entrypoint.sh \
  && chmod a+r /otp-config-src/*.json

ENV JAVA_TOOL_OPTIONS="-Xmx2g"
EXPOSE 8080
ENTRYPOINT ["/docker-entrypoint.sh"]
