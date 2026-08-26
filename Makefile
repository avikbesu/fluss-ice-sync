# Single source of truth for the Iceberg JDBC catalog connection (Postgres
# in config/docker/docker-compose.infra.yml's iceberg-catalog-db service),
# otherwise duplicated across the Fluss servers' datalake.iceberg.* config,
# this file's lakehouse-submit recipe, and Trino's iceberg.properties
# catalog. Exported so `${VAR}` substitution in the compose files and
# Trino's `${ENV:VAR}` substitution in its properties files both pick these
# up from the same place.
ICEBERG_CATALOG_NAME = fluss-iceberg-catalog
ICEBERG_CATALOG_DB = iceberg_catalog
ICEBERG_CATALOG_USER = iceberg
ICEBERG_CATALOG_PASSWORD = iceberg
ICEBERG_CATALOG_URI = jdbc:postgresql://iceberg-catalog-db:5432/$(ICEBERG_CATALOG_DB)
ICEBERG_WAREHOUSE = file:///lakehouse/warehouse
export ICEBERG_CATALOG_NAME ICEBERG_CATALOG_DB ICEBERG_CATALOG_USER ICEBERG_CATALOG_PASSWORD ICEBERG_CATALOG_URI ICEBERG_WAREHOUSE

# Must match config/docker/flink/Dockerfile's ARG FLUSS_VERSION — the jar
# lakehouse-submit points at is only present in the image if the two agree.
FLUSS_VERSION = 0.9.1-incubating

COMPOSE = docker compose \
	-f config/docker/docker-compose.infra.yml \
	-f config/docker/docker-compose.app.yml \
	-f config/docker/docker-compose.monitoring.yml \
	-f config/docker/docker-compose.lakehouse.yml \
	-f config/docker/docker-compose.trino.yml

.PHONY: build test run infra-up up down logs clean lakehouse-up lakehouse-submit trino-shell

build:
	./gradlew :app:sync:build

test:
	./gradlew :app:sync:test

run:
	./gradlew :app:sync:run

infra-up:
	docker compose -f config/docker/docker-compose.infra.yml up -d

up:
	$(COMPOSE) up -d --build

down:
	$(COMPOSE) down

logs:
	$(COMPOSE) logs -f fluss-ice-sync

clean:
	./gradlew clean

lakehouse-up:
	docker compose \
		-f config/docker/docker-compose.infra.yml \
		-f config/docker/docker-compose.lakehouse.yml \
		up -d --build

lakehouse-submit:
	$(COMPOSE) exec flink-jobmanager /opt/flink/bin/flink run \
		/opt/flink/lib/fluss-flink-tiering-$(FLUSS_VERSION).jar \
		--fluss.bootstrap.servers tablet-server:9123 \
		--datalake.format iceberg \
		--datalake.iceberg.type jdbc \
		--datalake.iceberg.uri $(ICEBERG_CATALOG_URI) \
		--datalake.iceberg.jdbc.user $(ICEBERG_CATALOG_USER) \
		--datalake.iceberg.jdbc.password $(ICEBERG_CATALOG_PASSWORD) \
		--datalake.iceberg.warehouse $(ICEBERG_WAREHOUSE)

trino-shell:
	$(COMPOSE) exec trino-coordinator trino --user sales-read-role
