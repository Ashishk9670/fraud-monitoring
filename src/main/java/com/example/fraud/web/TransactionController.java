package com.example.fraud.web;

import com.example.fraud.messaging.TransactionPublisher;
import com.example.fraud.model.Transaction;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/transactions")
public class TransactionController {
    private final TransactionPublisher publisher;

    public TransactionController(TransactionPublisher publisher) {
        this.publisher = publisher;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, String> ingest(@Valid @RequestBody Transaction t) {
        publisher.publish(t);
        return Map.of("id", t.id(), "status", "ACCEPTED");
    }

    @PostMapping("/batch")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, Object> ingestBatch(@RequestBody List<@Valid Transaction> batch) {
        batch.forEach(publisher::publish);
        return Map.of("accepted", batch.size());
    }
}
