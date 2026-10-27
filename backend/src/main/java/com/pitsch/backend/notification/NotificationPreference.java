package com.pitsch.backend.notification;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "notification_preferences")
public class NotificationPreference {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false)
    private Long organizationId;

    @Column(nullable = false, length = 40)
    private String type;

    @Column(nullable = false)
    private boolean inApp = true;

    @Column(nullable = false)
    private boolean email;

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long v) { this.userId = v; }
    public Long getOrganizationId() { return organizationId; }
    public void setOrganizationId(Long v) { this.organizationId = v; }
    public String getType() { return type; }
    public void setType(String v) { this.type = v; }
    public boolean isInApp() { return inApp; }
    public void setInApp(boolean v) { this.inApp = v; }
    public boolean isEmail() { return email; }
    public void setEmail(boolean v) { this.email = v; }
}
