package com.example.fraud.replay;

import com.example.fraud.config.Topics;
import com.example.fraud.json.Json;
import com.example.fraud.model.FraudAlert;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Collects alerts produced on the replay alerts topic (not on the live path: no cases, analytics or push) so a replay
 * run can be inspected. Memory is bounded: the most recent {@link #MAX_RUNS} runs, {@link #MAX_ALERTS_PER_RUN} each.
 */
@Component
public class ReplayAlertCollector {
    private static final Logger log = LoggerFactory.getLogger(ReplayAlertCollector.class);
    static final int MAX_RUNS = 20;
    static final int MAX_ALERTS_PER_RUN = 10_000;

    private final Map<String, ReplayRun> runs = new LinkedHashMap<>();
    private final Map<String, List<FraudAlert>> alerts = new LinkedHashMap<>();

    public synchronized void register(ReplayRun run) {
        runs.put(run.id(), run);
        alerts.putIfAbsent(run.id(), new ArrayList<>());
        while (runs.size() > MAX_RUNS) {
            String oldest = runs.keySet().iterator().next();
            runs.remove(oldest);
            alerts.remove(oldest);
        }
    }

    public synchronized ReplayRun run(String id) {
        return runs.get(id);
    }

    public synchronized List<FraudAlert> alerts(String id) {
        return List.copyOf(alerts.getOrDefault(id, List.of()));
    }

    @KafkaListener(topics = Topics.FRAUD_ALERTS_REPLAY, groupId = "replay-sink")
    public void onReplayAlert(ConsumerRecord<String, String> rec) {
        String key = rec.key();
        int sep = key == null ? -1 : key.indexOf('|');
        if (sep < 0) return;
        try {
            add(key.substring(0, sep), Json.MAPPER.readValue(rec.value(), FraudAlert.class));
        } catch (Exception e) {
            log.warn("Skipping malformed replay alert: {}", e.toString());
        }
    }

    synchronized void add(String replayId, FraudAlert alert) {
        List<FraudAlert> list = alerts.get(replayId);
        if (list != null && list.size() < MAX_ALERTS_PER_RUN) list.add(alert);
    }
}
