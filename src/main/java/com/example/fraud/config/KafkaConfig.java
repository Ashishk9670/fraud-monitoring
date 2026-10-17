package com.example.fraud.config;

import com.example.fraud.redis.BlocklistService;
import com.example.fraud.redis.FeatureStore;
import com.example.fraud.redis.VelocityCounter;
import com.example.fraud.rules.RulesProvider;
import com.example.fraud.streams.FraudTopology;
import com.example.fraud.model.FraudAlert;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.kstream.KStream;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafkaStreams;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
@EnableKafkaStreams
public class KafkaConfig {

    @Bean
    NewTopic transactionsTopic() {
        return TopicBuilder.name(Topics.TRANSACTIONS).partitions(3).replicas(1).build();
    }

    @Bean
    NewTopic fraudAlertsTopic() {
        return TopicBuilder.name(Topics.FRAUD_ALERTS).partitions(3).replicas(1).build();
    }

    @Bean
    KStream<String, FraudAlert> fraudTopology(StreamsBuilder builder, RulesProvider rules,
                                              BlocklistService blocklist, VelocityCounter velocity,
                                              FeatureStore features) {
        return FraudTopology.build(builder, rules, blocklist, velocity, features);
    }
}
