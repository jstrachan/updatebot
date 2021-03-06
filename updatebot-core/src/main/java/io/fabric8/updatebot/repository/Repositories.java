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
package io.fabric8.updatebot.repository;

import io.fabric8.updatebot.Configuration;
import io.fabric8.updatebot.commands.CommandSupport;
import io.fabric8.updatebot.github.GitHubHelpers;
import io.fabric8.updatebot.model.GitHubProjects;
import io.fabric8.updatebot.model.GitHubRepositoryDetails;
import io.fabric8.updatebot.model.GitRepository;
import io.fabric8.updatebot.model.GithubOrganisation;
import io.fabric8.updatebot.model.GithubRepository;
import io.fabric8.updatebot.model.Projects;
import io.fabric8.updatebot.support.Commands;
import io.fabric8.updatebot.support.FileHelper;
import io.fabric8.updatebot.support.Strings;
import io.fabric8.utils.Files;
import io.fabric8.utils.Filter;
import io.fabric8.utils.Objects;
import org.kohsuke.github.GHPerson;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GitHub;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static io.fabric8.updatebot.support.MarkupHelper.loadYaml;

/**
 */
public class Repositories {
    private static final transient Logger LOG = LoggerFactory.getLogger(Repositories.class);


    public static List<LocalRepository> cloneOrPullRepositories(CommandSupport command, Configuration configuration, Projects projects) throws IOException {
        List<LocalRepository> repositories = findRepositories(command, configuration, projects);
        for (LocalRepository repository : repositories) {
            cloneOrPull(configuration, repository);
        }
        return repositories;
    }

    private static void cloneOrPull(Configuration configuration, LocalRepository repository) {
        File dir = repository.getDir();
        File gitDir = new File(dir, ".git");
        if (gitDir.exists()) {
            if (Commands.runCommandIgnoreOutput(dir, "git", "stash") == 0) {
                if (Commands.runCommandIgnoreOutput(dir, "git", "checkout", "master") == 0) {
                    if (!configuration.isPullDisabled()) {
                        LOG.debug("Pulling: " + dir + " repo: " + repository.getCloneUrl());
                        Commands.runCommandIgnoreOutput(dir, "git", "pull");
                    }
                }
            }
        } else {
            File parentDir = dir.getParentFile();
            parentDir.mkdirs();

            LOG.info("Cloning: " + repository.getFullName() + " to " + FileHelper.getRelativePathToCurrentDir(dir));
            Commands.runCommand(parentDir, "git", "clone", repository.getCloneUrl(), dir.getName());
        }
    }

    protected static List<LocalRepository> findRepositories(CommandSupport updateBot, Configuration configuration, Projects projects) throws IOException {
        String workDirPath = configuration.getWorkDir();
        File workDir = new File(workDirPath);
        if (!workDir.isAbsolute()) {
            File sourceDir = configuration.getSourceDir();
            if (io.fabric8.utils.Files.isDirectory(sourceDir)) {
                workDir = new File(sourceDir, workDirPath);
            }
        }
        workDir.mkdirs();

        Map<String, LocalRepository> map = new LinkedHashMap<>();
        File gitHubDir = new File(workDir, "github");
        File gitDir = new File(workDir, "git");

        GitHubProjects githubProjects = projects.getGithub();
        if (githubProjects != null) {
            List<GithubOrganisation> organisations = githubProjects.getOrganisations();
            if (organisations != null && !organisations.isEmpty()) {
                GitHub github = configuration.getGithub();
                for (GithubOrganisation organisation : organisations) {
                    addGitHubRepositories(map, github, organisation, new File(gitHubDir, organisation.getName()));
                }
            }
        }
        List<GitRepository> gitRepositories = projects.getGit();
        if (gitRepositories != null) {
            for (GitRepository gitRepository : gitRepositories) {
                addRepository(map, gitDir, gitRepository);
            }
        }
        return new ArrayList<>(map.values());
    }

    protected static void addRepository(Map<String, LocalRepository> map, File gitDir, GitRepository gitRepository) {
        LocalRepository localRepository = new LocalRepository(gitRepository, new File(gitDir, gitRepository.getName()));
        map.putIfAbsent(localRepository.getCloneUrl(), localRepository);
    }

    protected static void addGitHubRepositories(Map<String, LocalRepository> map, GitHub github, GithubOrganisation organisation, File file) {
        String orgName = organisation.getName();
        Filter<String> filter = organisation.createFilter();

        GHPerson person = GitHubHelpers.getOrganisationOrUser(github, orgName);
        if (person != null) {
            try {
                Set<String> foundNames = new TreeSet<>();
                List<GitHubRepositoryDetails> namedRepositories = organisation.getRepositories();
                if (namedRepositories != null) {
                    for (GitHubRepositoryDetails namedRepository : namedRepositories) {
                        String name = namedRepository.getName();
                        if (Strings.notEmpty(name) && foundNames.add(name)) {
                            GHRepository ghRepository = null;
                            try {
                                ghRepository = person.getRepository(name);
                            } catch (IOException e) {
                                LOG.warn("Github repository " + orgName + "/" + name + " not found: " + e);
                                continue;
                            }
                            if (ghRepository != null) {
                                GitRepository gitRepository = new GithubRepository(ghRepository, namedRepository);
                                addRepository(map, file, gitRepository);
                            } else {
                                LOG.warn("Github repository " + orgName + "/" + name + " not found!");
                            }
                        }
                    }
                }
                Map<String, GHRepository> repositories = person.getRepositories();
                for (Map.Entry<String, GHRepository> entry : repositories.entrySet()) {
                    String repoName = entry.getKey();
                    if (filter.matches(repoName) && foundNames.add(repoName)) {
                        GitRepository gitRepository = new GithubRepository(entry.getValue());
                        addRepository(map, file, gitRepository);
                    }
                }
            } catch (IOException e) {
                LOG.warn("Failed to load organisation: " + orgName + ". " + e, e);
            }
        }
    }

    /**
     * Returns the repository for the given name or null if it could not be found
     */
    public static LocalRepository findRepository(List<LocalRepository> localRepositories, String name) {
        if (localRepositories != null) {
            for (LocalRepository repository : localRepositories) {
                GitRepository repo = repository.getRepo();
                if (repo != null) {
                    if (Objects.equal(name, repo.getName())) {
                        return repository;
                    }
                }
            }
        }
        return null;
    }


    /**
     * Returns the link to the repository
     */
    public static String getRepositoryLink(LocalRepository repository) {
        return getRepositoryLink(repository, repository.getFullName());
    }

    /**
     * Returns the link to the repository
     */
    public static String getRepositoryLink(LocalRepository repository, String label) {
        return getRepositoryLink(repository, label, "`" + label + "`");
    }

    /**
     * Returns the link to the repository
     */
    public static String getRepositoryLink(LocalRepository repository, String label, String defaultValue) {
        if (repository != null) {
            String htmlUrl = repository.getRepo().getHtmlUrl();
            if (Strings.notEmpty(htmlUrl)) {
                return "[" + label + "](" + htmlUrl + ")";
            }
        }
        if (Strings.notEmpty(defaultValue)) {
            return defaultValue;
        }
        return label;
    }

    /**
     * Returns the UpdateBot project configurations from the given configFile (File or URL) and source directory
     */
    public static Projects loadProjects(String configFile, File sourceDir) throws IOException {
        File file = new File(configFile);
        if (Files.isDirectory(sourceDir) && !file.isAbsolute()) {
            file = new File(sourceDir, configFile);
        }
        if (!Files.isFile(file)) {
            URL url = null;
            try {
                url = new URL(configFile);
                InputStream in = null;
                try {
                    in = url.openStream();
                } catch (IOException e) {
                    throw new IOException("Failed to open URL " + configFile + ". " + e, e);
                }
                if (in != null) {
                    return loadYaml(in, Projects.class);
                }
            } catch (MalformedURLException e) {
                // ignore
            }
            throw new FileNotFoundException(file.getCanonicalPath());
        }
        return loadYaml(file, Projects.class);
    }
}
