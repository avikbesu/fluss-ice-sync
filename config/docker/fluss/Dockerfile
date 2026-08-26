# apache/fluss's coordinator-server/tablet-server already bundle
# fluss-lake-iceberg in /opt/fluss/plugins/iceberg/, but Fluss isolates
# each plugins/<name>/ directory into its own classloader (no cross-plugin
# visibility, confirmed by hitting ClassNotFoundException:
# org.apache.hadoop.conf.Configurable at coordinator startup with
# datalake.format: iceberg configured) — Hadoop classes need to be
# co-located inside plugins/iceberg/ itself, not just plugins/hdfs/
# (where Fluss's own fs-hdfs plugin keeps them).

# Builder stage exists only to package core-site.xml into a jar (see
# below for why) — apache/fluss's own runtime image has a JRE, not a JDK,
# so no `jar` tool is available in the final stage.
FROM eclipse-temurin:21-jdk AS conf-jar-build
RUN mkdir -p /build && printf '%s\n' \
      '<?xml version="1.0"?>' \
      '<configuration>' \
      '  <property>' \
      '    <name>fs.permissions.umask-mode</name>' \
      '    <value>000</value>' \
      '  </property>' \
      '</configuration>' > /build/core-site.xml \
    && jar cf /build/hadoop-conf-override.jar -C /build core-site.xml

FROM apache/fluss:0.9.1-incubating

ARG HADOOP_VERSION=3.4.0

# Hadoop's LocalFileSystem applies its own software-level umask
# (fs.permissions.umask-mode, default 022) when creating directories,
# independent of the OS umask and of any POSIX ACL on the parent volume —
# this silently downgrades new dirs to rwxr-xr-x, breaking writes from any
# container whose UID isn't the directory's owner (confirmed directly: a
# write failed with "Mkdirs failed" even with a working default ACL
# granting rwx to everyone, because the new directory's own mode bits, not
# the ACL, ended up governing access for non-owning UIDs).
#
# This has to be packaged inside a jar, not dropped as a loose XML file —
# Fluss's DirectoryBasedPluginFinder only adds `**.jar` files in a plugin
# directory to that plugin's classloader (confirmed by decompiling it: it
# globs strictly for .jar), so a loose core-site.xml sitting in
# plugins/iceberg/ is silently invisible to that classloader and never
# picked up (confirmed directly: the loose-file version had zero effect).
COPY --from=conf-jar-build /build/hadoop-conf-override.jar /opt/fluss/plugins/iceberg/hadoop-conf-override.jar

RUN curl -fSL -o /opt/fluss/plugins/iceberg/hadoop-client-api-${HADOOP_VERSION}.jar \
      "https://repo.maven.apache.org/maven2/org/apache/hadoop/hadoop-client-api/${HADOOP_VERSION}/hadoop-client-api-${HADOOP_VERSION}.jar" \
    && curl -fSL -o /opt/fluss/plugins/iceberg/hadoop-client-runtime-${HADOOP_VERSION}.jar \
      "https://repo.maven.apache.org/maven2/org/apache/hadoop/hadoop-client-runtime/${HADOOP_VERSION}/hadoop-client-runtime-${HADOOP_VERSION}.jar" \
    && curl -fSL -o /opt/fluss/plugins/iceberg/postgresql-42.7.4.jar \
      "https://repo.maven.apache.org/maven2/org/postgresql/postgresql/42.7.4/postgresql-42.7.4.jar"
