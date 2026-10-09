package com.pitsch.backend.auth;

/** The public shape of a user. {@code role} is the role in the active workspace (null if none). */
public record UserView(Long id, String name, String email, String avatarUrl, String role, boolean emailVerified) {

    public static UserView of(User u, String role) {
        return new UserView(u.getId(), u.getName(), u.getEmail(), null, role, u.isEmailVerified());
    }
}
