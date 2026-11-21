package io.github.rkaushikdev.bushelworks.api;

import io.github.rkaushikdev.bushelworks.Bushelworks;
import io.github.rkaushikdev.bushelworks.persistence.Database;
import io.github.rkaushikdev.bushelworks.persistence.Migrations;
import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The routes a load balancer and a deploy check call: liveness, readiness, and the pages the API
 * document is read through. None of them asks for a key and none is rate limited.
 */
@RestController
@Tag(name = "ops")
public class ServiceRoutes {

    /** What {@code /ready} reports for a schema with no migration history at all. */
    public static final String UNMANAGED = "unmanaged";

    /** What {@code /ready} reports for a migration history with nothing applied in it. */
    public static final String EMPTY = "empty";

    private static final String REDOC =
            """
            <!DOCTYPE html>
            <html>
            <head>
            <title>bushelworks - ReDoc</title>
            <meta charset="utf-8"/>
            <meta name="viewport" content="width=device-width, initial-scale=1">
            </head>
            <body>
            <redoc spec-url="/openapi.json"></redoc>
            <script src="https://unpkg.com/redoc@2/bundles/redoc.standalone.js"></script>
            </body>
            </html>
            """;

    private final Database database;

    public ServiceRoutes(Database database) {
        this.database = database;
    }

    @GetMapping("/health")
    @Operation(summary = "Health", description = "Liveness. Says nothing about the database on purpose.")
    public Map<String, String> health() {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("status", "ok");
        body.put("version", Bushelworks.VERSION);
        return body;
    }

    /**
     * Readiness: opens a session and asks what revision the schema is at.
     *
     * <p>The cheapest statement there is used to be enough. It is not: a box that can reach the
     * database but is a migration behind will take a ticket and then fail to settle it, which is
     * worse than refusing to start. The revision comes back in the body so a deploy can be checked
     * without logging in.
     */
    @GetMapping("/ready")
    @Operation(summary = "Ready", description = "Readiness, and the revision the schema is at.")
    public Map<String, String> ready() {
        String revision = database.inSession(session -> {
            session.createNativeQuery("SELECT 1").getSingleResult();
            return schemaRevision(session);
        });
        Map<String, String> body = new LinkedHashMap<>();
        body.put("status", "ready");
        body.put("schema", revision);
        return body;
    }

    /** The API document, rendered for reading rather than for trying routes out. */
    @Hidden
    @GetMapping("/redoc")
    public ResponseEntity<String> redoc() {
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(REDOC);
    }

    /** Where the interactive document's sign in comes back to. */
    @Hidden
    @GetMapping("/docs/oauth2-redirect")
    public ResponseEntity<Void> oauthRedirect() {
        return ResponseEntity.status(HttpStatus.FOUND)
                .header(HttpHeaders.LOCATION, "/swagger-ui/oauth2-redirect.html")
                .build();
    }

    /**
     * The newest revision the migrations applied. A schema built some other way has no history table
     * at all, which is reported as {@value #UNMANAGED} rather than treated as a failure.
     */
    static String schemaRevision(EntityManager session) {
        List<?> applied;
        try {
            applied = session.createNativeQuery("SELECT \"version\" FROM \"" + Migrations.HISTORY_TABLE + "\""
                            + " WHERE \"success\" AND \"version\" IS NOT NULL ORDER BY \"installed_rank\" DESC")
                    .getResultList();
        } catch (PersistenceException noHistory) {
            return UNMANAGED;
        }
        return applied.isEmpty() ? EMPTY : String.valueOf(applied.get(0));
    }
}
