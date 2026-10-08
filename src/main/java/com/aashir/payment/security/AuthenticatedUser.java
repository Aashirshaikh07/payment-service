package com.aashir.payment.security;

public class AuthenticatedUser {

    private final Long userId;
    private final String email;
    private final String role;

    public AuthenticatedUser(Long userId, String email, String role) {
        this.userId = userId;
        this.email = email;
        this.role = role;
    }

    public Long getUserId() {
        return userId;
    }


    public String getRole() {
        return role;
    }

    @Override
    public String toString() {
        return "AuthenticatedUser{" +
                "userId=" + userId +
                ", email='" + email + '\'' +
                ", role='" + role + '\'' +
                '}';
    }
}