package com.pitsch.backend.user;

import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Modifying;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface UserSettingsRepository extends JpaRepository<UserSettings, Long> {

    Optional<UserSettings> findByUserId(Long userId);

    @Modifying
    @Query("delete from UserSettings s where s.userId = :userId")
    int deleteByUser(@Param("userId") Long userId);
}
