package com.example.fraud.replay;

import com.example.fraud.model.FraudAlert;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/replay")
public class ReplayController {
    private final ReplayService replay;
    private final ReplayAlertCollector collector;

    public ReplayController(ReplayService replay, ReplayAlertCollector collector) {
        this.replay = replay;
        this.collector = collector;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ReplayRun start(@RequestBody ReplayRequest req) {
        return replay.start(req);
    }

    @GetMapping("/{id}")
    public Map<String, Object> status(@PathVariable String id) {
        ReplayRun run = require(id);
        return Map.of("run", run, "alertCount", collector.alerts(id).size());
    }

    @GetMapping("/{id}/alerts")
    public List<FraudAlert> alerts(@PathVariable String id) {
        require(id);
        return collector.alerts(id);
    }

    private ReplayRun require(String id) {
        ReplayRun run = collector.run(id);
        if (run == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown replay " + id);
        return run;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail invalid(IllegalArgumentException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }
}
