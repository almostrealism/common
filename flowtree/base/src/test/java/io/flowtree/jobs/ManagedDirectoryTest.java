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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Tests for {@link ManagedDirectory}.
 */
public class ManagedDirectoryTest extends TestSuiteBase {

    /** Deleting removes the whole tree, and deleting an absent tree is a no-op. */
    @Test(timeout = 30000)
    public void deleteRemovesPopulatedTreeAndToleratesAbsence() throws IOException {
        Path root = Files.createTempDirectory("managed-directory");
        Path file = root.resolve("a/b/c.txt");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "c");
        ManagedDirectory directory = new ManagedDirectory(root);

        directory.delete();
        Assert.assertFalse("The tree must be deleted", Files.exists(root));

        directory.delete();
        Assert.assertEquals(root, directory.getPath());
    }
}
