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

import io.flowtree.job.JobFactory;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Covers the failure contract of {@link JobFactory#decode(String)}: every
 * malformed input yields {@code null} rather than an exception or a hang. The
 * decoder runs at controller startup on persisted recovery blobs, so a single
 * bad blob that threw or spun would stop the controller from coming up.
 */
public class JobFactoryDecodeTest extends TestSuiteBase {

    /** A {@code null} blob decodes to {@code null}. */
    @Test(timeout = 10000)
    public void nullDecodesToNull() {
        assertNull(JobFactory.decode(null));
    }

    /** A blob with no entry separator (not even a class name token) decodes to {@code null}. */
    @Test(timeout = 10000)
    public void blobWithoutSeparatorDecodesToNull() {
        assertNull(JobFactory.decode("io.flowtree.jobs.CodingAgentJob$Factory"));
        assertNull(JobFactory.decode(""));
    }

    /** A blob naming a class that does not exist decodes to {@code null}. */
    @Test(timeout = 10000)
    public void unknownClassDecodesToNull() {
        assertNull(JobFactory.decode("com.example.DoesNotExist" + JobFactory.ENTRY_SEPARATOR
                + "id" + JobFactory.KEY_VALUE_SEPARATOR + "x"));
    }

    /** A blob naming a class that is not a {@link JobFactory} decodes to {@code null}. */
    @Test(timeout = 10000)
    public void nonFactoryClassDecodesToNull() {
        assertNull(JobFactory.decode("java.lang.StringBuilder" + JobFactory.ENTRY_SEPARATOR
                + "id" + JobFactory.KEY_VALUE_SEPARATOR + "x"));
    }

    /**
     * Regression: when the final entry's second character is {@code /}, the
     * separator scan used to restart from the beginning of the entry after
     * finding no further separator, and never terminated. The decoder must
     * finish and apply every entry, including that last one.
     */
    @Test(timeout = 10000)
    public void finalEntryWithSlashAsSecondCharacterTerminates() {
        CodingAgentJob.Factory original = new CodingAgentJob.Factory("slash work");
        original.setTargetBranch("feature/slash");
        String blob = original.encode() + JobFactory.ENTRY_SEPARATOR
                + "a/b" + JobFactory.KEY_VALUE_SEPARATOR + "c";

        JobFactory decoded = JobFactory.decode(blob);

        assertNotNull("a trailing slash-bearing entry must not abort decoding", decoded);
        assertEquals(original.getTaskId(), decoded.getTaskId());
        assertEquals("feature/slash", ((CodingAgentJob.Factory) decoded).getTargetBranch());
        assertTrue("the final entry is applied",
                decoded.encode().contains("a/b" + JobFactory.KEY_VALUE_SEPARATOR + "c"));
    }
}
