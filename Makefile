.PHONY: demo screenshots lint-monitoring app-up observability-up loadtest e2e-local build test unit integration verify infra-up infra-down run run-sim e2e-install e2e e2e-local-app coverage

build:            ## compile without tests
	mvn -q -DskipTests compile

test:             ## unit tests + Docker-free API tests
	mvn -B test

integration:      ## Testcontainers integration tests (needs Docker)
	mvn -B verify -Dtest=NoSuchTest -Dsurefire.failIfNoSpecifiedTests=false

verify:           ## everything + Jacoco report (target/site/jacoco/index.html)
	mvn -B verify

coverage: test    ## open-able coverage report after tests
	mvn -B jacoco:report && echo "target/site/jacoco/index.html"

infra-up:
	docker compose up -d --wait

infra-down:
	docker compose down -v

run:              ## run the app against docker compose infra
	mvn spring-boot:run

run-sim:          ## same, with the continuous traffic simulator enabled
	mvn spring-boot:run -Dspring-boot.run.arguments=--simulator.enabled=true

e2e-install:
	cd e2e && npm install && npx playwright install chromium

e2e:              ## Playwright against a running app (BASE_URL, API_KEY; defaults http://localhost:8080 / dev-local-key)
	cd e2e && npx playwright test

e2e-local:        ## Playwright against the Docker-free stand-in app (see e2e-local-app; uses the test API key)
	cd e2e && API_KEY=test-key npx playwright test

e2e-local-app:    ## Docker-free app (embedded Kafka, H2, fake Redis) for local Playwright runs
	mvn -q test-compile org.codehaus.mojo:exec-maven-plugin:3.5.0:java -Dexec.classpathScope=test \
	  -Dexec.mainClass=com.example.fraud.support.StandaloneTestApp

app-up:           ## whole stack incl. the app image (needs Docker)
	docker compose --profile app up -d --build --wait

observability-up: ## + Prometheus (9090, alert rules), Grafana (3000), Loki logs + Tempo traces (Alloy ships logs)
	docker compose --profile app --profile observability up -d --build

loadtest:         ## k6 ingest burst against http://localhost:8080 (needs k6)
	k6 run -e API_KEY=$${API_KEY:-dev-local-key} loadtest/ingest-burst.js

lint-monitoring:  ## validate Prometheus rules + unit-test the alerts (needs Docker)
	docker run --rm --entrypoint promtool -v $$PWD:/w -w /w prom/prometheus:v2.55.1 check rules monitoring/alerts.yml
	docker run --rm --entrypoint promtool -v $$PWD:/w -w /w prom/prometheus:v2.55.1 test rules monitoring/alerts.test.yml

demo:             ## bring the whole stack up (app + observability) and seed demo data (needs Docker, curl)
	docker compose --profile app --profile observability up -d --build --wait
	@for a in demo-acct-1 demo-acct-2 demo-acct-3; do \
	  curl -fsS -XPOST -H "X-API-Key: $${API_KEY:-dev-local-key}" "localhost:8080/api/simulator/fraud-burst?accountId=$$a&count=12" >/dev/null && echo "seeded fraud burst for $$a"; \
	done
	@curl -fsS -XPOST -H "X-API-Key: $${API_KEY:-dev-local-key}" -H 'content-type: application/json' localhost:8080/api/blocklist -d '{"type":"ACCOUNT","value":"demo-blocked","reason":"demo"}' >/dev/null 2>&1 || true
	@echo "Dashboard http://localhost:8080  Grafana http://localhost:3000  Prometheus http://localhost:9090  (stop: docker compose --profile app --profile observability down -v)"

screenshots:      ## regenerate docs/img with the Docker-free stand-in app (make e2e-local-app, port 8099) running
	cd e2e && BASE_URL=http://127.0.0.1:8099 API_KEY=test-key SCREENSHOTS=1 npx playwright test screenshots
