package com.smartsplitpro.controller;

import com.smartsplitpro.model.User;
import com.smartsplitpro.repository.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.dao.DataIntegrityViolationException;

@Controller
public class AuthController {
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;

    public AuthController(UserRepository userRepository, PasswordEncoder passwordEncoder, AuthenticationManager authenticationManager) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.authenticationManager = authenticationManager;
    }

    @GetMapping("/login")
    public String login() {
        return "login";
    }

    @GetMapping("/register")
    public String registerForm(Model model) {
        model.addAttribute("user", new User());
        return "register";
    }

    @PostMapping("/register")
    public String register(@ModelAttribute User user, Model model) {
        // Basic check if username exists
        if (user.getUsername() == null || user.getUsername().isBlank() ||
                user.getPassword() == null || user.getPassword().isBlank() ||
                user.getPhoneNumber() == null || user.getPhoneNumber().isBlank()) {
            model.addAttribute("error", "Username, password, and phone number are required");
            return "register";
        }
        // Normalize username casing and trim to prevent dashboard mismatch
        String normalized = user.getUsername().trim().toLowerCase();
        user.setUsername(normalized);
        if (user.getDisplayName() == null || user.getDisplayName().isBlank()) {
            user.setDisplayName(normalized);
        } else {
            user.setDisplayName(user.getDisplayName().trim());
        }
        if (userRepository.findByUsernameIgnoreCase(normalized).isPresent()) {
            model.addAttribute("error", "Username already exists");
            return "register";
        }

        // Basic phone normalization (strip leading/trailing whitespace)
        user.setPhoneNumber(user.getPhoneNumber().trim());

        // Capture raw password for immediate authentication after save
        String rawPassword = user.getPassword();
        user.setPassword(passwordEncoder.encode(rawPassword));
        if (user.getDisplayName() == null || user.getDisplayName().isBlank()) user.setDisplayName(user.getUsername());
        user.setRole("ROLE_USER");
        try {
            userRepository.save(user);
        } catch (DataIntegrityViolationException ex) {
            // Unique constraint failed — username already exists (race or placeholder user)
            model.addAttribute("error", "Username already exists — please choose another username");
            return "register";
        }

        // After successful registration, attempt to authenticate the user immediately so they
        // don't have to log in manually.
        try {
            Authentication auth = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(normalized, rawPassword)
            );
            SecurityContextHolder.getContext().setAuthentication(auth);
            // Redirect to root (dashboard) after auto-login
            return "redirect:/";
        } catch (Exception e) {
            // If auto-login fails for any reason, fall back to showing the login page.
            return "redirect:/login";
        }
    }
}
