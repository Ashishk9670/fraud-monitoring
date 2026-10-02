package com.example.fraud.streams;

import com.example.fraud.model.Transaction;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.streams.processor.TimestampExtractor;

/** Uses the transaction's own event time rather than the Kafka append time. */
public class TransactionTimestampExtractor implements TimestampExtractor {
    @Override
    public long extract(ConsumerRecord<Object, Object> record, long partitionTime) {
        if (record.value() instanceof Transaction t) return t.timestamp().toEpochMilli();
        return record.timestamp() >= 0 ? record.timestamp() : partitionTime;
    }
}
