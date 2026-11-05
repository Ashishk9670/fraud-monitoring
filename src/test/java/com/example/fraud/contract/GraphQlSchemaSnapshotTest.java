package com.example.fraud.contract;

import static org.assertj.core.api.Assertions.assertThat;

import graphql.schema.idl.RuntimeWiring;
import graphql.schema.idl.SchemaGenerator;
import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.SchemaPrinter;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Snapshot of the public GraphQL schema. A failing test means the API contract changed: review the diff, then
 * regenerate with {@code mvn test -Dtest=GraphQlSchemaSnapshotTest -Dgraphql.snapshot.update=true}.
 */
class GraphQlSchemaSnapshotTest {
    private static final Path SNAPSHOT = Path.of("src/test/resources/graphql/schema.snapshot.graphqls");
    private static final Path SOURCE = Path.of("src/main/resources/graphql/schema.graphqls");

    @Test
    void schemaMatchesSnapshot() throws Exception {
        var registry = new SchemaParser().parse(Files.readString(SOURCE));
        var schema = new SchemaGenerator().makeExecutableSchema(registry, RuntimeWiring.newRuntimeWiring().build());
        String printed = new SchemaPrinter(SchemaPrinter.Options.defaultOptions()
                .includeDirectives(false).includeSchemaDefinition(true)).print(schema).strip() + "\n";
        if (Boolean.getBoolean("graphql.snapshot.update")) {
            Files.writeString(SNAPSHOT, printed);
        }
        assertThat(printed).isEqualTo(Files.readString(SNAPSHOT));
    }
}
