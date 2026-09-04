package com.pm;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class ProjectManagementApplication {
    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(ProjectManagementApplication.class);
        // Tray mode needs AWT; Spring Boot forces headless=true by default.
        for (String arg : args) {
            if (arg.equals("--pm.tray.enabled=true")) {
                app.setHeadless(false);
                break;
            }
        }
        app.run(args);
    }
}
