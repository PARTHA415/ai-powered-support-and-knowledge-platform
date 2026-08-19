package com.example.aiplatform.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * customerId is nullable and only meaningful for USER-role accounts - a
 * support agent or admin isn't a customer, and tool-level ownership checks
 * (see SupportTools) never consult it for those roles in the first place.
 */
@Entity
@Table(name = "app_user")
public class AppUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 100)
    private String username;

    @Column(name = "password_hash", nullable = false, length = 200)
    private String passwordHash;

    @Column(name = "customer_id", length = 50)
    private String customerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private Role role;

    protected AppUser() {
    }

    public AppUser(String username, String passwordHash, String customerId, Role role) {
        this.username = username;
        this.passwordHash = passwordHash;
        this.customerId = customerId;
        this.role = role;
    }

    public Long getId() {
        return id;
    }

    public String getUsername() {
        return username;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public String getCustomerId() {
        return customerId;
    }

    public Role getRole() {
        return role;
    }
}
