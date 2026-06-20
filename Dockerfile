# OttoChain Full Stack - Multi-stage Docker Build
#
# Builds ALL 5 layers: GL0, GL1, ML0, CL1, DL1
# Single image ensures tessellation/metakit/metagraph compatibility.
#
# Version resolution (from actual dependency chain):
#   1. Read metakit version from project/Dependencies.scala
#   2. Fetch metakit POM from Maven Central → extract tessellation-sdk version
#   3. Download tessellation release JARs (GL0, GL1)
#   4. Build OttoChain (sbt resolves metakit + tessellation-sdk from Maven Central)
#
# Layers: gl0 (9000), gl1 (9100), ml0 (9200), cl1 (9300), dl1 (9400)

ARG TESSELLATION_VERSION=""
ARG OTTOCHAIN_VERSION=0.0.0-docker

# ============ Stage 1: Resolve versions & download tessellation JARs ============
FROM eclipse-temurin:21-jdk AS tess-jars

ARG TESSELLATION_VERSION

RUN apt-get update && apt-get install -y curl jq && rm -rf /var/lib/apt/lists/*

WORKDIR /resolve

# Copy Dependencies.scala to extract metakit version
COPY project/Dependencies.scala .

RUN set -e && \
    if [ -n "${TESSELLATION_VERSION}" ]; then \
      TESS_VER="${TESSELLATION_VERSION}"; \
      echo "Using override tessellation version: ${TESS_VER}"; \
    else \
      # 1. Extract metakit version from Dependencies.scala
      METAKIT_VER=$(grep 'val metakit' Dependencies.scala | head -1 | sed 's/.*"\(.*\)".*/\1/') && \
      echo "Metakit version from Dependencies.scala: ${METAKIT_VER}" && \
      \
      # 2. Fetch metakit POM from Maven Central → extract tessellation-sdk version
      POM_URL="https://repo1.maven.org/maven2/io/constellationnetwork/metakit_2.13/${METAKIT_VER}/metakit_2.13-${METAKIT_VER}.pom" && \
      echo "Fetching POM: ${POM_URL}" && \
      POM=$(curl -sf "${POM_URL}") && \
      TESS_VER=$(echo "${POM}" | grep -A1 'tessellation-sdk' | grep '<version>' | sed 's/.*<version>\(.*\)<\/version>.*/\1/') && \
      echo "Tessellation version from metakit POM: ${TESS_VER}"; \
    fi && \
    \
    # Ensure v prefix for GitHub release tag
    case "${TESS_VER}" in v*) ;; *) TESS_VER="v${TESS_VER}" ;; esac && \
    echo "${TESS_VER}" > /resolve/tess-version.txt && \
    \
    # 3. Download tessellation release JARs
    mkdir -p /jars && \
    echo "Downloading tessellation ${TESS_VER} release JARs..." && \
    for asset in cl-node.jar cl-dag-l1.jar cl-keytool.jar cl-wallet.jar; do \
      echo "  ${asset}..." && \
      curl -sL -f \
        "https://github.com/Constellation-Labs/tessellation/releases/download/${TESS_VER}/${asset}" \
        -o "/jars/${asset}" || { echo "ERROR: Failed to download ${asset} from ${TESS_VER}"; exit 1; }; \
    done && \
    echo "✓ All tessellation JARs downloaded" && \
    ls -la /jars/

# ============ Stage 2: Build OttoChain ============
FROM eclipse-temurin:21-jdk AS otto-builder

ARG OTTOCHAIN_VERSION

RUN apt-get update && apt-get install -y curl gnupg && \
    echo "deb [signed-by=/usr/share/keyrings/sbt.gpg] https://repo.scala-sbt.org/scalasbt/debian all main" > /etc/apt/sources.list.d/sbt.list && \
    curl -sL "https://keyserver.ubuntu.com/pks/lookup?op=get&search=0x2EE0EA64E40A89B84B2DF73499E82A75642AC823" | gpg --dearmor -o /usr/share/keyrings/sbt.gpg && \
    apt-get update && apt-get install -y sbt && \
    rm -rf /var/lib/apt/lists/*

WORKDIR /ottochain

# Copy build files first (better caching)
COPY build.sbt .
COPY project/ project/

# Download dependencies (metakit + tessellation-sdk resolve from Maven Central)
RUN sbt update

# Copy source and build metagraph JARs (ML0, CL1, DL1)
COPY . .
# Override sbt-dynver with explicit version (no .git in Docker context)
RUN echo "ThisBuild / version := \"${OTTOCHAIN_VERSION}\"" > version.sbt && \
    cat version.sbt && \
    sbt "currencyL0/assembly" "currencyL1/assembly" "dataL1/assembly"

# ============ Stage 3: Runtime ============
FROM eclipse-temurin:21-jre

RUN apt-get update && apt-get install -y curl jq && rm -rf /var/lib/apt/lists/*

WORKDIR /ottochain

# Create directories
RUN mkdir -p /ottochain/jars /ottochain/data /ottochain/keys /ottochain/genesis

# Copy tessellation release JARs
COPY --from=tess-jars /jars/cl-node.jar /ottochain/jars/gl0.jar
COPY --from=tess-jars /jars/cl-dag-l1.jar /ottochain/jars/gl1.jar

# Copy metagraph assembly JARs
COPY --from=otto-builder /ottochain/modules/l0/target/scala-2.13/ /tmp/otto-ml0/
COPY --from=otto-builder /ottochain/modules/l1/target/scala-2.13/ /tmp/otto-cl1/
COPY --from=otto-builder /ottochain/modules/data_l1/target/scala-2.13/ /tmp/otto-dl1/

# Extract exactly one assembly JAR per metagraph layer
RUN set -e && \
    for layer in ml0:otto-ml0:ottochain-currency-l0-assembly cl1:otto-cl1:ottochain-currency-l1-assembly \
                 dl1:otto-dl1:ottochain-data-l1-assembly; do \
      NAME="${layer%%:*}"; REST="${layer#*:}"; DIR="${REST%%:*}"; PATTERN="${REST#*:}"; \
      JAR=$(find /tmp/$DIR -maxdepth 1 -name "${PATTERN}-*.jar" -type f | head -1); \
      if [ -z "$JAR" ]; then echo "ERROR: No JAR found for $NAME"; exit 1; fi; \
      cp "$JAR" /ottochain/jars/${NAME}.jar; \
      echo "✓ $NAME: $(basename $JAR)"; \
    done && \
    rm -rf /tmp/otto-*

# Entrypoint script
COPY docker-entrypoint.sh /ottochain/entrypoint.sh
RUN chmod +x /ottochain/entrypoint.sh

# Verify all JARs exist
RUN ls -la /ottochain/jars/ && \
    test -f /ottochain/jars/gl0.jar && \
    test -f /ottochain/jars/gl1.jar && \
    test -f /ottochain/jars/ml0.jar && \
    test -f /ottochain/jars/cl1.jar && \
    test -f /ottochain/jars/dl1.jar

# Environment defaults (CL_PASSWORD intentionally omitted - must be provided at runtime)
ENV LAYER=ml0
ENV CL_KEYSTORE=/ottochain/keys/key.p12
ENV CL_KEYALIAS=alias
ENV CL_APP_ENV=testnet
ENV JAVA_OPTS="-Xmx4g -Xms2g"

# Ports: GL0=9000, GL1=9100, ML0=9200, CL1=9300, DL1=9400
EXPOSE 9000 9100 9200 9300 9400

ENTRYPOINT ["/ottochain/entrypoint.sh"]
