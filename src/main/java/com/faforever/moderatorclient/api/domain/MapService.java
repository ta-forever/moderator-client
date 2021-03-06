package com.faforever.moderatorclient.api.domain;

import com.faforever.commons.api.dto.*;
import com.faforever.commons.api.elide.ElideNavigator;
import com.faforever.commons.api.elide.ElideNavigatorOnCollection;
import com.faforever.moderatorclient.api.FafApiCommunicationService;
import com.faforever.moderatorclient.config.GithubGeneratorRelease;
import com.faforever.moderatorclient.mapstruct.MapPoolAssignmentMapper;
import com.faforever.moderatorclient.mapstruct.MapPoolMapper;
import com.faforever.moderatorclient.mapstruct.MapVersionMapper;
import com.faforever.moderatorclient.mapstruct.MatchmakerQueueMapPoolMapper;
import com.faforever.moderatorclient.ui.domain.MapPoolAssignmentFX;
import com.faforever.moderatorclient.ui.domain.MapPoolFX;
import com.faforever.moderatorclient.ui.domain.MapVersionFX;
import com.faforever.moderatorclient.ui.domain.MatchmakerQueueMapPoolFX;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.maven.artifact.versioning.ComparableVersion;
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class MapService {
    private final FafApiCommunicationService fafApi;
    private final MapVersionMapper mapVersionMapper;
    private final MapPoolMapper mapPoolMapper;
    private final MapPoolAssignmentMapper mapPoolAssignmentMapper;
    private final MatchmakerQueueMapPoolMapper matchmakerQueueMapPoolMapper;

    @Value("${faforever.map-generator.queryVersionsUrl}")
    private String generatorVersionsURL;
    @Value("${faforever.map-generator.minSupportedVersion}")
    private String minGeneratorVersion;

    private List<Map> findMapsByAttribute(@NotNull String attribute, @NotNull String pattern, boolean excludeHidden) {
        log.debug("Searching for maps by attribute '{}' with pattern: {}", attribute, pattern);
        ElideNavigatorOnCollection<MapVersion> routeBuilder = ElideNavigator.of(MapVersion.class)
                .collection()
                .addIncludeOnCollection("map")
                .addIncludeOnCollection("map.author");

        if (excludeHidden) {
            routeBuilder.addFilter(ElideNavigator.qBuilder().string("map." + attribute).eq(pattern)
                    .and().bool("hidden").isFalse());
        } else {
            routeBuilder.addFilter(ElideNavigator.qBuilder().string("map." + attribute).eq(pattern));
        }

        List<Map> result = fafApi.getAll(MapVersion.class, routeBuilder).stream()
                .map(MapVersion::getMap)
                .distinct()
                .collect(Collectors.toList());

        // filter empty mapVersions that were created by map relationships
        for (Map map : result) {
            map.getVersions().removeIf(mapVersion -> mapVersion.getMap() == null);
        }

        log.trace("found {} maps", result.size());
        return result;
    }

    public List<Map> findMapsByName(@NotNull String pattern, boolean excludeHidden) {
        return findMapsByAttribute("displayName", pattern, excludeHidden);
    }

    public List<Map> findMapsByAuthorId(@NotNull String pattern, boolean excludeHidden) {
        return findMapsByAttribute("author.id", pattern, excludeHidden);
    }

    public List<Map> findMapsByAuthorName(@NotNull String pattern, boolean excludeHidden) {
        return findMapsByAttribute("author.login", pattern, excludeHidden);
    }

    public List<Map> findMaps(String mapNamePattern) {
        log.debug("Searching for maps with pattern: {}", mapNamePattern);
        ElideNavigatorOnCollection<Map> routeBuilder = ElideNavigator.of(Map.class)
                .collection()
                .addIncludeOnCollection("versions");

        if (mapNamePattern != null && mapNamePattern.length() > 0) {
            routeBuilder.addFilter(ElideNavigator.qBuilder().string("displayName").eq(mapNamePattern));
        }

        List<Map> result = fafApi.getAll(Map.class, routeBuilder);
        log.trace("found {} maps", result.size());
        return result;
    }

    public List<MatchmakerQueue> getAllMatchmakerQueues() {
        log.debug("Searching for all matchmaker queues");
        ElideNavigatorOnCollection<MatchmakerQueue> routeBuilder = ElideNavigator.of(MatchmakerQueue.class)
                .collection();
        List<MatchmakerQueue> queues = fafApi.getAll(MatchmakerQueue.class, routeBuilder);
        log.debug("found {} matchmaker queues", queues.size());
        return queues;
    }

    public List<MatchmakerQueueMapPool> getListOfBracketsInMatchmakerQueue(MatchmakerQueue queue) {
        log.debug("Searching for all brackets in queue {}", queue.getId());
        ElideNavigatorOnCollection<MatchmakerQueueMapPool> routeBuilder = ElideNavigator.of(MatchmakerQueueMapPool.class)
                .collection()
                .addFilter(ElideNavigator.qBuilder().string("matchmakerQueue.id").eq(queue.getId()));
        List<MatchmakerQueueMapPool> brackets = fafApi.getAll(MatchmakerQueueMapPool.class, routeBuilder);
        for (MatchmakerQueueMapPool bracket : brackets) {
            log.info("{}", bracket);
        }
        return brackets;
    }

    public List<MapPoolAssignment> getListOfMapsInBrackets(List<MatchmakerQueueMapPool> brackets) {
        List<String> poolIDs = brackets.stream()
                .map(bracket -> bracket.getMapPool().getId())
                .collect(Collectors.toList());
        log.debug("Searching for all maps in pools {}", String.join(", ", poolIDs));
        ElideNavigatorOnCollection<MapPoolAssignment> routeBuilder = ElideNavigator.of(MapPoolAssignment.class)
                .collection()
                .addFilter(ElideNavigator.qBuilder().string("mapPool.id").in(poolIDs))
                .addIncludeOnCollection("mapVersion")
                .addIncludeOnCollection("mapVersion.map")
                .addSortingRule("mapVersion.width", false)
                .addSortingRule("mapVersion.map.displayName", false);
        List<MapPoolAssignment> mapAssignments = fafApi.getAll(MapPoolAssignment.class, routeBuilder);
        for (MapPoolAssignment poolAssignment : mapAssignments) {
            log.debug("Received from api {}", poolAssignment);
        }
        return mapAssignments;
    }

    public void postMapPoolAssignments(List<MapPoolAssignment> mapPoolAssignments) {
        mapPoolAssignments.forEach(mapPoolAssignment -> {
            log.debug("Creating new mapPoolAssignment for pool: {}", mapPoolAssignment.getMapPool().getId());
            fafApi.post(ElideNavigator.of(MapPoolAssignment.class).collection(), mapPoolAssignment);
        });
    }

    public void patchMapPoolAssignments(List<MapPoolAssignment> mapPoolAssignments) {
        mapPoolAssignments.forEach(mapPoolAssignment -> {
            log.debug("Updating mapPoolAssignment id: {}", mapPoolAssignment.getId());
            fafApi.patch(ElideNavigator.of(mapPoolAssignment), mapPoolAssignment);
        });
    }

    public void deleteMapPoolAssignments(List<MapPoolAssignment> mapPoolAssignments) {
        mapPoolAssignments.forEach(mapPoolAssignment -> {
            log.debug("Deleting mapPoolAssignment id: {}", mapPoolAssignment.getId());
            fafApi.delete(ElideNavigator.of(mapPoolAssignment));
        });
    }


    public void patchBracket(MatchmakerQueueMapPoolFX bracketFX) {
        log.debug("Updating matchmakerQueueMapPool (bracket) id: {}", bracketFX.getId());
        var bracket = matchmakerQueueMapPoolMapper.mapToDto(bracketFX);
        fafApi.patch(ElideNavigator.of(bracket),
                (MatchmakerQueueMapPool) new MatchmakerQueueMapPool()
                        .setMapPool(bracket.getMapPool())
                        .setMaxRating(bracket.getMaxRating())
                        .setMinRating(bracket.getMinRating())
                        .setId(bracket.getId()));
    }

    public void patchMapVersion(MapVersionFX mapVersionFX) {
        patchMapVersion(mapVersionMapper.map(mapVersionFX));
    }

    public void patchMapVersion(MapVersion mapVersion) {
        log.debug("Updating mapVersion id: {}", mapVersion.getId());
        fafApi.patch(ElideNavigator.of(mapVersion),
                (MapVersion) new MapVersion()
                        .setHidden(mapVersion.isHidden())
                        .setRanked(mapVersion.isRanked())
                        .setId(mapVersion.getId()
                        ));
    }

    public boolean doesMapVersionExist(int id) {
        log.debug("Requesting Mapversion with id: {}", id);
        return !fafApi.getAll(MapVersion.class, ElideNavigator.of(MapVersion.class)
                .collection()
                .addFilter(ElideNavigator.qBuilder().string("id").eq(String.valueOf(id))))
                .isEmpty();
    }

    public List<MapVersionFX> findLatestMapVersions() {
        log.debug("Searching for latest mapVersions ");
        ElideNavigatorOnCollection<MapVersion> navigator = ElideNavigator.of(MapVersion.class)
                .collection()
                .addIncludeOnCollection("map")
                .addIncludeOnCollection("map.author")
                .addSortingRule("id", false);

        List<MapVersion> result = fafApi.getPage(MapVersion.class, navigator, 50, 1, Collections.emptyMap());
        log.trace("found {} teamkills", result.size());
        return mapVersionMapper.mapToFX(result);
    }

    public List<ComparableVersion> getGeneratorVersions() {
        ComparableVersion minVersion = new ComparableVersion(minGeneratorVersion);
        RestTemplate restTemplate = new RestTemplate();

        LinkedMultiValueMap<String, String> headers = new LinkedMultiValueMap<>();
        headers.add("Accept", "application/vnd.github.v3+json");
        HttpEntity<String> entity = new HttpEntity<>(null, headers);

        ResponseEntity<List<GithubGeneratorRelease>> response = restTemplate.exchange(generatorVersionsURL, HttpMethod.GET, entity, new ParameterizedTypeReference<List<GithubGeneratorRelease>>() {});
        List<GithubGeneratorRelease> releases = response.getBody();
        if (releases != null) {
            return releases.stream()
                    .map(release -> new ComparableVersion(release.getTagName()))
                    .filter(version -> version.compareTo(minVersion) >= 0)
                    .collect(Collectors.toList());
        } else {
            return List.of();
        }
    }
}
