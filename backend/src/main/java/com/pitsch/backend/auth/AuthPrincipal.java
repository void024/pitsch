package com.pitsch.backend.auth;

import com.pitsch.backend.common.ApiException;
import com.pitsch.backend.common.ErrorCode;
import com.pitsch.backend.org.Role;

/**
 * Who is calling, resolved on the server for every request: user from the verified access token, session from the
 * database, workspace and role from the session's active membership. Never built from client-supplied IDs.
 */
public record AuthPrincipal(Long userId, String email, String name, String sessionId, Long organizationId, Role role,
                            boolean emailVerified) {

    public static final String REQUEST_ATTRIBUTE = "pitsch.principal";

    public boolean has(Permission permission) {
        return role != null && role.has(permission);
    }

    public void require(Permission permission) {
        if (!has(permission)) {
            throw new ApiException(ErrorCode.FORBIDDEN, "Your role does not allow this action.");
        }
    }

    /** The active workspace; fails if the user has none (only reachable on @AllowWithoutWorkspace endpoints). */
    public Long orgId() {
        if (organizationId == null) {
            throw new ApiException(ErrorCode.FORBIDDEN, "Create or join a workspace first.");
        }
        return organizationId;
    }
}
