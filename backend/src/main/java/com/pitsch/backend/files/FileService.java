package com.pitsch.backend.files;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import com.pitsch.backend.audit.AuditAction;
import com.pitsch.backend.audit.AuditService;
import com.pitsch.backend.billing.EntitlementService;
import com.pitsch.backend.billing.UsageMetric;
import com.pitsch.backend.common.ApiException;
import com.pitsch.backend.common.ErrorCode;
import com.pitsch.backend.common.Hashing;
import com.pitsch.backend.config.PitschProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Validates, scans and stores untrusted files; serves them back only through short-lived signed URLs. */
@Service
public class FileService {

    private static final Logger log = LoggerFactory.getLogger(FileService.class);

    private final StorageProvider storage;
    private final FileValidator validator;
    private final MalwareScanner scanner;
    private final StoredFileRepository files;
    private final EntitlementService entitlements;
    private final AuditService audit;
    private final PitschProperties props;

    public FileService(StorageProvider storage, FileValidator validator, MalwareScanner scanner,
                       StoredFileRepository files, EntitlementService entitlements, AuditService audit,
                       PitschProperties props) {
        this.storage = storage;
        this.validator = validator;
        this.scanner = scanner;
        this.files = files;
        this.entitlements = entitlements;
        this.audit = audit;
        this.props = props;
    }

    /**
     * Not @Transactional on purpose: validation failures must not mark the caller's transaction rollback-only (Gmail
     * ingestion skips bad attachments and continues). The metadata insert joins the caller's transaction.
     */
    public StoredFile store(Long orgId, Long actorUserId, String declaredName, byte[] bytes) {
        FileValidator.Validated v;
        try {
            v = validator.validate(declaredName, bytes);
        } catch (ApiException e) {
            audit.recordIndependent(orgId, actorUserId, AuditAction.FILE_REJECTED, "FILE", null,
                    Map.of("reason", e.getCode().name()));
            throw e;
        }
        long usedMb = files.totalBytes(orgId) / (1024 * 1024);
        long limitMb = entitlements.limit(orgId, UsageMetric.STORAGE_MB);
        if (limitMb != EntitlementService.UNLIMITED && usedMb + bytes.length / (1024 * 1024) > limitMb) {
            throw new ApiException(ErrorCode.QUOTA_EXCEEDED, "Your plan's storage limit is reached.");
        }
        MalwareScanner.Result scan = scanner.scan(bytes);
        if (scan.verdict() == MalwareScanner.Verdict.INFECTED) {
            log.warn("Rejected an infected upload for org {} ({})", orgId, scan.signature());
            audit.recordIndependent(orgId, actorUserId, AuditAction.FILE_REJECTED, "FILE", null, Map.of("reason", "MALWARE"));
            throw new ApiException(ErrorCode.FILE_REJECTED, "The file was rejected by the malware scanner.");
        }
        if (scan.verdict() == MalwareScanner.Verdict.ERROR) {
            throw new ApiException(ErrorCode.SERVICE_UNAVAILABLE, "The file could not be scanned. Try again shortly.");
        }
        String key = "org/" + orgId + "/files/" + UUID.randomUUID() + v.kind().extension;
        storage.put(key, bytes, v.contentType());
        StoredFile f = new StoredFile();
        f.setOrganizationId(orgId);
        f.setStorageKey(key);
        f.setFilename(v.filename());
        f.setContentType(v.contentType());
        f.setSizeBytes(bytes.length);
        f.setSha256(Hashing.sha256Hex(bytes));
        f.setScanStatus(scan.verdict().name());
        return files.save(f);
    }

    public byte[] read(StoredFile file) {
        return storage.get(file.getStorageKey());
    }

    public URI downloadUrl(StoredFile file) {
        return storage.signedDownloadUrl(file.getStorageKey(), Duration.ofMinutes(props.getStorage().getSignedUrlMinutes()),
                file.getFilename(), file.getContentType());
    }

    public void delete(StoredFile file) {
        try {
            storage.delete(file.getStorageKey());
        } catch (RuntimeException e) {
            log.warn("Could not delete object for stored file {}; metadata removed anyway", file.getId());
        }
        files.delete(file);
    }
}
