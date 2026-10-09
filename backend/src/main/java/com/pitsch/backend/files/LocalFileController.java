package com.pitsch.backend.files;

import com.pitsch.backend.auth.PublicEndpoint;
import com.pitsch.backend.common.ApiException;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Serves local-storage objects for signed links (development/demo only; S3 presigned URLs are used in production). */
@RestController
public class LocalFileController {

    private final StorageProvider storage;

    public LocalFileController(StorageProvider storage) {
        this.storage = storage;
    }

    @PublicEndpoint
    @GetMapping("/api/v1/files/local")
    public ResponseEntity<byte[]> download(@RequestParam String key, @RequestParam long expires, @RequestParam String sig,
                                           @RequestParam(defaultValue = "download") String name) {
        if (!(storage instanceof LocalStorageProvider local) || !local.verify(key, expires, sig)) {
            throw ApiException.notFound("File");
        }
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(name).build().toString())
                .body(local.get(key));
    }
}
