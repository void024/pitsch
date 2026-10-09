package com.pitsch.backend.files;

import java.net.URI;
import java.time.Duration;

/** Private object storage for pitch decks and attachments. Objects are never publicly readable. */
public interface StorageProvider {

    void put(String key, byte[] bytes, String contentType);

    byte[] get(String key);

    void delete(String key);

    /** A short-lived URL that lets the browser download one object (as an attachment). */
    URI signedDownloadUrl(String key, Duration ttl, String downloadFilename, String contentType);

    String name();
}
