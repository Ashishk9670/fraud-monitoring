package com.example.fraud.web;

import com.example.fraud.redis.BlockType;
import com.example.fraud.redis.BlocklistService;
import com.example.fraud.redis.FeatureStore;
import com.example.fraud.redis.VelocityCounter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class BlocklistController {
    public record BlockRequest(@NotNull BlockType type, @NotBlank String value, String reason, Long ttlSeconds) {}

    private final BlocklistService blocklist;
    private final FeatureStore features;
    private final VelocityCounter velocity;

    public BlocklistController(BlocklistService blocklist, FeatureStore features, VelocityCounter velocity) {
        this.blocklist = blocklist;
        this.features = features;
        this.velocity = velocity;
    }

    @PostMapping("/blocklist")
    @ResponseStatus(HttpStatus.CREATED)
    public void block(@Valid @RequestBody BlockRequest r) {
        blocklist.block(r.type(), r.value(), r.reason(), r.ttlSeconds() == null ? null : Duration.ofSeconds(r.ttlSeconds()));
    }

    @DeleteMapping("/blocklist/{type}/{value}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unblock(@PathVariable BlockType type, @PathVariable String value) {
        blocklist.unblock(type, value);
    }

    @GetMapping("/blocklist/{type}/{value}")
    public Map<String, Boolean> check(@PathVariable BlockType type, @PathVariable String value) {
        return Map.of("blocked", blocklist.isBlocked(type, value));
    }

    @GetMapping("/accounts/{accountId}/features")
    public Map<String, Object> features(@PathVariable String accountId) {
        return Map.of("features", features.get(accountId), "velocity", velocity.current(accountId));
    }
}
