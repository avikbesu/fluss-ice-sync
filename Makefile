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

# docker-compose.lakehouse.yml and docker-compose.trino.yml were folded
# into docker-compose.infra.yml (Flink tiering + Trino have no
# independent-startup use case distinct from the rest of infra -- see that
# file). Note this does widen `infra-up` below: it now also brings up
# Flink and Trino, not just Fluss/ZooKeeper/the Iceberg catalog DB as
# before the merge.
COMPOSE = docker compose \
	-f config/docker/docker-compose.infra.yml \
	-f config/docker/docker-compose.app.yml \
	-f config/docker/docker-compose.monitoring.yml

.PHONY: build test run infra-up up down logs clean reset lakehouse-up lakehouse-submit trino-shell ui-logs ui-test nl-api-logs nl-api-test config-logs config-test seed-banking

default: up

build:
	./gradlew :app:sync:build

test:
	./gradlew :app:sync:test

run:
	./gradlew :app:sync:run

infra-up:
	docker compose -f config/docker/docker-compose.infra.yml up -d

up: reset
	$(COMPOSE) up -d --build

down:
	$(COMPOSE) down

logs:
	$(COMPOSE) logs -f flino

clean: ## Clean gradle build for all modules
	./gradlew clean

reset: clean ## Full teardown: containers, volumes, locally-built images, and Gradle build output (all profiles, including Trino)
	$(COMPOSE) down --rmi local --volumes --remove-orphans

lakehouse-up:
	docker compose -f config/docker/docker-compose.infra.yml \
		up -d --build flink-jobmanager flink-taskmanager

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

ui-logs:
	$(COMPOSE) logs -f flino-ui

ui-test:
	cd app/ui/web && npm test && cd ../bff && npm test

nl-api-logs:
	$(COMPOSE) logs -f flino-nl-api

nl-api-test: ## Unit tests only (no Docker needed) -- see app/nl-api/README.md for integrationTest, which does
	./gradlew :app:nl-api:test

config-logs:
	$(COMPOSE) logs -f flino-config

config-test:
	./gradlew :app:config:test

seed-banking: ## Generate a fresh, relationally-intact sample banking dataset as CSVs into data/banking/ (see config/data-caterer/plan/banking.yaml)
	rm -rf data/banking
	mkdir -p data/banking
	# The data-caterer image writes as uid 1001, not the host user -- see
	# config/docker/docker-compose.seed.yaml.
	chmod 777 data/banking
	docker compose -f config/docker/docker-compose.seed.yaml run --rm data-caterer
	# Data Caterer can't make account_contracts.contract_role loan-aware
	# itself (a field's `sql` can't see another field's real foreignKeys
	# value -- see bug 5 in banking.yaml's file-level comment), so fix up
	# here: Power of Attorney -> Guarantor wherever the contract's parent
	# account is a Loan.
	awk -F, ' \
		NR==FNR { \
			if (FNR==1) { for (i=1;i<=NF;i++) h[$$i]=i; next } \
			type[$$(h["id"])] = $$(h["account_type"]); next \
		} \
		FNR==1 { for (i=1;i<=NF;i++) ch[$$i]=i; print; next } \
		{ \
			if (type[$$(ch["accounts_key"])] == "Loan" && $$(ch["contract_role"]) == "Power of Attorney") { \
				$$(ch["contract_role"]) = "Guarantor" \
			} \
			print \
		} \
	' OFS=, data/banking/accounts.csv data/banking/account_contracts.csv > data/banking/account_contracts.csv.tmp
	mv data/banking/account_contracts.csv.tmp data/banking/account_contracts.csv
