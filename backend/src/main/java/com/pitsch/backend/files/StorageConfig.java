package com.pitsch.backend.files;

import java.time.Clock;

import com.pitsch.backend.config.PitschProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class StorageConfig {

    @Bean
    public StorageProvider storageProvider(PitschProperties props, Clock clock) {
        String provider = props.getStorage().getProvider();
        if ("s3".equalsIgnoreCase(provider)) {
            return new S3StorageProvider(props.getStorage().getS3());
        }
        if ("local".equalsIgnoreCase(provider)) {
            if (props.isProduction()) {
                throw new IllegalStateException("STORAGE_PROVIDER=local is not allowed in production (use s3)");
            }
            return new LocalStorageProvider(props.getStorage().getLocalDir(), props.getPublicApiUrl(), clock);
        }
        throw new IllegalStateException("Unknown STORAGE_PROVIDER '" + provider + "' (s3 | local)");
    }

    @Bean
    public MalwareScanner malwareScanner(PitschProperties props) {
        if ("clamav".equalsIgnoreCase(props.getMalware().getScanner())) {
            return new ClamAvScanner(props.getMalware().getClamavHost(), props.getMalware().getClamavPort());
        }
        return new NoopMalwareScanner();
    }
}
