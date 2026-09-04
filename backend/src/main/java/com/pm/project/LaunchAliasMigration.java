package com.pm.project;

import com.pm.proxy.Slugs;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Backfills a stable {@code <alias>.localhost} slug (Rung 1) for every launch that predates the
 * feature. Idempotent: launches that already carry an alias are left untouched. Runs late so any
 * default launches created by {@link LegacyLaunchMigration} on the same boot are already present.
 */
@Slf4j
@Component
@Order(1000)
@RequiredArgsConstructor
public class LaunchAliasMigration implements ApplicationRunner {

    private final LaunchRepository launchRepo;
    private final ProjectRepository projectRepo;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        List<Launch> all = launchRepo.findAll();
        boolean anyMissing = all.stream().anyMatch(l -> l.getAlias() == null || l.getAlias().isBlank());
        if (!anyMissing) return;

        Map<String, String> projectNames = projectRepo.findAll().stream()
                .collect(Collectors.toMap(Project::getId, Project::getName, (a, b) -> a));
        Map<String, Long> launchCounts = all.stream()
                .collect(Collectors.groupingBy(Launch::getProjectId, Collectors.counting()));
        Set<String> taken = all.stream()
                .map(Launch::getAlias)
                .filter(a -> a != null && !a.isBlank())
                .map(a -> a.toLowerCase(Locale.ROOT))
                .collect(Collectors.toCollection(HashSet::new));

        int assigned = 0;
        for (Launch l : all) {
            if (l.getAlias() != null && !l.getAlias().isBlank()) continue;
            String projectName = projectNames.getOrDefault(l.getProjectId(), l.getName());
            long count = launchCounts.getOrDefault(l.getProjectId(), 1L);
            String base = count == 1 ? projectName : projectName + "-" + l.getName();
            String fallback = "launch-" + l.getId().substring(0, Math.min(8, l.getId().length()));
            String slug = Slugs.uniqueSlug(base, fallback, taken);
            l.setAlias(slug);
            taken.add(slug.toLowerCase(Locale.ROOT));
            launchRepo.save(l);
            assigned++;
        }
        if (assigned > 0) log.info("Assigned <alias>.localhost names to {} existing launch(es)", assigned);
    }
}
