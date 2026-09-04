package com.pm.proxy;

import com.pm.process.PortUtils;
import com.pm.process.ProcessSupervisor;
import com.pm.project.Launch;
import com.pm.project.LaunchRepository;
import com.pm.project.Project;
import com.pm.project.ProjectRepository;
import com.pm.project.ProjectStatus;
import com.pm.project.Reach;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Resolves an {@code <alias>.localhost} host to the TCP port its launch is currently
 * listening on. The mapping is computed live on every request so it always follows the
 * newest server, whatever port an agent grabbed this time.
 */
@Service
@RequiredArgsConstructor
public class RouteRegistry {

    private final LaunchRepository launchRepo;
    private final ProjectRepository projectRepo;
    private final ProcessSupervisor supervisor;
    private final PortRoleResolver roleResolver;

    /** One row of the localhost index page. */
    public record Route(String projectName, String launchName, String alias,
                        ProjectStatus status, Integer port) {}

    /** Resolve an alias to the port its launch is currently reachable on, if any. */
    public Optional<Integer> resolvePort(String alias) {
        if (alias == null || alias.isBlank()) return Optional.empty();
        return launchRepo.findByAliasIgnoreCase(alias.trim()).flatMap(this::activePort);
    }

    /** The configured reach (LOCAL/WIFI/INTERNET) of the launch owning this alias, if any. */
    public Optional<Reach> reachOf(String alias) {
        if (alias == null || alias.isBlank()) return Optional.empty();
        return launchRepo.findByAliasIgnoreCase(alias.trim())
                .map(l -> l.getReach() != null ? l.getReach() : Reach.LOCAL);
    }

    /** Aliases whose launch is shared beyond localhost (reach WIFI or INTERNET). */
    public Set<String> sharedAliases() {
        return launchRepo.findAll().stream()
                .filter(l -> l.getAlias() != null && !l.getAlias().isBlank())
                .filter(l -> l.getReach() == Reach.WIFI || l.getReach() == Reach.INTERNET)
                .map(Launch::getAlias)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /** True when at least one launch is shared beyond localhost — the proxy should bind the LAN. */
    public boolean anySharedLan() {
        return launchRepo.findAll().stream()
                .anyMatch(l -> l.getReach() == Reach.WIFI || l.getReach() == Reach.INTERNET);
    }

    /** The port a launch is reachable on right now: the web-UI port among everything it's listening on. */
    private Optional<Integer> activePort(Launch l) {
        ProjectStatus status = supervisor.statusOf(l.getId());
        List<Integer> declared = l.getPorts() != null ? l.getPorts() : List.of();

        // Gather every port the launch is currently listening on: detected process-tree ports
        // (running/attached) plus any declared port that happens to be up (covers EXTERNAL).
        LinkedHashSet<Integer> candidates = new LinkedHashSet<>();
        if (status == ProjectStatus.RUNNING || status == ProjectStatus.ATTACHED) {
            candidates.addAll(supervisor.detectListeningPorts(l.getId()));
        }
        for (Integer p : declared) {
            if (p != null && PortUtils.isListening(p)) candidates.add(p);
        }
        if (candidates.isEmpty()) return Optional.empty();

        List<Integer> list = new ArrayList<>(candidates);
        if (list.size() == 1) return Optional.of(list.get(0));
        // Multiple servers behind one launch (e.g. frontend + API): probe for the browser UI.
        return Optional.of(roleResolver.chooseWebPort(l.getId(), list));
    }

    /** All launches that carry an alias, with their current status and resolved port, for the index page. */
    public List<Route> list() {
        Map<String, String> projectNames = projectRepo.findAll().stream()
                .collect(Collectors.toMap(Project::getId, Project::getName, (a, b) -> a));
        return launchRepo.findAll().stream()
                .filter(l -> l.getAlias() != null && !l.getAlias().isBlank())
                .map(l -> new Route(
                        projectNames.getOrDefault(l.getProjectId(), "?"),
                        l.getName(),
                        l.getAlias(),
                        supervisor.statusOf(l.getId()),
                        activePort(l).orElse(null)))
                .sorted(java.util.Comparator
                        .comparing(Route::projectName, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(Route::alias, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }
}
