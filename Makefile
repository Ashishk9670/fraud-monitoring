.PHONY: app-up observability-up loadtest e2e-local build test unit integration verify infra-up infra-down run run-sim e2e-install e2e e2e-local-app coverage

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

observability-up: ## + Prometheus (9090), Grafana (3000, dashboard "Fraud Monitoring"), Jaeger (16686)
	docker compose --profile app --profile observability up -d --build

loadtest:         ## k6 ingest burst against http://localhost:8080 (needs k6)
	k6 run -e API_KEY=$${API_KEY:-dev-local-key} loadtest/ingest-burst.js
