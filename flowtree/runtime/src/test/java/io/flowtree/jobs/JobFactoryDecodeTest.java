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

import io.flowtree.job.AbstractJobFactory;
import io.flowtree.job.Job;
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
     * Regression: an entry with no {@link JobFactory#KEY_VALUE_SEPARATOR} after
     * valid entries used to return the factory configured up to that point.
     * A partial factory must never be handed to a dispatcher, so the whole
     * blob decodes to {@code null} — whether the bad entry is last or is
     * followed by further valid entries that would otherwise be dropped.
     */
    @Test(timeout = 10000)
    public void entryWithoutKeyValueSeparatorDecodesToNull() {
        String valid = new CodingAgentJob.Factory("partial work").encode();
        assertNotNull("the unmodified blob decodes", JobFactory.decode(valid));

        assertNull(JobFactory.decode(valid + JobFactory.ENTRY_SEPARATOR + "noSeparator"));
        assertNull(JobFactory.decode(valid + JobFactory.ENTRY_SEPARATOR + "noSeparator"
                + JobFactory.ENTRY_SEPARATOR + "late" + JobFactory.KEY_VALUE_SEPARATOR + "value"));
    }

    /**
     * A blob ending in a dangling {@link JobFactory#ENTRY_SEPARATOR}, as a
     * truncated write would leave it, decodes to {@code null} rather than to
     * the factory configured by the entries before it.
     */
    @Test(timeout = 10000)
    public void danglingTrailingSeparatorDecodesToNull() {
        String valid = new CodingAgentJob.Factory("truncated work").encode();
        assertNull(JobFactory.decode(valid + JobFactory.ENTRY_SEPARATOR));
        assertNull(JobFactory.decode(CodingAgentJob.Factory.class.getName()
                + JobFactory.ENTRY_SEPARATOR));
    }

    /**
     * When the factory's own {@link JobFactory#set(String, String)} rejects an
     * entry, the decode fails as a whole: neither the entries applied before
     * the rejected one nor the factory instance escape to the caller.
     */
    @Test(timeout = 10000)
    public void rejectedEntryDecodesToNull() {
        String accepted = RejectingFactory.class.getName() + JobFactory.ENTRY_SEPARATOR
                + "first" + JobFactory.KEY_VALUE_SEPARATOR + "1";
        JobFactory control = JobFactory.decode(accepted);
        assertNotNull("a blob without the rejected key decodes", control);
        assertTrue(control instanceof RejectingFactory);

        assertNull(JobFactory.decode(accepted + JobFactory.ENTRY_SEPARATOR
                + RejectingFactory.REJECTED_KEY + JobFactory.KEY_VALUE_SEPARATOR + "x"));
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

    /**
     * {@link JobFactory#nextEntrySeparator(String, int)} returns a separator that
     * genuinely divides two entries unchanged.
     */
    @Test(timeout = 10000)
    public void nextEntrySeparatorReturnsDividingSeparator() {
        assertEquals(3, JobFactory.nextEntrySeparator("key" + JobFactory.ENTRY_SEPARATOR + "value",
                "key".length()));
    }

    /**
     * {@link JobFactory#nextEntrySeparator(String, int)} skips a separator that a
     * value escapes with a leading {@code /} and reports the next dividing one.
     */
    @Test(timeout = 10000)
    public void nextEntrySeparatorSkipsSlashEscapedSeparator() {
        String data = "a::/b::c";
        assertEquals(5, JobFactory.nextEntrySeparator(data, data.indexOf(JobFactory.ENTRY_SEPARATOR)));
    }

    /**
     * {@link JobFactory#nextEntrySeparator(String, int)} skips a separator that a
     * value escapes with a preceding {@code \} and reports the next dividing one.
     */
    @Test(timeout = 10000)
    public void nextEntrySeparatorSkipsBackslashEscapedSeparator() {
        String data = "a\\::b::c";
        assertEquals(5, JobFactory.nextEntrySeparator(data, data.indexOf(JobFactory.ENTRY_SEPARATOR)));
    }

    /**
     * {@link JobFactory#nextEntrySeparator(String, int)} returns a negative index
     * unchanged, which is how it terminates on a final entry whose second character
     * is {@code /} rather than restarting the search and spinning forever.
     */
    @Test(timeout = 10000)
    public void nextEntrySeparatorReturnsNegativeWhenNoneRemains() {
        assertTrue(JobFactory.nextEntrySeparator("a/b", "a/b".indexOf(JobFactory.ENTRY_SEPARATOR)) < 0);
        assertTrue(JobFactory.nextEntrySeparator("a/b", -1) < 0);
    }

    /**
     * A factory whose {@link #set(String, String)} throws for one key, standing
     * in for a real factory that validates the values it is given.
     */
    public static class RejectingFactory extends AbstractJobFactory {
        /** The key whose value {@link #set(String, String)} rejects. */
        public static final String REJECTED_KEY = "rejected";

        /** {@inheritDoc} Throws for {@link #REJECTED_KEY}. */
        @Override
        public void set(String key, String value) {
            if (REJECTED_KEY.equals(key)) {
                throw new IllegalArgumentException("Rejected key " + key);
            }
            super.set(key, value);
        }

        /** {@inheritDoc} Never produces a job. */
        @Override
        public Job nextJob() { return null; }

        /** {@inheritDoc} Always complete. */
        @Override
        public double getCompleteness() { return 1.0; }
    }
}
