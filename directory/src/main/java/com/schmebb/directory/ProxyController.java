package com.schmebb.directory;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/proxies")
public class ProxyController {

    private final ProxyRegistry registry;

    public ProxyController(ProxyRegistry registry) {
        this.registry = registry;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Proxy register(@RequestBody Proxy request) {
        return registry.register(request);
    }

    @GetMapping("/{phone}")
    public ResponseEntity<Proxy> resolve(@PathVariable String phone) {
        return ResponseEntity.of(registry.resolve(phone));
    }

    @ExceptionHandler(InvalidProxyException.class)
    ProblemDetail invalid(InvalidProxyException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(DuplicateProxyException.class)
    ProblemDetail duplicate(DuplicateProxyException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }
}
