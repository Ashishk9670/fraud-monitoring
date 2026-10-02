package com.example.fraud.messaging;

import com.example.fraud.model.FraudAlert;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

/** Fan-out of new alerts to live GraphQL subscribers. */
@Component
public class AlertBroadcaster {
    private final Sinks.Many<FraudAlert> sink = Sinks.many().multicast().directBestEffort();

    public synchronized void publish(FraudAlert alert) {
        sink.tryEmitNext(alert);
    }

    public Flux<FraudAlert> stream() {
        return sink.asFlux();
    }
}
