/*
 * Copyright 2026 Michael Murray
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.flowtree.jobs;

/**
 * Enforcement rule that prevents {@code <dependency>} changes in Maven
 * {@code pom.xml} files. Active when {@link ClaudeCodeJob#isEnforceMavenDependencies()}
 * is {@code true}.
 *
 * <p>Only {@code <dependency>} element additions, removals, or modifications
 * are flagged. Changes to other {@code pom.xml} content (plugin configuration,
 * properties, etc.) are not affected.</p>
 *
 * @author Michael Murray
 * @see ClaudeCodeJob#hasMavenDependencyChanges()
 * @see EnforcementRule
 */
class MavenDependencyProtectionRule implements EnforcementRule {

    @Override
    public String getName() { return "no-maven-dependency-changes"; }

    @Override
    public boolean isViolated(ClaudeCodeJob job) {
        return job.hasMavenDependencyChanges();
    }

    @Override
    public String buildCorrectionPrompt(ClaudeCodeJob job) {
        String baseBranch = job.getBaseBranch() != null ? job.getBaseBranch() : "master";
        StringBuilder sb = new StringBuilder();
        sb.append("MAVEN DEPENDENCY PROTECTION RULE VIOLATION\n\n");
        sb.append("Your changes to one or more pom.xml files add, remove, or modify ");
        sb.append("<dependency> entries. Maven module dependencies are externally ");
        sb.append("controlled and MUST NOT be modified by agents.\n\n");
        sb.append("MANDATORY ACTION: Revert all <dependency> changes in any pom.xml files.\n\n");
        sb.append("Steps to identify and fix the violation:\n");
        sb.append("1. Run: git diff origin/").append(baseBranch)
          .append(" -- '**/pom.xml' 'pom.xml'\n");
        sb.append("   to see exactly what <dependency> lines you added or removed.\n");
        sb.append("2. Use the Edit tool to surgically remove any added <dependency> ");
        sb.append("blocks and restore any removed ones.\n\n");
        sb.append("IMPORTANT — do NOT use git restore, git checkout --, or git reset ");
        sb.append("to revert pom.xml files. Those commands discard ALL your changes ");
        sb.append("to the file, not just the dependency modifications. Use the Edit ");
        sb.append("tool to remove only the <dependency> changes.\n\n");
        sb.append("You MAY keep all non-dependency changes to pom.xml files ");
        sb.append("(plugin configuration, properties, build settings, etc.). ");
        sb.append("Only <dependency> additions, removals, and modifications must be undone.");
        return sb.toString();
    }
}
