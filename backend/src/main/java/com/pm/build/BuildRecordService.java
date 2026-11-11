package com.pm.build;

import com.pm.git.GitService;
import com.pm.git.HeadInfo;
import com.pm.project.Launch;
import com.pm.project.Project;
import com.pm.project.ProjectCommand;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/** Records runs of build commands (those linked to a launch) and judges whether a launch's build is out of date. */
@Slf4j
@Service
@RequiredArgsConstructor
public class BuildRecordService {

    static final int HISTORY_LIMIT = 20;
    private static final int SHORT_SHA = 7;
    /** A RUNNING record younger than this is trusted even if its process isn't visible yet (it is created just before the process starts). */
    private static final Duration STALE_RUNNING_GRACE = Duration.ofSeconds(15);

    private final BuildRecordRepository repo;
    private final GitService gitService;
    private final PlatformTransactionManager txManager;

    /** Inserts a RUNNING record stamped with the current HEAD and returns its id. */
    public String begin(Project project, ProjectCommand command) {
        Optional<HeadInfo> head = gitService.readHead(project);
        BuildRecord r = BuildRecord.builder()
                .id(UUID.randomUUID().toString())
                .projectId(project.getId())
                .launchId(command.getBuildForLaunchId())
                .commandId(command.getId())
                .commandName(command.getName())
                .commitSha(head.map(HeadInfo::sha).orElse(null))
                .commitSubject(head.map(HeadInfo::subject).orElse(null))
                .branch(head.map(HeadInfo::branch).orElse(null))
                .status(BuildStatus.RUNNING)
                .startedAt(Instant.now())
                .build();
        return repo.save(r).getId();
    }

    /** Completes a record and trims the launch's history. Never throws: recording must not break the build itself. */
    public void finish(String recordId, BuildStatus status, Integer exitCode) {
        try {
            repo.findById(recordId).ifPresent(r -> {
                r.setStatus(status);
                r.setExitCode(exitCode);
                r.setFinishedAt(Instant.now());
                repo.save(r);
                prune(r.getLaunchId());
            });
        } catch (Exception e) {
            log.error("Could not finish build record {} as {}: {}", recordId, status, e.toString());
        }
    }

    /** Keeps the newest {@value #HISTORY_LIMIT} records per launch (plus each command's latest success, which freshness relies on). */
    private void prune(String launchId) {
        List<BuildRecord> all = repo.findByLaunchIdOrderByStartedAtDesc(launchId);
        if (all.size() <= HISTORY_LIMIT) return;
        Set<String> latestSuccessIds = new HashSet<>();
        Set<String> seenCommands = new HashSet<>();
        for (BuildRecord r : all) {
            if (r.getStatus() == BuildStatus.SUCCESS && seenCommands.add(r.getCommandId())) {
                latestSuccessIds.add(r.getId());
            }
        }
        List<BuildRecord> doomed = new ArrayList<>();
        for (BuildRecord r : all.subList(HISTORY_LIMIT, all.size())) {
            if (!latestSuccessIds.contains(r.getId())) doomed.add(r);
        }
        if (!doomed.isEmpty()) repo.deleteAll(doomed);
    }

    /** A PM restart orphans any build that was running; mark those as interrupted instead of "building" forever. */
    @EventListener(ApplicationReadyEvent.class)
    public void markOrphanedBuildsInterrupted() {
        List<BuildRecord> running = repo.findByStatus(BuildStatus.RUNNING);
        if (running.isEmpty()) return;
        Instant now = Instant.now();
        for (BuildRecord r : running) {
            r.setStatus(BuildStatus.INTERRUPTED);
            r.setFinishedAt(now);
        }
        repo.saveAll(running);
        log.info("Marked {} orphaned build record(s) as INTERRUPTED", running.size());
    }

    @Transactional
    public void deleteByLaunchId(String launchId) {
        repo.deleteByLaunchId(launchId);
    }

    @Transactional
    public void deleteByProjectId(String projectId) {
        repo.deleteByProjectId(projectId);
    }

    public List<BuildRecordDto> history(String launchId) {
        return repo.findTop20ByLaunchIdOrderByStartedAtDesc(launchId).stream().map(BuildRecordDto::from).toList();
    }

    /**
     * Freshness per launch id, only for launches that have at least one build command. Empty when the
     * project has no build commands or its HEAD cannot be read (not a git repo).
     * {@code commandRunning} says whether a command's process is really alive; a RUNNING record whose
     * command is not running (e.g. its completion could not be saved) is healed instead of showing "Building…" forever.
     */
    public Map<String, BuildFreshnessDto> freshness(Project project, List<Launch> launches,
                                                    List<ProjectCommand> commands,
                                                    Predicate<String> commandRunning) {
        List<ProjectCommand> buildCommands = commands.stream()
                .filter(c -> c.getBuildForLaunchId() != null)
                .toList();
        if (buildCommands.isEmpty()) return Map.of();
        Optional<HeadInfo> head = gitService.readHead(project);
        if (head.isEmpty()) return Map.of();

        Map<String, BuildFreshnessDto> result = new HashMap<>();
        for (Launch launch : launches) {
            List<ProjectCommand> forLaunch = buildCommands.stream()
                    .filter(c -> launch.getId().equals(c.getBuildForLaunchId()))
                    .toList();
            if (!forLaunch.isEmpty()) {
                result.put(launch.getId(), judge(project, launch.getId(), forLaunch, head.get(), commandRunning));
            }
        }
        return result;
    }

    private BuildFreshnessDto judge(Project project, String launchId, List<ProjectCommand> commands, HeadInfo head,
                                    Predicate<String> commandRunning) {
        boolean building = false;
        boolean missing = false;
        List<BuildRecord> lastSuccesses = new ArrayList<>();
        for (ProjectCommand c : commands) {
            if (isBuilding(c, launchId, commandRunning)) {
                building = true;
            }
            Optional<BuildRecord> ok = repo.findFirstByCommandIdAndLaunchIdAndStatusOrderByStartedAtDesc(
                    c.getId(), launchId, BuildStatus.SUCCESS);
            // A success without a commit (HEAD was unreadable then) gives no baseline to compare against.
            if (ok.isPresent() && ok.get().getCommitSha() != null) {
                lastSuccesses.add(ok.get());
            } else {
                missing = true;
            }
        }

        BuildFreshnessDto dto = new BuildFreshnessDto();
        dto.headSha = abbreviate(head.sha());
        repo.findFirstByLaunchIdOrderByStartedAtDesc(launchId).ifPresent(r -> dto.lastResult = r.getStatus().name());
        lastSuccesses.stream().min(Comparator.comparing(BuildRecord::getStartedAt)).ifPresent(oldest -> {
            dto.builtSha = abbreviate(oldest.getCommitSha());
            dto.builtSubject = oldest.getCommitSubject();
            dto.builtAt = oldest.getFinishedAt() != null ? oldest.getFinishedAt() : oldest.getStartedAt();
        });

        if (building) {
            dto.state = BuildFreshnessDto.BUILDING;
        } else if (missing) {
            dto.state = BuildFreshnessDto.NO_RECORD;
        } else {
            boolean stale = false;
            boolean diverged = false;
            int maxBehind = 0;
            for (BuildRecord r : lastSuccesses) {
                if (r.getCommitSha().equals(head.sha())) continue;
                stale = true;
                int behind = gitService.countCommitsBetween(project, r.getCommitSha(), head.sha());
                if (behind < 0) diverged = true;
                else maxBehind = Math.max(maxBehind, behind);
            }
            if (stale) {
                dto.state = BuildFreshnessDto.STALE;
                dto.commitsBehind = diverged ? -1 : maxBehind;
            } else {
                dto.state = BuildFreshnessDto.FRESH;
            }
        }
        return dto;
    }

    /** True when the command has a RUNNING record backed by a live process; orphaned RUNNING records are marked INTERRUPTED. */
    private boolean isBuilding(ProjectCommand c, String launchId, Predicate<String> commandRunning) {
        List<BuildRecord> running = repo.findByCommandIdAndLaunchIdAndStatus(c.getId(), launchId, BuildStatus.RUNNING);
        if (running.isEmpty()) return false;
        if (commandRunning.test(c.getId())) return true;
        boolean building = false;
        Instant cutoff = Instant.now().minus(STALE_RUNNING_GRACE);
        for (BuildRecord r : running) {
            if (r.getStartedAt().isAfter(cutoff)) {
                building = true;
            } else {
                markInterrupted(r.getId());
            }
        }
        return building;
    }

    /** Own transaction: the caller may be a read-only request that would never flush this write. */
    private void markInterrupted(String recordId) {
        try {
            TransactionTemplate tx = new TransactionTemplate(txManager);
            tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            tx.executeWithoutResult(s -> repo.findById(recordId).ifPresent(r -> {
                if (r.getStatus() != BuildStatus.RUNNING) return;
                r.setStatus(BuildStatus.INTERRUPTED);
                r.setFinishedAt(Instant.now());
                repo.save(r);
                log.warn("Build record {} was RUNNING but its command is not; marked INTERRUPTED", recordId);
            }));
        } catch (Exception e) {
            log.error("Could not mark orphaned build record {} as INTERRUPTED: {}", recordId, e.toString());
        }
    }

    private static String abbreviate(String sha) {
        return sha == null ? null : sha.substring(0, Math.min(SHORT_SHA, sha.length()));
    }
}
