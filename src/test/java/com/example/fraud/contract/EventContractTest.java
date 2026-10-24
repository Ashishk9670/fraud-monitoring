package com.example.fraud.contract;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.fraud.json.Json;
import com.example.fraud.model.FraudAlert;
import com.example.fraud.model.Severity;
import com.example.fraud.model.Transaction;
import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.InputFormat;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Verifies the AsyncAPI payload schemas against events serialized by the real wire-format mapper. */
class EventContractTest {
    private static final Path DIR = Path.of("asyncapi");

    private static JsonSchema schema(String file) throws IOException {
        return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
                .getSchema(Files.readString(DIR.resolve("schemas").resolve(file)));
    }

    private static List<String> errors(JsonSchema s, Object event) throws IOException {
        String json = Json.MAPPER.writeValueAsString(event);
        return s.validate(json, InputFormat.JSON, ctx -> ctx.getExecutionConfig().setFormatAssertionsEnabled(true))
                .stream().map(Object::toString).toList();
    }

    @Test
    void serializedTransactionMatchesSchema() throws Exception {
        var tx = new Transaction("t-1", "acc-1", "m-1", new BigDecimal("12.34"), "EUR", "DE", Instant.parse("2025-01-01T00:00:00Z"));
        assertThat(errors(schema("transaction.schema.json"), tx)).isEmpty();
    }

    @Test
    void transactionWithDefaultedFieldsMatchesSchema() throws Exception {
        var tx = new Transaction(null, "acc-1", "m-1", BigDecimal.ONE, "USD", "US", null);
        assertThat(errors(schema("transaction.schema.json"), tx)).isEmpty();
    }

    @Test
    void serializedAlertMatchesSchema() throws Exception {
        var alert = new FraudAlert("a-1", "acc-1", "t-1", FraudAlert.RULE_HIGH_VALUE, Severity.HIGH, 0.9, "big",
                1, List.of("DE"), Instant.parse("2025-01-01T00:00:00Z"), Instant.parse("2025-01-01T00:01:00Z"),
                Instant.parse("2025-01-01T00:01:01Z"));
        assertThat(errors(schema("alert.schema.json"), alert)).isEmpty();
        assertThat(errors(schema("alert.schema.json"), alert.withRiskScore(0.42))).isEmpty();
    }

    @Test
    void schemaRejectsInvalidEvents() throws Exception {
        JsonNode bad = Json.MAPPER.readTree("{\"id\":\"x\",\"amount\":-1}");
        assertThat(schema("transaction.schema.json").validate(bad)).isNotEmpty();
        JsonNode badAlert = Json.MAPPER.readTree("{\"id\":\"x\",\"severity\":\"NOPE\"}");
        assertThat(schema("alert.schema.json").validate(badAlert)).isNotEmpty();
    }

    @Test
    void asyncApiDocumentReferencesBothSchemasAndTopics() throws Exception {
        String doc = Files.readString(DIR.resolve("asyncapi.yaml"));
        assertThat(doc).contains("schemas/transaction.schema.json", "schemas/alert.schema.json")
                .contains("address: " + com.example.fraud.config.Topics.TRANSACTIONS)
                .contains("address: " + com.example.fraud.config.Topics.FRAUD_ALERTS);
    }
}
