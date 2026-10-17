package com.example.fraud.rules;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Runtime-editable fraud rules. Protected by the API key like every other {@code /api/**} endpoint. */
@RestController
@RequestMapping("/api/rules")
public class RulesController {
    private final RuleService rules;

    public RulesController(RuleService rules) {
        this.rules = rules;
    }

    @GetMapping
    public RuleSettings get() {
        return rules.settings();
    }

    @PutMapping
    public RuleSettings update(@RequestBody RuleUpdate update, Authentication auth) {
        return rules.update(update, auth == null ? "unknown" : auth.getName());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail invalid(IllegalArgumentException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }
}
