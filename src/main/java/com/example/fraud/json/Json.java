package com.example.fraud.json;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serdes;

/** Shared JSON mapper + Kafka serde factory so every component agrees on the wire format. */
public final class Json {
    public static final ObjectMapper MAPPER = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private Json() {}

    public static <T> Serde<T> serde(Class<T> type) {
        return Serdes.serdeFrom(
                (topic, data) -> {
                    if (data == null) return null;
                    try {
                        return MAPPER.writeValueAsBytes(data);
                    } catch (Exception e) {
                        throw new IllegalStateException("Cannot serialize " + type.getSimpleName(), e);
                    }
                },
                (topic, bytes) -> {
                    if (bytes == null) return null;
                    try {
                        return MAPPER.readValue(bytes, type);
                    } catch (Exception e) {
                        throw new IllegalStateException("Cannot deserialize " + type.getSimpleName(), e);
                    }
                });
    }
}
