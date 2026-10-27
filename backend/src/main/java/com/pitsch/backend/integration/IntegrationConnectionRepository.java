package com.pitsch.backend.integration;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface IntegrationConnectionRepository extends JpaRepository<IntegrationConnection, Long> {

    Optional<IntegrationConnection> findByOrganizationIdAndUserIdAndProvider(Long organizationId, Long userId, String provider);

    List<IntegrationConnection> findByOrganizationId(Long organizationId);

    List<IntegrationConnection> findByProviderAndAccountEmailIgnoreCase(String provider, String accountEmail);

    List<IntegrationConnection> findByStatus(String status);

    Optional<IntegrationConnection> findByIdAndOrganizationId(Long id, Long organizationId);

    List<IntegrationConnection> findByUserId(Long userId);
}
