package com.example.aiplatform.model;

/**
 * Declared in increasing order of privilege - USER.ordinal() &lt; SUPPORT_AGENT.ordinal()
 * &lt; ADMIN.ordinal() - so staff-override checks (e.g. "can this caller access
 * any customer's data, not just their own") can compare ordinals directly
 * instead of hardcoding a separate hierarchy table for three roles.
 */
public enum Role {
    USER,
    SUPPORT_AGENT,
    ADMIN
}
