package io.github.rkaushikdev.bushelworks.servicetest;

import io.github.rkaushikdev.bushelworks.BushelworksError;
import io.github.rkaushikdev.bushelworks.ConfigurationError;
import io.github.rkaushikdev.bushelworks.ConflictError;
import io.github.rkaushikdev.bushelworks.LookupFailure;
import io.github.rkaushikdev.bushelworks.ValidationError;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Routes a test mounts beside the application's, each refusing with one kind of domain error. */
@RestController
@RequestMapping("/_test")
public class ProbeRoutes {

    @GetMapping("/validation")
    public void validation() {
        throw new ValidationError("tare exceeds gross", "tare_exceeds_gross");
    }

    @GetMapping("/missing")
    public void missing() {
        throw new LookupFailure("no ticket A-99999 at HOME");
    }

    @GetMapping("/conflict")
    public void conflict() {
        throw new ConflictError("ticket A-10441 already written at HOME");
    }

    @GetMapping("/misconfigured")
    public void misconfigured() {
        throw new ConfigurationError("no shrink table filed");
    }

    @GetMapping("/plain")
    public void plain() {
        throw new BushelworksError("something the office has not seen before");
    }
}
