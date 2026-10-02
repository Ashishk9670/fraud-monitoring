package com.example.fraud.simulator;

import com.example.fraud.messaging.TransactionPublisher;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Continuously produces synthetic traffic when {@code simulator.enabled=true}. */
@Component
@ConditionalOnProperty(name = "simulator.enabled", havingValue = "true")
public class SimulatorService {
    private final TransactionPublisher publisher;
    private final TransactionGenerator generator = new TransactionGenerator(42, 200);
    private final double burstProbability;

    public SimulatorService(TransactionPublisher publisher,
                            @Value("${simulator.burst-probability:0.02}") double burstProbability) {
        this.publisher = publisher;
        this.burstProbability = burstProbability;
    }

    @Scheduled(fixedRateString = "${simulator.interval-ms:500}")
    public void tick() {
        publisher.publish(generator.normal());
        if (ThreadLocalRandom.current().nextDouble() < burstProbability) {
            generator.fraudBurst("acct-" + ThreadLocalRandom.current().nextInt(200), 7).forEach(publisher::publish);
        }
    }
}
