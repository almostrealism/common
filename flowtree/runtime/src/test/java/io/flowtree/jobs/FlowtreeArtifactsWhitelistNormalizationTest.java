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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Regression test for a Copilot review finding on
 * {@link FlowtreeArtifacts#isExcludedFromCommit}: the candidate path was
 * normalized before the whitelist lookup, but whitelist entries themselves
 * were not, so a whitelist entry spelled with a leading {@code ./}, a
 * leading {@code /}, or Windows backslashes would silently fail to match
 * its (correctly normalized) candidate path and remain excluded.
 *
 * <p>The production {@link FlowtreeArtifacts#COMMIT_WHITELIST} stays empty
 * (see {@link FlowtreeArtifactsTest#commitWhitelistIsEmpty}); this test
 * injects whitelist sets directly into {@link FlowtreeArtifacts#isExcludedFromCommit}
 * to prove the normalization fix without touching production configuration.
 */
public class FlowtreeArtifactsWhitelistNormalizationTest extends TestSuiteBase {

    /** A whitelist entry with a leading {@code ./} must still allow its path. */
    @Test(timeout = 30000)
    public void dotSlashPrefixedWhitelistEntryAllowsItsPath() {
        Set<String> whitelist = Set.of("./.flowtree/keepme.txt");
        assertFalse("a whitelist entry spelled with a leading ./ must match its normalized path",
                FlowtreeArtifacts.isExcludedFromCommit(".flowtree/keepme.txt", whitelist));
    }

    /** A whitelist entry with a leading {@code /} must still allow its path. */
    @Test(timeout = 30000)
    public void leadingSlashPrefixedWhitelistEntryAllowsItsPath() {
        Set<String> whitelist = Set.of("/.flowtree/keepme.txt");
        assertFalse("a whitelist entry spelled with a leading / must match its normalized path",
                FlowtreeArtifacts.isExcludedFromCommit(".flowtree/keepme.txt", whitelist));
    }

    /** A whitelist entry using Windows backslashes must still allow its path. */
    @Test(timeout = 30000)
    public void backslashSpelledWhitelistEntryAllowsItsPath() {
        Set<String> whitelist = Set.of(".flowtree\\keepme.txt");
        assertFalse("a whitelist entry spelled with backslashes must match its normalized path",
                FlowtreeArtifacts.isExcludedFromCommit(".flowtree/keepme.txt", whitelist));
    }

    /**
     * All three unconventional spellings can coexist in one whitelist and each
     * still resolves to its own normalized path, while a non-whitelisted
     * {@code .flowtree/} path remains excluded regardless.
     */
    @Test(timeout = 30000)
    public void mixedSpellingsAllResolveWhileNonWhitelistedPathStaysExcluded() {
        Set<String> whitelist = Set.of(
                "./.flowtree/a.txt",
                "/.flowtree/b.txt",
                ".flowtree\\c.txt");

        assertFalse(FlowtreeArtifacts.isExcludedFromCommit(".flowtree/a.txt", whitelist));
        assertFalse(FlowtreeArtifacts.isExcludedFromCommit(".flowtree/b.txt", whitelist));
        assertFalse(FlowtreeArtifacts.isExcludedFromCommit(".flowtree/c.txt", whitelist));

        assertTrue("a .flowtree/ path with no matching whitelist entry, in any spelling, "
                        + "must still be excluded",
                FlowtreeArtifacts.isExcludedFromCommit(".flowtree/not-whitelisted.txt", whitelist));
        assertFalse("paths outside .flowtree/ are never gated, whitelist normalization notwithstanding",
                FlowtreeArtifacts.isExcludedFromCommit("README.md", whitelist));
    }
}
