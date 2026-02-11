# OttoChain Full Stack - Multi-stage Docker Build
#
# Builds ALL 5 layers: GL0, GL1, ML0, CL1, DL1
# Single image ensures tessellation/metakit/metagraph compatibility.
#
# Build order (dependency chain):
#   1. Tessellation (patched) → sdk/publishLocal
#   2. Metakit (uses local tessellation) → publishLocal
#   3. OttoChain (uses local metakit + tessellation) → assembly
#
# Layers: gl0 (9000), gl1 (9100), ml0 (9200), cl1 (9300), dl1 (9400)

ARG TESSELLATION_VERSION=v4.0.0-rc.2
ARG METAKIT_VERSION=main
ARG OTTOCHAIN_VERSION=0.0.0-docker

# ============ Stage 1: Build Tessellation ============
FROM eclipse-temurin:21-jdk AS tess-builder

ARG TESSELLATION_VERSION

RUN apt-get update && apt-get install -y curl gnupg git && \
    echo "deb https://repo.scala-sbt.org/scalasbt/debian all main" > /etc/apt/sources.list.d/sbt.list && \
    curl -sL "https://keyserver.ubuntu.com/pks/lookup?op=get&search=0x2EE0EA64E40A89B84B2DF73499E82A75642AC823" | apt-key add && \
    apt-get update && apt-get install -y sbt

WORKDIR /tessellation

# Clone tessellation
RUN git clone --depth 1 --branch ${TESSELLATION_VERSION} https://github.com/Constellation-Labs/tessellation.git .

# Apply patches (type inference fix) - temporary until fixed upstream
# See: https://github.com/Constellation-Labs/tessellation/issues/XXX
RUN FILE="modules/node-shared/src/main/scala/org/tessellation/node/shared/infrastructure/snapshot/GlobalSnapshotStateChannelEventsProcessor.scala" && \
    if [ -f "$FILE" ] && grep -q "def make\[F\[_\]\]" "$FILE"; then \
      echo "Applying type inference patch to GlobalSnapshotStateChannelEventsProcessor..." && \
      sed -i 's/def make\[F\[_\]\]/def make[F[_]]: GlobalSnapshotStateChannelEventsProcessor[F]/g' "$FILE" || exit 1; \
    else \
      echo "WARN: Patch target not found in $FILE - may have been fixed upstream in ${TESSELLATION_VERSION}"; \
    fi

# Build and publish tessellation SDK locally + build JARs
RUN sbt sdk/publishLocal dagL0/assembly dagL1/assembly

# ============ Stage 2: Build Metakit ============
FROM eclipse-temurin:21-jdk AS metakit-builder

ARG METAKIT_VERSION

RUN apt-get update && apt-get install -y curl gnupg git && \
    echo "deb https://repo.scala-sbt.org/scalasbt/debian all main" > /etc/apt/sources.list.d/sbt.list && \
    curl -sL "https://keyserver.ubuntu.com/pks/lookup?op=get&search=0x2EE0EA64E40A89B84B2DF73499E82A75642AC823" | apt-key add && \
    apt-get update && apt-get install -y sbt

# Copy tessellation SDK from previous stage (only ivy2 - .sbt cache can cause conflicts)
COPY --from=tess-builder /root/.ivy2 /root/.ivy2

WORKDIR /metakit

# Clone metakit
RUN git clone --depth 1 --branch ${METAKIT_VERSION} https://github.com/Constellation-Labs/metakit.git .

# Build and publish metakit locally (uses local tessellation SDK)
RUN sbt publishLocal

# ============ Stage 3: Build OttoChain ============
FROM eclipse-temurin:21-jdk AS otto-builder

ARG OTTOCHAIN_VERSION

RUN apt-get update && apt-get install -y curl gnupg && \
    echo "deb https://repo.scala-sbt.org/scalasbt/debian all main" > /etc/apt/sources.list.d/sbt.list && \
    curl -sL "https://keyserver.ubuntu.com/pks/lookup?op=get&search=0x2EE0EA64E40A89B84B2DF73499E82A75642AC823" | apt-key add && \
    apt-get update && apt-get install -y sbt

WORKDIR /ottochain

# Copy ivy cache with tessellation SDK + metakit (only ivy2 - .sbt cache can cause conflicts)
COPY --from=metakit-builder /root/.ivy2 /root/.ivy2

# Copy build files first (better caching)
COPY build.sbt .
COPY project/ project/

# Download dependencies
RUN sbt update

# Copy source and build metagraph JARs (ML0, CL1, DL1)
COPY . .
# Override sbt-dynver with explicit version (no .git in Docker context)
# Write version.sbt to override dynver's automatic version detection
RUN echo "ThisBuild / version := \"${OTTOCHAIN_VERSION}\"" > version.sbt && \
    cat version.sbt && \
    sbt "currencyL0/assembly" "currencyL1/assembly" "dataL1/assembly"

# ============ Stage 4: Runtime ============
FROM eclipse-temurin:21-jre

RUN apt-get update && apt-get install -y curl jq && rm -rf /var/lib/apt/lists/*

WORKDIR /ottochain

# Create directories
RUN mkdir -p /ottochain/jars /ottochain/data /ottochain/keys /ottochain/genesis

# Copy JAR directories from build stages (avoid glob issues with multiple matches)
COPY --from=tess-builder /tessellation/modules/dag-l0/target/scala-2.13/ /tmp/tess-gl0/
COPY --from=tess-builder /tessellation/modules/dag-l1/target/scala-2.13/ /tmp/tess-gl1/
COPY --from=otto-builder /ottochain/modules/l0/target/scala-2.13/ /tmp/otto-ml0/
COPY --from=otto-builder /ottochain/modules/l1/target/scala-2.13/ /tmp/otto-cl1/
COPY --from=otto-builder /ottochain/modules/data_l1/target/scala-2.13/ /tmp/otto-dl1/

# Extract exactly one assembly JAR per layer (fail if multiple found)
RUN set -e && \
    for layer in gl0:tess-gl0:tessellation-dag-l0-assembly gl1:tess-gl1:tessellation-dag-l1-assembly \
                 ml0:otto-ml0:ottochain-currency-l0-assembly cl1:otto-cl1:ottochain-currency-l1-assembly \
                 dl1:otto-dl1:ottochain-data-l1-assembly; do \
      NAME="${layer%%:*}"; REST="${layer#*:}"; DIR="${REST%%:*}"; PATTERN="${REST#*:}"; \
      JAR=$(find /tmp/$DIR -maxdepth 1 -name "${PATTERN}-*.jar" -type f | head -1); \
      if [ -z "$JAR" ]; then echo "ERROR: No JAR found for $NAME"; exit 1; fi; \
      COUNT=$(find /tmp/$DIR -maxdepth 1 -name "${PATTERN}-*.jar" -type f | wc -l); \
      if [ "$COUNT" -gt 1 ]; then echo "WARN: Multiple JARs for $NAME, using: $JAR"; fi; \
      cp "$JAR" /ottochain/jars/${NAME}.jar; \
      echo "✓ $NAME: $(basename $JAR)"; \
    done && \
    rm -rf /tmp/tess-* /tmp/otto-*

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
