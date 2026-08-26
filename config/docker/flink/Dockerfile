# Flink image used only to run Fluss's Lakehouse Tiering Service job (see
# doc/design/v1-trino-integration-design.md). Not a general-purpose Flink
# deployment for other jobs.
#
# Tiers into Iceberg (not Paimon — see the design doc for why: the only
# buildable community Paimon-Trino connector bundles a Paimon reader far
# older than what Fluss's tiering service writes, an unresolved upstream
# version gap). Trino ships a built-in, actively-maintained Iceberg
# connector, so no custom plugin build is needed on the Trino side at all.
FROM flink:1.20-scala_2.12

ARG FLUSS_VERSION=0.9.1-incubating
# Must match Fluss 0.9.1-incubating's own pinned hadoop.version
# (org.apache.fluss:fluss:0.9.1-incubating's pom) — fluss-lake-iceberg
# declares Hadoop as "provided" (needed by its JdbcCatalog/FileIO usage),
# so the tiering job needs it supplied on its classpath itself. Using the
# shaded hadoop-client-api/hadoop-client-runtime pair, not raw
# hadoop-common/hadoop-hdfs-client — hand-picking individual Hadoop jars
# broke Flink's own cluster startup in an earlier (Paimon-path) iteration
# of this Dockerfile; the shaded pair bundles its full transitive closure.
ARG HADOOP_VERSION=3.4.0
# Iceberg's JdbcCatalog (used for the shared catalog metadata store,
# config/docker/docker-compose.infra.yml's iceberg-catalog-db service)
# needs a JDBC driver on the classpath; Trino's iceberg connector already
# bundles the Postgres driver, but the tiering job does not.
ARG POSTGRESQL_DRIVER_VERSION=42.7.4

# Hadoop's LocalFileSystem applies its own software-level umask
# (fs.permissions.umask-mode, default 022) when creating directories,
# independent of the OS umask and of any POSIX ACL on the parent — this
# silently downgrades new dirs to rwxr-xr-x, breaking writes from any
# container whose UID isn't the directory's owner (confirmed directly: a
# write failed with "Mkdirs failed" even after the shared warehouse volume
# had a working default ACL granting rwx to everyone, because the new
# directory's own mode bits, not the ACL, ended up governing access for
# non-owning UIDs). Flink's own config.sh auto-adds /etc/hadoop/conf to the
# classpath if present, so a core-site.xml disabling the umask there is
# picked up with no other wiring needed.
RUN mkdir -p /etc/hadoop/conf && printf '%s\n' \
      '<?xml version="1.0"?>' \
      '<configuration>' \
      '  <property>' \
      '    <name>fs.permissions.umask-mode</name>' \
      '    <value>000</value>' \
      '  </property>' \
      '</configuration>' > /etc/hadoop/conf/core-site.xml

RUN curl -fSL -o /opt/flink/lib/fluss-flink-tiering-${FLUSS_VERSION}.jar \
      "https://repo.maven.apache.org/maven2/org/apache/fluss/fluss-flink-tiering/${FLUSS_VERSION}/fluss-flink-tiering-${FLUSS_VERSION}.jar" \
    && curl -fSL -o /opt/flink/lib/fluss-flink-1.20-${FLUSS_VERSION}.jar \
      "https://repo.maven.apache.org/maven2/org/apache/fluss/fluss-flink-1.20/${FLUSS_VERSION}/fluss-flink-1.20-${FLUSS_VERSION}.jar" \
    && curl -fSL -o /opt/flink/lib/fluss-lake-iceberg-${FLUSS_VERSION}.jar \
      "https://repo.maven.apache.org/maven2/org/apache/fluss/fluss-lake-iceberg/${FLUSS_VERSION}/fluss-lake-iceberg-${FLUSS_VERSION}.jar" \
    && curl -fSL -o /opt/flink/lib/hadoop-client-api-${HADOOP_VERSION}.jar \
      "https://repo.maven.apache.org/maven2/org/apache/hadoop/hadoop-client-api/${HADOOP_VERSION}/hadoop-client-api-${HADOOP_VERSION}.jar" \
    && curl -fSL -o /opt/flink/lib/hadoop-client-runtime-${HADOOP_VERSION}.jar \
      "https://repo.maven.apache.org/maven2/org/apache/hadoop/hadoop-client-runtime/${HADOOP_VERSION}/hadoop-client-runtime-${HADOOP_VERSION}.jar" \
    && curl -fSL -o /opt/flink/lib/postgresql-${POSTGRESQL_DRIVER_VERSION}.jar \
      "https://repo.maven.apache.org/maven2/org/postgresql/postgresql/${POSTGRESQL_DRIVER_VERSION}/postgresql-${POSTGRESQL_DRIVER_VERSION}.jar"
