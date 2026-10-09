package com.pitsch.backend.org;

import java.util.EnumSet;
import java.util.Set;

import com.pitsch.backend.auth.Permission;

import static com.pitsch.backend.auth.Permission.*;

/** Workspace roles. Permissions are enforced on the server for every endpoint (see RequiresPermission). */
public enum Role {
    OWNER(EnumSet.allOf(Permission.class)),
    ADMIN(EnumSet.complementOf(EnumSet.of(ORG_DELETE, BILLING_MANAGE))),
    INVESTOR(EnumSet.of(PITCH_READ, PITCH_WRITE, PITCH_DELETE, EMAIL_IMPORT, WORKFLOW_RUN, ACTION_APPROVE,
            CALENDAR_READ, CALENDAR_WRITE, TASK_READ, TASK_WRITE, INTEGRATION_CONNECT, MEMBER_READ, USAGE_READ)),
    ANALYST(EnumSet.of(PITCH_READ, PITCH_WRITE, EMAIL_IMPORT, WORKFLOW_RUN, CALENDAR_READ, TASK_READ, TASK_WRITE,
            MEMBER_READ)),
    MEMBER(EnumSet.of(PITCH_READ, CALENDAR_READ, TASK_READ, TASK_WRITE, MEMBER_READ));

    private final Set<Permission> permissions;

    Role(Set<Permission> permissions) {
        this.permissions = Set.copyOf(permissions);
    }

    public Set<Permission> permissions() {
        return permissions;
    }

    public boolean has(Permission permission) {
        return permissions.contains(permission);
    }
}
