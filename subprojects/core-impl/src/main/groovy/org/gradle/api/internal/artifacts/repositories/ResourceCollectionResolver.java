/*
 * Copyright 2011 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.gradle.api.internal.artifacts.repositories;

import org.apache.ivy.core.IvyPatternHelper;
import org.apache.ivy.core.event.EventManager;
import org.apache.ivy.core.module.descriptor.Artifact;
import org.apache.ivy.core.module.descriptor.DefaultArtifact;
import org.apache.ivy.core.module.descriptor.DependencyDescriptor;
import org.apache.ivy.core.module.descriptor.ModuleDescriptor;
import org.apache.ivy.core.module.id.ModuleRevisionId;
import org.apache.ivy.core.report.DownloadReport;
import org.apache.ivy.core.resolve.DownloadOptions;
import org.apache.ivy.core.resolve.ResolveData;
import org.apache.ivy.plugins.latest.ArtifactInfo;
import org.apache.ivy.plugins.repository.Resource;
import org.apache.ivy.plugins.resolver.BasicResolver;
import org.apache.ivy.plugins.resolver.util.MDResolvedResource;
import org.apache.ivy.plugins.resolver.util.ResolvedResource;
import org.apache.ivy.plugins.resolver.util.ResolverHelper;
import org.apache.ivy.plugins.resolver.util.ResourceMDParser;
import org.apache.ivy.plugins.version.VersionMatcher;
import org.apache.ivy.util.ChecksumHelper;
import org.apache.ivy.util.FileUtil;
import org.apache.ivy.util.Message;
import org.gradle.api.internal.artifacts.repositories.transport.ResourceCollection;
import org.gradle.api.internal.artifacts.repositories.transport.http.HttpResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Set;

public class ResourceCollectionResolver extends BasicResolver {
    private static final Logger LOGGER = LoggerFactory.getLogger(ResourceCollectionResolver.class);

    private List<String> ivyPatterns = new ArrayList<String>();
    private List<String> artifactPatterns = new ArrayList<String>();
    private boolean m2compatible = false;
    private final ResourceCollection repository;

    public ResourceCollectionResolver(String name, ResourceCollection repository) {
        setName(name);
        this.repository = repository;
    }

    protected ResourceCollection getRepository() {
        return repository;
    }

    public ResolvedResource findIvyFileRef(DependencyDescriptor dd, ResolveData data) {
        ModuleRevisionId mrid = dd.getDependencyRevisionId();
        if (isM2compatible()) {
            mrid = convertM2IdForResourceSearch(mrid);
        }
        return findResourceUsingPatterns(mrid, ivyPatterns, DefaultArtifact.newIvyArtifact(mrid,
            data.getDate()), getRMDParser(dd, data), data.getDate());
    }

    protected ResolvedResource findArtifactRef(Artifact artifact, Date date) {
        ModuleRevisionId mrid = artifact.getModuleRevisionId();
        if (isM2compatible()) {
            mrid = convertM2IdForResourceSearch(mrid);
        }
        return findResourceUsingPatterns(mrid, artifactPatterns, artifact,
            getDefaultRMDParser(artifact.getModuleRevisionId().getModuleId()), date);
    }

    protected ResolvedResource findResourceUsingPatterns(ModuleRevisionId moduleRevision,
            List patternList, Artifact artifact, ResourceMDParser rmdparser, Date date) {
        List<ResolvedResource> resolvedResources = new ArrayList<ResolvedResource>();
        Set<String> foundRevisions = new HashSet<String>();
        boolean dynamic = getSettings().getVersionMatcher().isDynamic(moduleRevision);
        boolean stop = false;
        for (Iterator iter = patternList.iterator(); iter.hasNext() && !stop;) {
            String pattern = (String) iter.next();
            ResolvedResource rres = findResourceUsingPattern(
                moduleRevision, pattern, artifact, rmdparser, date);
            if ((rres != null) && !foundRevisions.contains(rres.getRevision())) {
                // only add the first found ResolvedResource for each revision
                foundRevisions.add(rres.getRevision());
                resolvedResources.add(rres);
                stop = !dynamic; // stop iterating if we are not searching a dynamic revision
            }
        }

        if (resolvedResources.size() > 1) {
            ResolvedResource[] rress = resolvedResources.toArray(new ResolvedResource[resolvedResources.size()]);
            List<ResolvedResource> sortedResources = getLatestStrategy().sort(rress);
            return sortedResources.get(sortedResources.size() - 1);
        } else if (resolvedResources.size() == 1) {
            return resolvedResources.get(0);
        } else {
            return null;
        }
    }

    public ResolvedResource findLatestResource(ModuleRevisionId mrid, String[] versions, ResourceMDParser rmdparser, Date date, String pattern, Artifact artifact) throws IOException {
        String name = getName();
        VersionMatcher versionMatcher = getSettings().getVersionMatcher();

        ResolvedResource found = null;

        List<String> sorted = sortVersionsLatestFirst(versions);
        List<String> rejected = new ArrayList<String>();

        for (String version : sorted) {
            ModuleRevisionId foundMrid = ModuleRevisionId.newInstance(mrid, version);

            if (!versionMatcher.accept(mrid, foundMrid)) {
                Message.debug("\t" + name + ": rejected by version matcher: " + version);
                rejected.add(version);
                continue;
            }

            // Here we are initialising the resource: ensure we close it if we don't return it...
            String resourcePath = IvyPatternHelper.substitute(pattern, foundMrid, artifact);
            Resource resource = getResource(resourcePath, artifact);
            String description = version + " [" + resource + "]";
            if (!resource.exists()) {
                Message.debug("\t" + name + ": unreachable: " + description);
                rejected.add(version + " (unreachable)");
                cleanupResource(resource);
                continue;
            }
            if ((date != null && resource.getLastModified() > date.getTime())) {
                Message.verbose("\t" + name + ": too young: " + description);
                rejected.add(version + " (" + resource.getLastModified() + ")");
                cleanupResource(resource);
                continue;
            }
            if (versionMatcher.needModuleDescriptor(mrid, foundMrid)) {
                MDResolvedResource parsedResource = rmdparser.parse(resource, version);
                if (parsedResource == null) {
                    Message.debug("\t" + name + ": impossible to get module descriptor resource: " + description);
                    rejected.add(version + " (no or bad MD)");
                    cleanupResource(resource);
                    continue;
                }
                ModuleDescriptor md = parsedResource.getResolvedModuleRevision().getDescriptor();
                if (md.isDefault()) {
                    Message.debug("\t" + name + ": default md rejected by version matcher requiring module descriptor: " + description);
                    rejected.add(version + " (MD)");
                    cleanupResource(resource);
                    continue;
                } else if (!versionMatcher.accept(mrid, md)) {
                    Message.debug("\t" + name + ": md rejected by version matcher: " + description);
                    rejected.add(version + " (MD)");
                    cleanupResource(resource);
                    continue;
                }

                found = parsedResource;
                break;
            }
            found = new ResolvedResource(resource, version);
            break;
        }

        if (found == null && !rejected.isEmpty()) {
            logAttempt(rejected.toString());
        }

        return found;
    }

    public DownloadReport download(Artifact[] artifacts, DownloadOptions options) {
        EventManager eventManager = getEventManager();
        try {
            if (eventManager != null) {
                repository.addTransferListener(eventManager);
            }
            return super.download(artifacts, options);
        } finally {
            if (eventManager != null) {
                repository.removeTransferListener(eventManager);
            }
        }
    }

    protected ResolvedResource findResourceUsingPattern(ModuleRevisionId moduleRevisionId, String pattern, Artifact artifact, ResourceMDParser resourceParser, Date date) {
        String name = getName();
        VersionMatcher versionMatcher = getSettings().getVersionMatcher();
        try {
            if (!versionMatcher.isDynamic(moduleRevisionId)) {
                return findStaticResourceUsingPattern(moduleRevisionId, pattern, artifact);
            } else {
                return findDynamicResourceUsingPattern(resourceParser, moduleRevisionId, pattern, artifact, date);
            }
        } catch (IOException ex) {
            throw new RuntimeException(name + ": unable to get resource for " + moduleRevisionId + ": res="
                    + IvyPatternHelper.substitute(pattern, moduleRevisionId, artifact) + ": " + ex, ex);
        }
    }

    private ResolvedResource findStaticResourceUsingPattern(ModuleRevisionId moduleRevisionId, String pattern, Artifact artifact) throws IOException {
        String resourceName = IvyPatternHelper.substitute(pattern, moduleRevisionId, artifact);
        logAttempt(resourceName);

        LOGGER.debug("Loading {}", resourceName);
        Resource res = getResource(resourceName, artifact);
        if (res.exists()) {
            String revision = moduleRevisionId.getRevision();
            return new ResolvedResource(res, revision);
        } else {
            LOGGER.debug("Resource not reachable for {}: res={}", moduleRevisionId, res);
            return null;
        }
    }
    
    private ResolvedResource findDynamicResourceUsingPattern(ResourceMDParser resourceParser, ModuleRevisionId moduleRevisionId, String pattern, Artifact artifact, Date date) throws IOException {
        logAttempt(IvyPatternHelper.substitute(pattern, ModuleRevisionId.newInstance(moduleRevisionId, IvyPatternHelper.getTokenString(IvyPatternHelper.REVISION_KEY)), artifact));
        String[] versions = listVersions(moduleRevisionId, pattern, artifact);
        if (versions == null) {
            LOGGER.debug("Unable to list versions for {}: pattern={}", moduleRevisionId, pattern);
            return null;
        } else {
            ResolvedResource found = findLatestResource(moduleRevisionId, versions, resourceParser, date, pattern, artifact);
            if (found == null) {
                LOGGER.debug("No resource found for {}: pattern={}", moduleRevisionId, pattern);
            }
            return found;
        }
    }

    private void cleanupResource(Resource resource) {
        if (resource instanceof HttpResource) {
            ((HttpResource) resource).close();
        }
    }

    private List<String> sortVersionsLatestFirst(String[] versions) {
        ArtifactInfo[] artifactInfos = new ArtifactInfo[versions.length];
        for (int i = 0; i < versions.length; i++) {
            String version = versions[i];
            artifactInfos[i] = new VersionArtifactInfo(version);
        }
        List<ArtifactInfo> sorted = getLatestStrategy().sort(artifactInfos);
        Collections.reverse(sorted);

        List<String> sortedVersions = new ArrayList<String>();
        for (ArtifactInfo info : sorted) {
            sortedVersions.add(info.getRevision());
        }
        return sortedVersions;
    }

    protected Resource getResource(String source) throws IOException {
        return repository.getResource(source);
    }
    
    protected Resource getResource(String source, Artifact target) throws IOException {
        return repository.getResource(source, target.getId());
    }

    protected String[] listVersions(ModuleRevisionId moduleRevisionId, String pattern, Artifact artifact) {
        ModuleRevisionId idWithoutRevision = ModuleRevisionId.newInstance(moduleRevisionId, IvyPatternHelper.getTokenString(IvyPatternHelper.REVISION_KEY));
        String partiallyResolvedPattern = IvyPatternHelper.substitute(pattern, idWithoutRevision, artifact);
        LOGGER.debug("Listing all in {}", partiallyResolvedPattern);
        return ResolverHelper.listTokenValues(repository, partiallyResolvedPattern, IvyPatternHelper.REVISION_KEY);
    }

    protected long get(Resource resource, File destination) throws IOException {
        LOGGER.debug("Downloading {} to {}", resource.getName(), destination);
        if (destination.getParentFile() != null) {
            destination.getParentFile().mkdirs();
        }
        repository.downloadResource(resource, destination);
        return destination.length();
    }

    public void publish(Artifact artifact, File src, boolean overwrite) throws IOException {
        String destinationPattern;
        if ("ivy".equals(artifact.getType()) && !getIvyPatterns().isEmpty()) {
            destinationPattern = (String) getIvyPatterns().get(0);
        } else if (!getArtifactPatterns().isEmpty()) {
            destinationPattern = (String) getArtifactPatterns().get(0);
        } else {
            throw new IllegalStateException("impossible to publish " + artifact + " using " + this + ": no artifact pattern defined");
        }
        // Check for m2 compatibility
        ModuleRevisionId moduleRevisionId = artifact.getModuleRevisionId();
        if (isM2compatible()) {
            moduleRevisionId = convertM2IdForResourceSearch(moduleRevisionId);
        }

        String destination = getDestination(destinationPattern, artifact, moduleRevisionId);

        put(artifact, src, destination, overwrite);
        LOGGER.info("Published {} to {}", artifact.getName(), hidePassword(destination));
    }

    protected String getDestination(String pattern, Artifact artifact, ModuleRevisionId moduleRevisionId) {
        return IvyPatternHelper.substitute(pattern, moduleRevisionId, artifact);
    }

    protected void put(Artifact artifact, File src, String destination, boolean overwrite) throws IOException {
        // verify the checksum algorithms before uploading artifacts!
        String[] checksums = getChecksumAlgorithms();
        for (String checksum : checksums) {
            if (!ChecksumHelper.isKnownAlgorithm(checksum)) {
                throw new IllegalArgumentException("Unknown checksum algorithm: " + checksum);
            }
        }

        repository.put(artifact, src, destination, overwrite);
        for (String checksum : checksums) {
            putChecksum(artifact, src, destination, overwrite, checksum);
        }
    }

    protected void putChecksum(Artifact artifact, File src, String destination, boolean overwrite,
                               String algorithm) throws IOException {
        File csFile = File.createTempFile("ivytemp", algorithm);
        try {
            FileUtil.copy(new ByteArrayInputStream(ChecksumHelper.computeAsString(src, algorithm)
                    .getBytes()), csFile, null);
            repository.put(DefaultArtifact.cloneWithAnotherTypeAndExt(artifact, algorithm,
                    artifact.getExt() + "." + algorithm), csFile, destination + "." + algorithm, overwrite);
        } finally {
            csFile.delete();
        }
    }

    protected void findTokenValues(Collection names, List patterns, Map tokenValues, String token) {
        for (Object pattern : patterns) {
            String partiallyResolvedPattern = IvyPatternHelper.substituteTokens((String) pattern, tokenValues);
            String[] values = ResolverHelper.listTokenValues(repository, partiallyResolvedPattern, token);
            if (values != null) {
                names.addAll(filterNames(new ArrayList(Arrays.asList(values))));
            }
        }
    }

    protected boolean exist(String path) {
        try {
            Resource resource = repository.getResource(path);
            try {
                return resource.exists();
            } finally {
                cleanupResource(resource);
            }
        } catch (IOException e) {
            return false;
        }
    }

    protected Collection findNames(Map tokenValues, String token) {
        throw new UnsupportedOperationException();
    }

    protected Collection filterNames(Collection names) {
        getSettings().filterIgnore(names);
        return names;
    }

    public void addIvyPattern(String pattern) {
        ivyPatterns.add(pattern);
    }

    public void addArtifactPattern(String pattern) {
        artifactPatterns.add(pattern);
    }

    public List getIvyPatterns() {
        return Collections.unmodifiableList(ivyPatterns);
    }

    public List getArtifactPatterns() {
        return Collections.unmodifiableList(artifactPatterns);
    }

    protected void setIvyPatterns(List patterns) {
        ivyPatterns = patterns;
    }

    protected void setArtifactPatterns(List patterns) {
        artifactPatterns = patterns;
    }

    public void dumpSettings() {
        super.dumpSettings();
        Message.debug("\t\tm2compatible: " + isM2compatible());
        Message.debug("\t\tivy patterns:");
        for (ListIterator iter = getIvyPatterns().listIterator(); iter.hasNext();) {
            String p = (String) iter.next();
            Message.debug("\t\t\t" + p);
        }
        Message.debug("\t\tartifact patterns:");
        for (ListIterator iter = getArtifactPatterns().listIterator(); iter.hasNext();) {
            String p = (String) iter.next();
            Message.debug("\t\t\t" + p);
        }
        Message.debug("\t\trepository: " + repository);
    }

    public boolean isM2compatible() {
        return m2compatible;
    }

    public void setM2compatible(boolean compatible) {
        m2compatible = compatible;
    }

    protected ModuleRevisionId convertM2IdForResourceSearch(ModuleRevisionId mrid) {
        if (mrid.getOrganisation() == null || mrid.getOrganisation().indexOf('.') == -1) {
            return mrid;
        }
        return ModuleRevisionId.newInstance(mrid.getOrganisation().replace('.', '/'),
            mrid.getName(), mrid.getBranch(), mrid.getRevision(),
            mrid.getQualifiedExtraAttributes());
    }
    
    private class VersionArtifactInfo implements ArtifactInfo {
        private final String version;

        private VersionArtifactInfo(String version) {
            this.version = version;
        }

        public String getRevision() {
            return version;
        }

        public long getLastModified() {
            return 0;
        }
    }
}
