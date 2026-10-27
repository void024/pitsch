package com.pitsch.backend.files;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StoredFileRepository extends JpaRepository<StoredFile, Long> {

    Optional<StoredFile> findByIdAndOrganizationId(Long id, Long organizationId);

    List<StoredFile> findByOrganizationId(Long organizationId);

    @Query("select coalesce(sum(f.sizeBytes), 0) from StoredFile f where f.organizationId = :orgId")
    long totalBytes(@Param("orgId") Long orgId);
}
