package com.pitsch.backend.org;

import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Modifying;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface InvitationRepository extends JpaRepository<Invitation, Long> {

    Optional<Invitation> findByTokenHash(String tokenHash);

    List<Invitation> findByOrganizationIdAndAcceptedAtIsNullAndRevokedAtIsNullOrderByCreatedAtDesc(Long organizationId);

    Optional<Invitation> findByIdAndOrganizationId(Long id, Long organizationId);

    @Modifying
    @Query("delete from Invitation i where i.organizationId = :orgId")
    int deleteByOrganization(@Param("orgId") Long orgId);
}
