package com.example.fraud.web;

import com.example.fraud.messaging.TransactionPublisher;
import com.example.fraud.model.Transaction;
import com.example.fraud.simulator.TransactionGenerator;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Manual triggers for demos and end-to-end tests. */
@RestController
@RequestMapping("/api/simulator")
public class SimulatorController {
    private final TransactionPublisher publisher;
    private final TransactionGenerator generator = new TransactionGenerator(System.nanoTime(), 200);

    public SimulatorController(TransactionPublisher publisher) {
        this.publisher = publisher;
    }

    @PostMapping("/fraud-burst")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, Object> burst(@RequestParam(defaultValue = "acct-demo") String accountId,
                                     @RequestParam(defaultValue = "7") int count) {
        List<Transaction> txns = generator.fraudBurst(accountId, Math.max(1, Math.min(count, 100)));
        txns.forEach(publisher::publish);
        return Map.of("accountId", accountId, "sent", txns.size());
    }

    @PostMapping("/normal")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, Object> normal(@RequestParam(defaultValue = "10") int count) {
        int n = Math.max(1, Math.min(count, 1000));
        for (int i = 0; i < n; i++) publisher.publish(generator.normal());
        return Map.of("sent", n);
    }
}
