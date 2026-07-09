package com.pm.settings;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/settings")
public class SettingsController {

    private final AppSettingsRepository repo;

    public SettingsController(AppSettingsRepository repo) {
        this.repo = repo;
    }

    @GetMapping
    public AppSettingsDto get() {
        return repo.findById(1)
                .map(s -> new AppSettingsDto(s.getJavaHome(), s.getNodeHome()))
                .orElse(new AppSettingsDto(null, null));
    }

    @PutMapping
    public AppSettingsDto put(@RequestBody AppSettingsDto dto) {
        AppSettings s = repo.findById(1).orElseGet(AppSettings::new);
        String jh = dto.javaHome();
        s.setJavaHome(jh == null || jh.isBlank() ? null : jh.trim());
        String nh = dto.nodeHome();
        s.setNodeHome(nh == null || nh.isBlank() ? null : nh.trim());
        AppSettings saved = repo.save(s);
        return new AppSettingsDto(saved.getJavaHome(), saved.getNodeHome());
    }
}
