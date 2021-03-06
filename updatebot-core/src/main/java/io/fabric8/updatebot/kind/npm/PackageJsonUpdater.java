/*
 * Copyright 2016 Red Hat, Inc.
 *
 * Red Hat licenses this file to you under the Apache License, version
 * 2.0 (the "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied.  See the License for the specific language governing
 * permissions and limitations under the License.
 */
package io.fabric8.updatebot.kind.npm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.fabric8.updatebot.commands.CommandContext;
import io.fabric8.updatebot.commands.PushVersionChangesContext;
import io.fabric8.updatebot.kind.Kind;
import io.fabric8.updatebot.kind.KindDependenciesCheck;
import io.fabric8.updatebot.kind.Updater;
import io.fabric8.updatebot.kind.npm.dependency.DependencyCheck;
import io.fabric8.updatebot.kind.npm.dependency.DependencyTree;
import io.fabric8.updatebot.model.Dependencies;
import io.fabric8.updatebot.model.DependencySet;
import io.fabric8.updatebot.model.DependencyVersionChange;
import io.fabric8.updatebot.model.NpmDependencies;
import io.fabric8.updatebot.support.Commands;
import io.fabric8.updatebot.support.FileHelper;
import io.fabric8.updatebot.support.JsonNodes;
import io.fabric8.updatebot.support.MarkupHelper;
import io.fabric8.updatebot.support.Strings;
import io.fabric8.utils.Files;
import io.fabric8.utils.Filter;
import io.fabric8.utils.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 */
public class PackageJsonUpdater implements Updater {

    private static final transient Logger LOG = LoggerFactory.getLogger(PackageJsonUpdater.class);

    @Override
    public boolean isApplicable(CommandContext context) {
        return FileHelper.isFile(context.file("package.json"));
    }

    @Override
    public boolean pushVersions(PushVersionChangesContext context) throws IOException {
        File file = context.file("package.json");
        JsonNode tree = MarkupHelper.loadJson(file);
        boolean answer = false;
        for (String dependencyKey : NpmDependencyKinds.DEPENDENCY_KEYS) {
            JsonNode dependencies = tree.get(dependencyKey);
            if (dependencies instanceof ObjectNode) {
                ObjectNode objectNode = (ObjectNode) dependencies;
                if (doPushVersionChange(dependencyKey, objectNode, context)) {
                    answer = true;
                }
            }
        }
        if (answer) {
            MarkupHelper.savePrettyJson(file, tree);
            context.updatedFile(file);
        }
        return answer;
    }

    /**
     * Adds the list of possible dependency update steps from the given source context that we can then apply to
     * other repositories
     */
    @Override
    public void addPushVersionsSteps(CommandContext context, Dependencies dependencyConfig, List<DependencyVersionChange> list) {
        NpmDependencies dependencies = dependencyConfig.getNpm();
        JsonNode tree = getPackageJsonTree(context);
        if (tree != null) {
            String name = JsonNodes.textValue(tree, "name");
            String version = JsonNodes.textValue(tree, "version");
            if (Strings.notEmpty(name) && Strings.notEmpty(version)) {
                if (isDevelopmentVersion(name, version)) {
                    LOG.info("Not updating NPM dependency " + name + " version " + version + " as this is a development version and not a release");
                } else {
                    list.add(new DependencyVersionChange(Kind.NPM, name, version, NpmDependencyKinds.DEPENDENCIES));
                }
            }
            if (dependencies != null) {
                addUpdateDependencySteps(list, tree, dependencies.getDependencies(), NpmDependencyKinds.DEPENDENCIES);
                addUpdateDependencySteps(list, tree, dependencies.getDevDependencies(), NpmDependencyKinds.DEV_DEPENDENCIES);
                addUpdateDependencySteps(list, tree, dependencies.getPeerDependencies(), NpmDependencyKinds.PEER_DEPENDENCIES);
            }
        }
    }

    protected JsonNode getPackageJsonTree(CommandContext context) {
        return getJsonFile(context, "package.json");
    }

    protected JsonNode getJsonFile(CommandContext context, String fileName) {
        JsonNode tree = null;
        File file = context.file(fileName);
        if (Files.isFile(file)) {
            try {
                tree = MarkupHelper.loadJson(file);
            } catch (IOException e) {
                LOG.warn("Failed to parse JSON " + file + ". " + e, e);
            }
        }
        return tree;
    }

    @Override
    public KindDependenciesCheck checkDependencies(CommandContext context, List<DependencyVersionChange> changes) {
        List<DependencyVersionChange> validChanges = new ArrayList<>();
        List<DependencyVersionChange> invalidChanges = new ArrayList<>();
        Map<String, DependencyCheck> failedChecks = new TreeMap<>();

        String dependencyFileName = ".dependency-tree.json";
        generateDependencyTree(context, dependencyFileName);
        JsonNode json = null;
        try {
            json = getJsonFile(context, dependencyFileName);
        } finally {
            File file = new File(context.getDir(), dependencyFileName);
            if (Files.isFile(file)) {
                file.delete();
            }
        }
        if (json != null) {
            DependencyTree dependencyTree = DependencyTree.parseTree(json);
            for (DependencyVersionChange change : changes) {
                DependencyCheck dependencyCheck = dependencyTree.dependencyCheck(change.getDependency());
                if (dependencyCheck.isValid()) {
                    validChanges.add(change);
                } else {
                    invalidChanges.add(change);
                    failedChecks.put(change.getDependency(), dependencyCheck);
                }
            }
        }
        return new KindDependenciesCheck(validChanges, invalidChanges, failedChecks);
    }

    protected void generateDependencyTree(CommandContext context, String dependencyFileName) {
        context.getConfiguration().getNpmDependencyTreeGenerator().generateDependencyTree(context, dependencyFileName);

    }


    /**
     * Returns true if the version string is
     *
     * @param name
     * @param version
     * @return
     */
    protected boolean isDevelopmentVersion(String name, String version) {
        if (version.endsWith("-development")) {
            return true;
        }
        return false;
    }

    protected void addUpdateDependencySteps(List<DependencyVersionChange> list, JsonNode tree, DependencySet dependencySet, String dependencyKey) {
        if (dependencySet != null) {
            Filter<String> filter = dependencySet.createFilter();
            JsonNode dependencies = tree.get(dependencyKey);
            if (dependencies instanceof ObjectNode) {
                ObjectNode objectNode = (ObjectNode) dependencies;
                Iterator<String> iter = objectNode.fieldNames();
                while (iter.hasNext()) {
                    String field = iter.next();
                    if (filter.matches(field)) {
                        String value = JsonNodes.textValue(objectNode, field);
                        if (value != null) {
                            list.add(new DependencyVersionChange(Kind.NPM, field, value, dependencyKey));
                        }
                    }
                }
            }
        }
    }

    @Override
    public boolean pullVersions(CommandContext context) throws IOException {
        File dir = context.getRepository().getDir();
        int status = Commands.runCommand(dir, "ncu", "--upgrade");
        if (status == 0) {
            return true;
        }
        return false;
    }


    protected boolean doPushVersionChange(String dependencyKey, ObjectNode dependencies, PushVersionChangesContext context) {
        String name = context.getName();
        String value = context.getValue();
        JsonNode dependency = dependencies.get(name);
        if (dependency != null && dependency.isTextual()) {
            String old = dependency.textValue();
            if (!Objects.equal(old, value)) {
                dependencies.put(name, value);
                context.updatedVersion(dependencyKey, name, value, old);
                return true;
            }
        }
        return false;
    }
}
