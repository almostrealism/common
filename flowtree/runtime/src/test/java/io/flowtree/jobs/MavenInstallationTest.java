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
import org.junit.Assert;
import org.junit.Test;

/**
 * Tests for {@link MavenInstallation}: identifying the node's Maven from the
 * output of {@code mvn --version}.
 */
public class MavenInstallationTest extends TestSuiteBase {

    /** Only Maven 3.9 or later honours MAVEN_ARGS and the split local repository. */
    @Test(timeout = 30000)
    public void splitSupportFollowsMavenVersion() {
        Assert.assertTrue(new MavenInstallation("Apache Maven 3.9.10 (5f519b97)").supportsSplitRepository());
        Assert.assertTrue(new MavenInstallation("Apache Maven 4.0.0").supportsSplitRepository());
        Assert.assertFalse(new MavenInstallation("Apache Maven 3.6.3").supportsSplitRepository());
        Assert.assertFalse(new MavenInstallation("mvn: command not found").supportsSplitRepository());
        Assert.assertFalse(new MavenInstallation(null).supportsSplitRepository());
    }

    /** The node's installation is probed once and shared by every caller. */
    @Test(timeout = 120000)
    public void nodeInstallationIsProbedOnce() {
        Assert.assertSame(MavenInstallation.node(), MavenInstallation.node());
    }
}
