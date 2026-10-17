package com.example.fraud.rules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.fraud.config.FraudRulesProperties;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class RuleValidatorTest {
    final RuleSettings base = RuleSettings.from(FraudRulesProperties.defaults());

    @Test
    void nullFieldsKeepCurrentValues() {
        RuleSettings s = RuleValidator.merge(base, new RuleUpdate(null, 9, null, null));
        assertThat(s.maxTransactions()).isEqualTo(9);
        assertThat(s.windowSeconds()).isEqualTo(60);
        assertThat(s.minDistinctLocations()).isEqualTo(2);
        assertThat(s.highValueAmount()).isEqualByComparingTo("10000");
    }

    @Test
    void rejectsOutOfBoundsValues() {
        assertThatThrownBy(() -> RuleValidator.merge(base, new RuleUpdate(0, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("windowSeconds");
        assertThatThrownBy(() -> RuleValidator.merge(base, new RuleUpdate(3601, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RuleValidator.merge(base, new RuleUpdate(null, 0, null, null)))
                .hasMessageContaining("maxTransactions");
        assertThatThrownBy(() -> RuleValidator.merge(base, new RuleUpdate(null, null, 0, null)))
                .hasMessageContaining("minDistinctLocations");
        assertThatThrownBy(() -> RuleValidator.merge(base, new RuleUpdate(null, null, null, BigDecimal.ZERO)))
                .hasMessageContaining("highValueAmount");
        assertThatThrownBy(() -> RuleValidator.merge(base, new RuleUpdate(null, null, null, new BigDecimal("-5"))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void convertsToTopologyProperties() {
        FraudRulesProperties p = RuleValidator.merge(base, new RuleUpdate(120, 3, 4, new BigDecimal("250.50")))
                .toProperties();
        assertThat(p.window().toSeconds()).isEqualTo(120);
        assertThat(p.maxTransactions()).isEqualTo(3);
        assertThat(p.minDistinctLocations()).isEqualTo(4);
        assertThat(p.highValueAmount()).isEqualByComparingTo("250.50");
    }
}
