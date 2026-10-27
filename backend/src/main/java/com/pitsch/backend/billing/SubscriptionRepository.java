package com.pitsch.backend.billing;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SubscriptionRepository extends JpaRepository<Subscription, Long> {

    Optional<Subscription> findByOrganizationId(Long organizationId);

    Optional<Subscription> findByProviderCustomerId(String providerCustomerId);

    Optional<Subscription> findByProviderSubscriptionId(String providerSubscriptionId);
}
