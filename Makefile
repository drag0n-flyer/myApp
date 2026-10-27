# Build and run the chat server on this machine, without Docker.
# Settings come from .env (copy deploy/.env.example and edit); a variable set
# in the shell wins over the file.
#
#   make build     build server/bin/appserver
#   make run       start it in the background (log: server/run/appserver.log)
#   make kill      stop it
#   make restart   kill, then run
#   make logs      follow the log
#   make clean     remove the binary and the log (server must be stopped)
#
# Listen address: APP_LISTEN in .env, or for one run:
#   make run BIND=0.0.0.0 PORT=8080   every network: other machines can connect
#   make run BIND=127.0.0.1           this machine only

ENV_FILE ?= .env
BIN      := server/bin/appserver
RUN_DIR  := server/run
PID      := $(RUN_DIR)/appserver.pid
LOG      := $(RUN_DIR)/appserver.log

ifdef BIND
LISTEN_ENV := APP_LISTEN=$(BIND):$(or $(PORT),8080)
else ifdef PORT
LISTEN_ENV := APP_LISTEN=:$(PORT)
endif

.PHONY: build run kill restart logs clean

build:
	cd server && go build -trimpath -o bin/appserver ./cmd/appserver

run: build
	@test -f "$(ENV_FILE)" || { echo "No $(ENV_FILE). Run: cp deploy/.env.example $(ENV_FILE), then edit it."; exit 1; }
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
	rm -rf server/bin $(RUN_DIR)
