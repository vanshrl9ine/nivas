package com.nivas.indexing;

import java.util.Map;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.nivas.outbox.OutboxEventRepository;
import com.nivas.outbox.OutboxStatus;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Operational endpoints for keeping search honest.
 *
 * <p>Locked to admins and mounted under {@code /api/v1/admin}, which the security
 * configuration guards as a whole.
 */
@RestController
@RequestMapping("/api/v1/admin/index")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin", description = "Index and outbox operations")
public class AdminIndexController {

    private final PropertyIndexService indexService;
    private final OutboxEventRepository outboxRepository;

    public AdminIndexController(PropertyIndexService indexService, OutboxEventRepository outboxRepository) {
        this.indexService = indexService;
        this.outboxRepository = outboxRepository;
    }

    /**
     * Rebuilds the search index from Postgres.
     *
     * <p>Synchronous on purpose: it is a deliberate operator action and the caller
     * should see the count and any failure directly rather than having to go
     * hunting through logs for a background job.
     */
    @PostMapping("/rebuild")
    @Operation(summary = "Rebuild the search index from the database")
    public Map<String, Object> rebuild() {
        long indexed = indexService.reindexAll();
        return Map.of(
                "indexed", indexed,
                "outboxPending", outboxRepository.countByStatus(OutboxStatus.PENDING),
                "outboxFailed", outboxRepository.countByStatus(OutboxStatus.FAILED));
    }
}
