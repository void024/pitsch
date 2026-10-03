package com.pitsch.backend.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "user_settings")
public class UserSettings {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private Long userId;

    private String timeFormat = "12h";
    private boolean compactMode = false;
    private boolean notifyEmail = true;
    private boolean notifyTaskReminders = true;
    private boolean notifyEventReminders = true;
    private boolean notifyWorkflowUpdates = true;

    /** Used by the Calendar and Email Response agents. */
    private String timezone;
    private String firmName;
    private String investorTitle;

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getTimeFormat() { return timeFormat; }
    public void setTimeFormat(String timeFormat) { this.timeFormat = timeFormat; }
    public boolean isCompactMode() { return compactMode; }
    public void setCompactMode(boolean compactMode) { this.compactMode = compactMode; }
    public boolean isNotifyEmail() { return notifyEmail; }
    public void setNotifyEmail(boolean v) { this.notifyEmail = v; }
    public boolean isNotifyTaskReminders() { return notifyTaskReminders; }
    public void setNotifyTaskReminders(boolean v) { this.notifyTaskReminders = v; }
    public boolean isNotifyEventReminders() { return notifyEventReminders; }
    public void setNotifyEventReminders(boolean v) { this.notifyEventReminders = v; }
    public boolean isNotifyWorkflowUpdates() { return notifyWorkflowUpdates; }
    public void setNotifyWorkflowUpdates(boolean v) { this.notifyWorkflowUpdates = v; }
    public String getTimezone() { return timezone; }
    public void setTimezone(String timezone) { this.timezone = timezone; }
    public String getFirmName() { return firmName; }
    public void setFirmName(String firmName) { this.firmName = firmName; }
    public String getInvestorTitle() { return investorTitle; }
    public void setInvestorTitle(String investorTitle) { this.investorTitle = investorTitle; }
}
