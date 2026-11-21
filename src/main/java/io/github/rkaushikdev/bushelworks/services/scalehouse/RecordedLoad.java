package io.github.rkaushikdev.bushelworks.services.scalehouse;

import io.github.rkaushikdev.bushelworks.domain.application.Application;
import io.github.rkaushikdev.bushelworks.services.intake.GradedLoad;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * What taking one load in did.
 *
 * @param ticketId the stored ticket's key
 * @param ticketNumber the ticket's number
 * @param load the load as intake graded it
 * @param applications where its bushels went, contract by contract, an overage last
 */
public record RecordedLoad(Integer ticketId, String ticketNumber, GradedLoad load, List<Application> applications) {

    public RecordedLoad {
        Objects.requireNonNull(ticketId, "ticketId");
        Objects.requireNonNull(ticketNumber, "ticketNumber");
        Objects.requireNonNull(load, "load");
        applications = List.copyOf(applications);
    }

    /** The bushels that landed on a contract. */
    public BigDecimal contractedBushels() {
        return applications.stream()
                .filter(application -> application.contractNumber().isPresent())
                .map(Application::bushels)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** The bushels that landed on no contract. */
    public BigDecimal openBushels() {
        return applications.stream()
                .filter(application -> application.contractNumber().isEmpty())
                .map(Application::bushels)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** The contracts the load landed on, in the order it filled them. */
    public List<String> contractNumbers() {
        return applications.stream()
                .map(Application::contractNumber)
                .flatMap(Optional::stream)
                .toList();
    }
}
