package com.ownding.video.auth;

public record AuthContext(long userId, String username, String role, String token) {
}
