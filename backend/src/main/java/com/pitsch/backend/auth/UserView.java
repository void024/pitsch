package com.pitsch.backend.auth;

/** What the frontend's `User` type expects. */
public record UserView(Long id, String name, String email, String avatarUrl, String role) {

    public static UserView of(User u) {
        return new UserView(u.getId(), u.getName(), u.getEmail(), null, u.getRole());
    }
}
