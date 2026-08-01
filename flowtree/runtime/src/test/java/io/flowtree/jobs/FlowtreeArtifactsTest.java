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

import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for {@link FlowtreeArtifacts}: the {@code .flowtree/} directory
 * constants, the path helpers, and the commit-exclusion gate that backs the
 * structural no-leak guarantee.
 */
public class FlowtreeArtifactsTest extends TestSuiteBase {

    /** The artifact directory is the agreed-upon {@code .flowtree} at the working-tree root. */
    @Test(timeout = 30000)
    public void directoryConstantIsFlowtree() {
        assertEquals(".flowtree", FlowtreeArtifacts.DIRECTORY);
        assertEquals(".flowtree/claude-output", FlowtreeArtifacts.OUTPUT_CAPTURE_DIRECTORY);
    }

    /** The commit whitelist ships EMPTY so nothing under {@code .flowtree/} is ever committed today. */
    @Test(timeout = 30000)
    public void commitWhitelistIsEmpty() {
        assertTrue("The production commit whitelist must be empty",
                FlowtreeArtifacts.COMMIT_WHITELIST.isEmpty());
    }

    /** {@link FlowtreeArtifacts#inDirectory} resolves a bare name under {@code .flowtree/}. */
    @Test(timeout = 30000)
    public void inDirectoryPrefixesWithFlowtree() {
        assertEquals(".flowtree/falsification-results.json",
                FlowtreeArtifacts.inDirectory("falsification-results.json"));
        assertEquals(".flowtree/commit.txt", FlowtreeArtifacts.inDirectory("commit.txt"));
    }

    /** {@link FlowtreeArtifacts#isUnderDirectory} recognizes the directory and its contents only. */
    @Test(timeout = 30000)
    public void isUnderDirectoryMatchesContentsOnly() {
        assertTrue(FlowtreeArtifacts.isUnderDirectory(".flowtree"));
        assertTrue(FlowtreeArtifacts.isUnderDirectory(".flowtree/falsification-results.json"));
        assertTrue(FlowtreeArtifacts.isUnderDirectory(".flowtree/claude-output/x.json"));
        assertTrue("leading ./ must normalize",
                FlowtreeArtifacts.isUnderDirectory("./.flowtree/commit.txt"));
        assertFalse("a sibling whose name merely starts with .flowtree is not under it",
                FlowtreeArtifacts.isUnderDirectory(".flowtree.lock"));
        assertFalse(FlowtreeArtifacts.isUnderDirectory("src/main/java/Foo.java"));
        assertFalse(FlowtreeArtifacts.isUnderDirectory("commit.txt"));
    }

    /** With the empty whitelist, every path under {@code .flowtree/} is excluded; nothing else is. */
    @Test(timeout = 30000)
    public void emptyWhitelistExcludesEverythingUnderDirectory() {
        Set<String> empty = FlowtreeArtifacts.COMMIT_WHITELIST;
        assertTrue(FlowtreeArtifacts.isExcludedFromCommit(".flowtree/falsification-results.json", empty));
        assertTrue(FlowtreeArtifacts.isExcludedFromCommit(".flowtree/retrospective-results.json", empty));
        assertTrue(FlowtreeArtifacts.isExcludedFromCommit(".flowtree/claude-output/run.json", empty));
        assertFalse("a real source change must never be excluded by this gate",
                FlowtreeArtifacts.isExcludedFromCommit("src/main/java/Foo.java", empty));
        assertFalse("commit.txt at the root is not under .flowtree/ and is untouched here",
                FlowtreeArtifacts.isExcludedFromCommit("commit.txt", empty));
    }

    /** A non-empty whitelist re-admits exactly the listed path and still excludes its siblings. */
    @Test(timeout = 30000)
    public void whitelistReadmitsListedPathOnly() {
        Set<String> whitelist = Set.of(".flowtree/keepme.txt");
        assertFalse("a whitelisted path must NOT be excluded",
                FlowtreeArtifacts.isExcludedFromCommit(".flowtree/keepme.txt", whitelist));
        assertTrue("a non-whitelisted sibling must still be excluded",
                FlowtreeArtifacts.isExcludedFromCommit(".flowtree/other.txt", whitelist));
        assertFalse("paths outside .flowtree/ are never gated",
                FlowtreeArtifacts.isExcludedFromCommit("README.md", whitelist));
    }

    /** A {@code null} whitelist is treated as the empty production whitelist. */
    @Test(timeout = 30000)
    public void nullWhitelistBehavesAsEmpty() {
        assertTrue(FlowtreeArtifacts.isExcludedFromCommit(".flowtree/anything.json", null));
        assertFalse(FlowtreeArtifacts.isExcludedFromCommit("src/Foo.java", null));
    }
}
