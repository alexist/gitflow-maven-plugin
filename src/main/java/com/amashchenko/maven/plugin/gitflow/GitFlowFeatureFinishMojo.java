/*
 * Copyright 2014-2023 Aleksandr Mashchenko.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.amashchenko.maven.plugin.gitflow;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.codehaus.plexus.components.interactivity.PrompterException;
import org.codehaus.plexus.util.StringUtils;
import org.codehaus.plexus.util.cli.CommandLineException;

/**
 * The git flow feature finish mojo.
 * 
 */
@Mojo(name = "feature-finish", aggregator = true)
public class GitFlowFeatureFinishMojo extends AbstractGitFlowMojo {

    /** Whether to keep feature branch after finish. */
    @Parameter(property = "keepBranch", defaultValue = "false")
    private boolean keepBranch = false;

    /**
     * Whether to skip calling Maven test goal before merging the branch.
     * 
     * @since 1.0.5
     */
    @Parameter(property = "skipTestProject", defaultValue = "false")
    private boolean skipTestProject = false;

    /**
     * Whether to squash feature branch commits into a single commit upon
     * merging.
     * 
     * @since 1.2.3
     */
    @Parameter(property = "featureSquash", defaultValue = "false")
    private boolean featureSquash = false;

    /**
     * Whether to push to the remote.
     * 
     * @since 1.3.0
     */
    @Parameter(property = "pushRemote", defaultValue = "true")
    private boolean pushRemote;

    /**
     * Feature name, without feature branch prefix, to use in non-interactive mode.
     * 
     * @since 1.9.0
     */
    @Parameter(property = "featureName")
    private String featureName;

    /**
     * Feature branch to use in non-interactive mode. Must start with feature branch
     * prefix. The featureBranch parameter will be used instead of
     * {@link #featureName} if both are set.
     * 
     * @since 1.16.0
     */
    @Parameter(property = "featureBranch")
    private String featureBranch;

    /**
     * Maven goals to execute in the feature branch before merging into the
     * development branch.
     *
     * @since 1.13.0
     */
    @Parameter(property = "preFeatureFinishGoals")
    private String preFeatureFinishGoals;

    /**
     * Maven goals to execute in the development branch after merging a feature.
     *
     * @since 1.13.0
     */
    @Parameter(property = "postFeatureFinishGoals")
    private String postFeatureFinishGoals;

    /**
     * Whether to increment the version during feature-finish goal.
     *
     * @since 1.15.0
     */
    @Parameter(property = "incrementVersionAtFinish", defaultValue = "false")
    private boolean incrementVersionAtFinish;

    /** {@inheritDoc} */
    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        validateConfiguration(preFeatureFinishGoals, postFeatureFinishGoals);

        try {
            // check uncommitted changes
            checkUncommittedChanges();

            String featureBranchName = null;
            if (settings.isInteractiveMode()) {
                featureBranchName = promptBranchName();
            } else if (StringUtils.isNotBlank(featureBranch)) {
                if (!featureBranch.startsWith(gitFlowConfig.getFeatureBranchPrefix())) {
                    throw new MojoFailureException("The featureBranch parameter doesn't start with feature branch prefix.");
                }
                if (!gitCheckBranchExists(featureBranch)) {
                    throw new MojoFailureException(
                            "Feature branch with name '" + featureBranch + "' doesn't exist. Cannot finish feature.");
                }
                featureBranchName = featureBranch;
            } else if (StringUtils.isNotBlank(featureName)) {
                final String branch = gitFlowConfig.getFeatureBranchPrefix() + featureName;
                if (!gitCheckBranchExists(branch)) {
                    throw new MojoFailureException("Feature branch with name '" + branch + "' doesn't exist. Cannot finish feature.");
                }
                featureBranchName = branch;
            }

            if (StringUtils.isBlank(featureBranchName)) {
                throw new MojoFailureException("Feature branch name to finish is blank.");
            }

            // fetch and check remote
            if (fetchRemote) {
                gitFetchRemoteAndCompareCreate(featureBranchName);

                gitFetchRemoteAndCompareCreate(gitFlowConfig.getDevelopmentBranch());
            }

            // git checkout feature/...
            gitCheckout(featureBranchName);

            if (!skipTestProject) {
                // mvn clean test
                mvnCleanTest();
            }

            // maven goals before merge
            if (StringUtils.isNotBlank(preFeatureFinishGoals)) {
                mvnRun(preFeatureFinishGoals);
            }

            String featureVersion = getCurrentProjectVersion();

            final String featName = featureBranchName.replaceFirst(gitFlowConfig.getFeatureBranchPrefix(), "");

            if (incrementVersionAtFinish) {
                // prevent incrementing feature name which can hold numbers
                String ver = featureVersion.replaceFirst("-" + featName, "");
                GitFlowVersionInfo nextVersionInfo = new GitFlowVersionInfo(ver, getVersionPolicy());
                ver = nextVersionInfo.nextSnapshotVersion();
                GitFlowVersionInfo featureVersionInfo = new GitFlowVersionInfo(ver, getVersionPolicy());
                featureVersion = featureVersionInfo.featureVersion(featName);

                mvnSetVersions(featureVersion);

                Map<String, String> properties = new HashMap<>();
                properties.put("version", featureVersion);
                properties.put("featureName", featName);
                gitCommit(commitMessages.getFeatureFinishIncrementVersionMessage(), properties);
            }

            final String keptFeatureVersion = featureVersion;

            final String version = keptFeatureVersion.replaceFirst("-" + featName, "");
            if (keptFeatureVersion.contains("-" + featName)) {
                // mvn versions:set -DnewVersion=... -DgenerateBackupPoms=false
                mvnSetVersions(version);

                Map<String, String> properties = new HashMap<>();
                properties.put("version", version);
                properties.put("featureName", featName);

                // git commit -a -m updating versions for development branch
                gitCommit(commitMessages.getFeatureFinishMessage(), properties);
            }

            // git checkout develop
            gitCheckout(gitFlowConfig.getDevelopmentBranch());

            if (featureSquash) {
                // git merge --squash feature/...
                gitMergeSquash(featureBranchName);
                gitCommit(StringUtils.isBlank(commitMessages.getFeatureSquashMessage()) ? featureBranchName
                        : commitMessages.getFeatureSquashMessage());
            } else {
                Map<String, String> properties = new HashMap<>();
                properties.put("version", version);
                properties.put("featureName", featName);
                // git merge --no-ff feature/...
                gitMergeNoff(featureBranchName, commitMessages.getFeatureFinishDevMergeMessage(), properties);
            }

            // maven goals after merge
            if (StringUtils.isNotBlank(postFeatureFinishGoals)) {
                mvnRun(postFeatureFinishGoals);
            }

            if (installProject) {
                // mvn clean install
                mvnCleanInstall();
            }

            if (keepBranch) {
                gitCheckout(featureBranchName);

                mvnSetVersions(keptFeatureVersion);

                Map<String, String> properties = new HashMap<>();
                properties.put("version", keptFeatureVersion);
                properties.put("featureName", featName);

                gitCommit(commitMessages.getUpdateFeatureBackMessage(), properties);
            }

            if (pushRemote) {
                gitPush(gitFlowConfig.getDevelopmentBranch(), false);

                if (keepBranch) {
                    gitPush(featureBranchName, false);
                } else {
                    gitPushDelete(featureBranchName);
                }
            }

            if (!keepBranch) {
                if (featureSquash) {
                    // git branch -D feature/...
                    gitBranchDeleteForce(featureBranchName);
                } else {
                    // git branch -d feature/...
                    gitBranchDelete(featureBranchName);
                }
            }
        } catch (Exception e) {
            throw new MojoFailureException("feature-finish", e);
        }
    }

    private String promptBranchName() throws MojoFailureException, CommandLineException {
        // git for-each-ref --format='%(refname:short)' refs/heads/feature/*
        final String featureBranches = gitFindBranches(gitFlowConfig.getFeatureBranchPrefix(), false);

        final String currentBranch = gitCurrentBranch();

        if (StringUtils.isBlank(featureBranches)) {
            throw new MojoFailureException("There are no feature branches.");
        }

        final String[] branches = featureBranches.split("\\r?\\n");

        List<String> numberedList = new ArrayList<>();
        String defaultChoice = null;
        StringBuilder str = new StringBuilder("Feature branches:").append(LS);
        for (int i = 0; i < branches.length; i++) {
            str.append((i + 1) + ". " + branches[i] + LS);
            numberedList.add(String.valueOf(i + 1));
            if (branches[i].equals(currentBranch)) {
                defaultChoice = String.valueOf(i + 1);
            }
        }
        str.append("Choose feature branch to finish");

        String featureNumber = null;
        try {
            while (StringUtils.isBlank(featureNumber)) {
                featureNumber = prompter.prompt(str.toString(), numberedList, defaultChoice);
            }
        } catch (PrompterException e) {
            throw new MojoFailureException("feature-finish", e);
        }

        String featureBranchName = null;
        if (featureNumber != null) {
            int num = Integer.parseInt(featureNumber);
            featureBranchName = branches[num - 1];
        }

        return featureBranchName;
    }
}
