# Build and run the chat server on this machine, without Docker.
# Settings come from .env (make env copies .env.example; then edit it); a
# variable set in the shell wins over the file.
#
#   make env       create .env from .env.example (never overwrites)
#   make build     build bin/appserver
#   make run       start it in the background (log: server/run/appserver.log)
#   make kill      stop it
#   make restart   kill, then run
#   make logs      follow the log
#   make clean     remove the binary and the log (server must be stopped)
#
# Database (PostgreSQL 18 in Docker, on 127.0.0.1 only; data kept in a volume):
#   make db        start it; user and database "appserver", password POSTGRES_PASSWORD from .env
#   make db-stop   stop it (data is kept)
#   make db-shell  open psql
#   Then in .env: APP_DATABASE_URL=postgres://appserver:PASSWORD@127.0.0.1:5432/appserver?sslmode=disable
#   The port comes from APP_DATABASE_URL (or make db DB_PORT=5433). The server creates its tables itself on start.
#
# Listen address: APP_LISTEN in .env, or for one run:
#   make run BIND=0.0.0.0 PORT=8080   every network: other machines can connect
#   make run BIND=127.0.0.1           this machine only

.DEFAULT_GOAL := build
ENV_FILE ?= .env
BIN      := bin/appserver
RUN_DIR  := server/run
PID      := $(RUN_DIR)/appserver.pid
LOG      := $(RUN_DIR)/appserver.log

# Port for make db: DB_PORT=..., else the port in APP_DATABASE_URL (127.0.0.1 only), else 5432.
DB_PORT      ?= $(or $(shell sed -n 's|^APP_DATABASE_URL=.*@127\.0\.0\.1:\([0-9][0-9]*\)/.*|\1|p' "$(ENV_FILE)" 2>/dev/null | tail -1),5432)
DB_CONTAINER ?= myapp-postgres
DB_VOLUME    ?= myapp-pgdata

ifdef BIND
LISTEN_ENV := APP_LISTEN=$(BIND):$(or $(PORT),8080)
else ifdef PORT
LISTEN_ENV := APP_LISTEN=:$(PORT)
endif

env:
	@if [ -f "$(ENV_FILE)" ]; then echo "$(ENV_FILE) already exists; not touching it."; else cp .env.example "$(ENV_FILE)" && chmod 600 "$(ENV_FILE)" && echo "Created $(ENV_FILE). Edit it: set the passwords and APP_HOST."; fi

.PHONY: env build run kill restart logs clean db db-stop db-shell

build:
	cd server && go build -trimpath -o ../bin/appserver ./cmd/appserver

run: build
	@test -f "$(ENV_FILE)" || { echo "No $(ENV_FILE). Run: make env, then edit it."; exit 1; }
	@if [ -f $(PID) ] && kill -0 $$(cat $(PID)) 2>/dev/null; then \
		echo "Already running (pid $$(cat $(PID))). Use: make kill"; exit 1; fi
	@mkdir -p $(RUN_DIR)
	@APP_ENV_FILE="$(ENV_FILE)" $(LISTEN_ENV) nohup $(BIN) serve >>$(LOG) 2>&1 & echo $$! > $(PID)
	@sleep 2; if kill -0 $$(cat $(PID)) 2>/dev/null; then \
		echo "Running, pid $$(cat $(PID)). Log: $(LOG)"; \
	else \
		echo "Failed to start:"; tail -20 $(LOG); rm -f $(PID); exit 1; fi

kill:
	@if [ -f $(PID) ] && kill -0 $$(cat $(PID)) 2>/dev/null; then \
		p=$$(cat $(PID)); kill $$p; \
		for i in 1 2 3 4 5 6 7 8 9 10 11 12; do kill -0 $$p 2>/dev/null || break; sleep 1; done; \
		if kill -0 $$p 2>/dev/null; then kill -9 $$p; echo "Killed $$p (did not stop in 12 s)"; else echo "Stopped $$p"; fi; \
	else echo "Not running"; fi
	@rm -f $(PID)

restart: kill run

logs:
	@tail -f $(LOG)

clean:
	@if [ -f $(PID) ] && kill -0 $$(cat $(PID)) 2>/dev/null; then echo "Server is running. Use: make kill"; exit 1; fi
	rm -rf bin $(RUN_DIR)

db:
	@command -v docker >/dev/null || { echo "Docker is not installed. Install PostgreSQL instead and create user and database appserver."; exit 1; }
	@if docker container inspect $(DB_CONTAINER) >/dev/null 2>&1; then docker start $(DB_CONTAINER) >/dev/null; \
	else \
		pw=$$(sed -n 's/^POSTGRES_PASSWORD=//p' "$(ENV_FILE)" 2>/dev/null | tail -1 | tr -d "\"'"); \
		[ -n "$$pw" ] || { echo "Set POSTGRES_PASSWORD in $(ENV_FILE) first."; exit 1; }; \
		docker run -d --name $(DB_CONTAINER) --restart unless-stopped \
			-e POSTGRES_USER=appserver -e POSTGRES_DB=appserver -e POSTGRES_PASSWORD="$$pw" \
			-p 127.0.0.1:$(DB_PORT):5432 -v $(DB_VOLUME):/var/lib/postgresql postgres:18 >/dev/null; \
	fi; \
	for i in $$(seq 1 30); do docker exec $(DB_CONTAINER) true 2>/dev/null || docker start $(DB_CONTAINER) >/dev/null 2>&1; docker exec $(DB_CONTAINER) pg_isready -q -h 127.0.0.1 -U appserver -d appserver && break; sleep 1; done; \
	docker exec $(DB_CONTAINER) pg_isready -q -h 127.0.0.1 -U appserver -d appserver && echo "Database ready on 127.0.0.1:$(DB_PORT)." || { echo "Database did not start:"; docker logs --tail 20 $(DB_CONTAINER); exit 1; }

db-stop:
	@docker stop $(DB_CONTAINER) >/dev/null 2>&1 && echo "Database stopped (data kept)." || echo "Database not running."

db-shell:
	@docker exec -it $(DB_CONTAINER) psql -U appserver -d appserver
