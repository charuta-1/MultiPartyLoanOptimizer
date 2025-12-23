package com.smartsplitpro.model;

import jakarta.persistence.*;
import jakarta.persistence.Table;
import java.util.Objects;

@Entity
@Table(name = "app_user")
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String username;

    private String displayName;
    
    @Column(nullable = false)
    private String password;

    @Column(length = 32)
    private String phoneNumber;

    // Simple role model (comma-separated or single role for scaffold)
    private String role = "ROLE_USER";

    // Constructors
    public User() {}

    public User(String username, String displayName) {
        this.username = username;
        this.displayName = displayName;
        this.phoneNumber = null;
    }

    public User(String username, String displayName, String password, String role) {
        this.username = username;
        this.displayName = displayName;
        this.password = password;
        this.role = role;
        this.phoneNumber = null;
    }

    // Getters and setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
    public String getPhoneNumber() { return phoneNumber; }
    public void setPhoneNumber(String phoneNumber) { this.phoneNumber = phoneNumber; }
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof User)) return false;
        User user = (User) o;
        return Objects.equals(id, user.id);
    }

    @Override
    public int hashCode() { return Objects.hash(id); }
}
