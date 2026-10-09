package com.pitsch.backend.org;

import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Modifying;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface MembershipRepository extends JpaRepository<Membership, Long> {

    Optional<Membership> findByOrganizationIdAndUserId(Long organizationId, Long userId);

    List<Membership> findByUserIdOrderByCreatedAtAsc(Long userId);

    List<Membership> findByOrganizationIdOrderByCreatedAtAsc(Long organizationId);

    long countByOrganizationId(Long organizationId);

    long countByOrganizationIdAndRole(Long organizationId, Role role);

    Optional<Membership> findByIdAndOrganizationId(Long id, Long organizationId);

    @Modifying
    @Query("delete from Membership m where m.organizationId = :orgId")
    int deleteByOrganization(@Param("orgId") Long orgId);
}
