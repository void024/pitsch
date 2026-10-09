package com.pitsch.backend.files;

import java.net.URI;
import java.time.Duration;

import com.pitsch.backend.config.PitschProperties;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

/** AWS S3 or any S3-compatible store (Cloudflare R2, MinIO, Backblaze B2, Wasabi) via a custom endpoint. */
public class S3StorageProvider implements StorageProvider {

    private final S3Client s3;
    private final S3Presigner presigner;
    private final String bucket;
    private final boolean awsNative;

    public S3StorageProvider(PitschProperties.Storage.S3 cfg) {
        if (cfg.getBucket() == null || cfg.getBucket().isBlank()) {
            throw new IllegalStateException("S3_BUCKET is required when STORAGE_PROVIDER=s3");
        }
        this.bucket = cfg.getBucket();
        this.awsNative = cfg.getEndpoint() == null || cfg.getEndpoint().isBlank();
        Region region = Region.of(cfg.getRegion() == null || cfg.getRegion().isBlank() ? "auto" : cfg.getRegion());
        AwsCredentialsProvider credentials = cfg.getAccessKeyId() == null || cfg.getAccessKeyId().isBlank()
                ? DefaultCredentialsProvider.create()
                : StaticCredentialsProvider.create(AwsBasicCredentials.create(cfg.getAccessKeyId(), cfg.getSecretAccessKey()));
        S3Configuration s3Config = S3Configuration.builder().pathStyleAccessEnabled(cfg.isPathStyle()).build();
        var clientBuilder = S3Client.builder().region(region).credentialsProvider(credentials)
                .serviceConfiguration(s3Config)
                // S3-compatible providers do not all support the newer default checksum headers.
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED);
        var presignerBuilder = S3Presigner.builder().region(region).credentialsProvider(credentials)
                .serviceConfiguration(s3Config);
        if (!awsNative) {
            clientBuilder.endpointOverride(URI.create(cfg.getEndpoint()));
        }
        // Presigned URLs are opened by the browser: they may need a different host than the one the backend uses.
        String presignEndpoint = cfg.getPublicEndpoint() != null && !cfg.getPublicEndpoint().isBlank()
                ? cfg.getPublicEndpoint() : (awsNative ? null : cfg.getEndpoint());
        if (presignEndpoint != null) {
            presignerBuilder.endpointOverride(URI.create(presignEndpoint));
        }
        this.s3 = clientBuilder.build();
        this.presigner = presignerBuilder.build();
    }

    @Override
    public void put(String key, byte[] bytes, String contentType) {
        PutObjectRequest.Builder req = PutObjectRequest.builder().bucket(bucket).key(key).contentType(contentType);
        if (awsNative) {
            req.serverSideEncryption(ServerSideEncryption.AES256);
        }
        s3.putObject(req.build(), RequestBody.fromBytes(bytes));
    }

    @Override
    public byte[] get(String key) {
        return s3.getObjectAsBytes(GetObjectRequest.builder().bucket(bucket).key(key).build()).asByteArray();
    }

    @Override
    public void delete(String key) {
        s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
    }

    @Override
    public URI signedDownloadUrl(String key, Duration ttl, String downloadFilename, String contentType) {
        GetObjectRequest get = GetObjectRequest.builder().bucket(bucket).key(key)
                .responseContentDisposition("attachment; filename=\"" + downloadFilename.replace("\"", "") + "\"")
                .responseContentType(contentType)
                .build();
        GetObjectPresignRequest presign = GetObjectPresignRequest.builder().signatureDuration(ttl).getObjectRequest(get).build();
        return URI.create(presigner.presignGetObject(presign).url().toString());
    }

    @Override
    public String name() {
        return "s3";
    }
}
