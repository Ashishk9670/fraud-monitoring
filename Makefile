.PHONY: build test unit integration verify infra-up infra-down run run-sim e2e-install e2e e2e-local-app coverage

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

e2e:              ## Playwright against a running app (BASE_URL, default http://localhost:8080)
	cd e2e && npx playwright test

e2e-local-app:    ## Docker-free app (embedded Kafka, H2, fake Redis) for local Playwright runs
	mvn -q test-compile org.codehaus.mojo:exec-maven-plugin:3.5.0:java -Dexec.classpathScope=test \
	  -Dexec.mainClass=com.example.fraud.support.StandaloneTestApp
