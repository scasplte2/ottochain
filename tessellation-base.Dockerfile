# tessellation-base.Dockerfile
#
# Packages pre-built Tessellation GL0 and GL1 JARs into a minimal image.
# JARs are built by build-tessellation-base.yml BEFORE docker build is called.
#
# Usage in CI:
#   docker create --name tess-base ghcr.io/ottobot-ai/tessellation-base:v{VERSION}
#   docker cp tess-base:/jars/gl0/. tessellation/modules/dag-l0/target/scala-2.13/
#   docker cp tess-base:/jars/gl1/. tessellation/modules/dag-l1/target/scala-2.13/
#   docker rm tess-base
#   SKIP_ASSEMBLY=true PUBLISH=false just up --metagraph=...
#
# NOTE: ml0 / cl1 / dl1 (OttoChain metagraph JARs) are intentionally excluded —
#       they change every PR and are always built from source.

FROM alpine:3.19

RUN mkdir -p /jars/gl0 /jars/gl1

# JARs are placed in the docker build context by build-tessellation-base.yml:
#   jars/gl0/tessellation-dag-l0-assembly-{VERSION}.jar
#   jars/gl1/tessellation-dag-l1-assembly-{VERSION}.jar

COPY jars/gl0/*.jar /jars/gl0/
COPY jars/gl1/*.jar /jars/gl1/
