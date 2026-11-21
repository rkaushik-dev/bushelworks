package io.github.rkaushikdev.bushelworks.api.metrics;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** The one route the counters are read through. Open without a key, and never rate limited. */
@RestController
@Tag(name = "ops")
public class MetricsRoutes {

    private final Metrics metrics;

    public MetricsRoutes(Metrics metrics) {
        this.metrics = metrics;
    }

    @GetMapping("/metrics")
    @Operation(summary = "Read Metrics", description = "What this process has done since it started.")
    public Map<String, Object> readMetrics() {
        return metrics.asMap();
    }
}
