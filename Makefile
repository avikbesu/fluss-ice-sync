COMPOSE = docker compose \
	-f config/docker/docker-compose.infra.yml \
	-f config/docker/docker-compose.app.yml \
	-f config/docker/docker-compose.monitoring.yml

.PHONY: build test run infra-up up down logs clean

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
	$(COMPOSE) logs -f fluss-sync

clean:
	./gradlew clean
