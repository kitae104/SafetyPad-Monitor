SHELL := /bin/bash
ENV := set -a; . ./.env; set +a;
PROD := docker compose -p safetypad-prod -f deploy/docker-compose.prod.yml --env-file deploy/.env.prod
PROD_ENV := set -a; . ./deploy/.env.prod; set +a;

.PHONY: env db-up db-down backend-run backend-test frontend-dev frontend-check e2e check \
        prod-env prod-up prod-down prod-smoke backup restore-check load-test

# ---------- local development ----------
env:
	@test -f .env || (cp .env.example .env && sed -i "s/^DB_PASSWORD=.*/DB_PASSWORD=$$(openssl rand -hex 16)/; s/^DEVICE_KEY_PEPPER=.*/DEVICE_KEY_PEPPER=$$(openssl rand -hex 32)/; s/^ADMIN_BOOTSTRAP_PASSWORD=.*/ADMIN_BOOTSTRAP_PASSWORD=$$(openssl rand -hex 12)/; s/^METRICS_PASSWORD=.*/METRICS_PASSWORD=$$(openssl rand -hex 16)/" .env && echo ".env created with random local secrets")

db-up: env
	$(ENV) docker compose up -d --wait postgres

db-down:
	docker compose down

backend-run: db-up
	$(ENV) cd backend && ./gradlew bootRun

backend-test:
	cd backend && ./gradlew test

frontend-dev:
	cd frontend && npm run dev

frontend-check:
	cd frontend && npm run lint && npm test && npm run build

# Needs the backend running (make backend-run) with SPRING_PROFILES_ACTIVE=dev; uses the installed Google Chrome.
e2e:
	$(ENV) cd frontend && npx playwright test

check: backend-test frontend-check

# ---------- production-like stack (single host) ----------
prod-env:
	@test -f deploy/.env.prod || (cp deploy/.env.prod.example deploy/.env.prod && chmod 600 deploy/.env.prod && \
	  sed -i "s/^DB_PASSWORD=.*/DB_PASSWORD=$$(openssl rand -hex 16)/; s/^DEVICE_KEY_PEPPER=.*/DEVICE_KEY_PEPPER=$$(openssl rand -hex 32)/; s/^ADMIN_BOOTSTRAP_PASSWORD=.*/ADMIN_BOOTSTRAP_PASSWORD=$$(openssl rand -hex 14)/; s/^ADMIN_BOOTSTRAP_USERNAME=.*/ADMIN_BOOTSTRAP_USERNAME=admin/; s/^METRICS_PASSWORD=.*/METRICS_PASSWORD=$$(openssl rand -hex 16)/" deploy/.env.prod && \
	  echo "deploy/.env.prod created: set DOMAIN (and ports for a rehearsal) before 'make prod-up'")

prod-up: prod-env
	$(PROD) up -d --build

prod-down:
	$(PROD) down

# BASE_URL defaults to the local rehearsal ports; override for a real domain.
prod-smoke:
	$(PROD_ENV) BASE_URL=$${BASE_URL:-https://localhost:$${HTTPS_PORT:-443}} CURL_OPTS=$${CURL_OPTS:--k} \
	  ADMIN_USER=$$ADMIN_BOOTSTRAP_USERNAME ADMIN_PASSWORD=$$ADMIN_BOOTSTRAP_PASSWORD scripts/smoke.sh

backup:
	$(PROD_ENV) BACKUP_PASSPHRASE_FILE=$${BACKUP_PASSPHRASE_FILE:-deploy/.backup-passphrase} scripts/backup.sh

restore-check:
	$(PROD_ENV) BACKUP_PASSPHRASE_FILE=$${BACKUP_PASSPHRASE_FILE:-deploy/.backup-passphrase} scripts/restore.sh $(FILE)

# Staging/rehearsal ONLY (creates throw-away devices). Raise INGEST_PER_IP_PER_MINUTE on the target first.
load-test:
	$(PROD_ENV) ADMIN_USER=$$ADMIN_BOOTSTRAP_USERNAME ADMIN_PASSWORD=$$ADMIN_BOOTSTRAP_PASSWORD \
	  python3 scripts/load_test.py --base-url $${BASE_URL:-https://localhost:$${HTTPS_PORT:-443}} --insecure $(ARGS)
