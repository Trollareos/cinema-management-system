package com.example.demo.service;

import com.example.demo.domain.AuditLog;
import com.example.demo.repo.AuditLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuditLogService {

    private static final Logger log = LoggerFactory.getLogger(AuditLogService.class);

    private final AuditLogRepository repo;

    public AuditLogService(AuditLogRepository repo) {
        this.repo = repo;
    }

    /**
     * REQUIRES_NEW ώστε το audit να γράφεται ακόμα κι αν σε άλλο layer γίνει rollback.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void log(String actorUsername, String action, Long programId, Long screeningId, String details) {
        AuditLog a = new AuditLog();
        a.setActorUsername(actorUsername);
        a.setAction(action);
        a.setProgramId(programId);
        a.setScreeningId(screeningId);
        a.setDetails(details);

        repo.save(a);

        // και στο application logs
        log.info("AUDIT action={} actor={} programId={} screeningId={} details={}",
                action, actorUsername, programId, screeningId, details);
    }
}
