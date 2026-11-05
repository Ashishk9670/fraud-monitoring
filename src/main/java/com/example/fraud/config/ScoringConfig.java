package com.example.fraud.config;

import com.example.fraud.scoring.RiskScorer;
import com.example.fraud.scoring.ZScoreRiskScorer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ScoringConfig {
    /** Default scorer; define another {@link RiskScorer} bean to replace it. */
    @Bean
    @ConditionalOnMissingBean(RiskScorer.class)
    RiskScorer defaultRiskScorer() {
        return new ZScoreRiskScorer();
    }
}
