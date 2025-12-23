package com.smartsplitpro;

import com.smartsplitpro.model.User;
import com.smartsplitpro.repository.UserRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.password.PasswordEncoder;

@SpringBootApplication
public class SmartSplitProApplication {
    
    private final Environment environment;
    
    public SmartSplitProApplication(Environment environment) {
        this.environment = environment;
    }
    
    public static void main(String[] args) {
        SpringApplication.run(SmartSplitProApplication.class, args);
    }
    
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        String port = environment.getProperty("server.port", "8080");
        String contextPath = environment.getProperty("server.servlet.context-path", "");
        
        System.out.println("\n" + "=".repeat(80));
        System.out.println("🚀 SmartSplitPro Application Started Successfully!");
        System.out.println("=".repeat(80));
        System.out.println("📱 Application URL: http://localhost:" + port + contextPath);
        System.out.println("🔐 Default Login: admin / password");
        System.out.println("💾 H2 Console: http://localhost:" + port + "/h2-console");
        System.out.println("=".repeat(80) + "\n");
    }

    // Create a default test user on startup if none exists. This helps troubleshooting the UI
    // so you can quickly log in and verify dashboard charts and graphs.
    @Bean
    public CommandLineRunner createDefaultUser(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        return args -> {
            String testUser = "admin";
            if (userRepository.findByUsername(testUser).isEmpty()) {
                User u = new User();
                u.setUsername(testUser);
                u.setDisplayName("Administrator");
                u.setPassword(passwordEncoder.encode("password"));
                u.setRole("ROLE_USER");
                userRepository.save(u);
                System.out.println("Created default user 'admin' with password 'password'");
            }
        };
    }
}
