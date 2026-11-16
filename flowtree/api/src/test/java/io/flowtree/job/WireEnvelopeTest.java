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

package io.flowtree.job;

import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

/**
 * Verifies the format-marker grammar owned by {@link WireEnvelope}: an unmarked
 * payload parses as the string format and renders back byte-identically, a
 * marked payload round-trips through {@link WireEnvelope#render()} and
 * {@link WireEnvelope#parse(String)}, and a structurally malformed marker is
 * rejected.
 *
 * @author Michael Murray
 */
public class WireEnvelopeTest extends TestSuiteBase {

    /** A representative unmarked string payload: class name then one entry. */
    private static final String UNMARKED = "io.flowtree.jobs.CodingAgentJob::taskId:=t7";

    /** An unmarked payload parses as the string format, keeping the whole string as its payload. */
    @Test(timeout = 60000)
    public void unmarkedParsesAsStringFormat() {
        WireEnvelope env = WireEnvelope.parse(UNMARKED);

        Assert.assertFalse(env.isMarked());
        Assert.assertEquals(StringJobCodec.FORMAT_ID, env.getFormatId());
        Assert.assertEquals("io.flowtree.jobs.CodingAgentJob", env.getClassName());
        Assert.assertEquals(UNMARKED, env.getPayload());
        Assert.assertEquals(UNMARKED, env.render());
    }

    /** An unmarked payload with no entry separator has a null class name and keeps its payload. */
    @Test(timeout = 60000)
    public void unmarkedWithoutSeparatorHasNullClassName() {
        WireEnvelope env = WireEnvelope.parse("io.flowtree.jobs.CodingAgentJob");

        Assert.assertFalse(env.isMarked());
        Assert.assertEquals(StringJobCodec.FORMAT_ID, env.getFormatId());
        Assert.assertNull(env.getClassName());
        Assert.assertEquals("io.flowtree.jobs.CodingAgentJob", env.getPayload());
    }

    /** A marked envelope renders with the marker and parses back to equal fields. */
    @Test(timeout = 60000)
    public void markedRoundTrips() {
        WireEnvelope made = new WireEnvelope("coding-pb1", "io.flowtree.jobs.CodingAgentJob", "QUJD");
        String rendered = made.render();

        Assert.assertEquals("@wire=coding-pb1::io.flowtree.jobs.CodingAgentJob::QUJD", rendered);

        WireEnvelope parsed = WireEnvelope.parse(rendered);
        Assert.assertTrue(parsed.isMarked());
        Assert.assertEquals("coding-pb1", parsed.getFormatId());
        Assert.assertEquals("io.flowtree.jobs.CodingAgentJob", parsed.getClassName());
        Assert.assertEquals("QUJD", parsed.getPayload());
    }

    /** A Base64 payload containing no separator survives round-trip unchanged. */
    @Test(timeout = 60000)
    public void markedPayloadPreservedVerbatim() {
        String payload = "AAAABBBBCCCCdddd+/==";
        WireEnvelope env = WireEnvelope.parse("@wire=test-fmt::a.b.C::" + payload);

        Assert.assertEquals(payload, env.getPayload());
    }

    /** The three-argument constructor leaves a string-format envelope unmarked and renders the payload verbatim. */
    @Test(timeout = 60000)
    public void stringFormatConstructorIsUnmarked() {
        WireEnvelope env = new WireEnvelope(StringJobCodec.FORMAT_ID, "a.b.C", "a.b.C::k:=v");

        Assert.assertFalse(env.isMarked());
        Assert.assertEquals("a.b.C::k:=v", env.render());
    }

    /** A marker missing the class separator is rejected. */
    @Test(timeout = 60000, expected = IllegalArgumentException.class)
    public void markerMissingClassSeparatorRejected() {
        WireEnvelope.parse("@wire=coding-pb1");
    }

    /** A marker missing the payload separator is rejected. */
    @Test(timeout = 60000, expected = IllegalArgumentException.class)
    public void markerMissingPayloadSeparatorRejected() {
        WireEnvelope.parse("@wire=coding-pb1::a.b.C");
    }

    /** A marker with an empty format id is rejected. */
    @Test(timeout = 60000, expected = IllegalArgumentException.class)
    public void markerWithEmptyFormatIdRejected() {
        WireEnvelope.parse("@wire=::a.b.C::QUJD");
    }

    /** A marker whose format id is outside {@code [a-z0-9-]+} is rejected. */
    @Test(timeout = 60000)
    public void markerWithInvalidFormatIdRejected() {
        for (String bad : new String[] { "Coding-PB1", "coding_pb", "a b", "x.y" }) {
            try {
                WireEnvelope.parse("@wire=" + bad + "::a.b.C::QUJD");
                Assert.fail("expected IllegalArgumentException for format id '" + bad + "'");
            } catch (IllegalArgumentException e) {
                Assert.assertTrue(e.getMessage().contains("'" + bad + "'"));
            }
        }
    }

    /** The constructor refuses a format id that would render a marker that does not round-trip. */
    @Test(timeout = 60000)
    public void constructorRejectsInvalidFormatId() {
        for (String bad : new String[] { "foo::bar", "coding_pb", "", null }) {
            try {
                new WireEnvelope(bad, "a.b.C", "QUJD");
                Assert.fail("expected IllegalArgumentException for format id '" + bad + "'");
            } catch (IllegalArgumentException expected) {
                // rejected before it can be rendered
            }
        }
    }

    /** isValidFormatId accepts exactly non-empty {@code [a-z0-9-]+} strings. */
    @Test(timeout = 60000)
    public void formatIdGrammar() {
        Assert.assertTrue(WireEnvelope.isValidFormatId("string"));
        Assert.assertTrue(WireEnvelope.isValidFormatId("coding-pb1"));
        Assert.assertTrue(WireEnvelope.isValidFormatId("-"));
        Assert.assertTrue(WireEnvelope.isValidFormatId("0"));
        Assert.assertFalse(WireEnvelope.isValidFormatId(null));
        Assert.assertFalse(WireEnvelope.isValidFormatId(""));
        Assert.assertFalse(WireEnvelope.isValidFormatId("foo::bar"));
        Assert.assertFalse(WireEnvelope.isValidFormatId("coding_pb"));
        Assert.assertFalse(WireEnvelope.isValidFormatId("Coding"));
        Assert.assertFalse(WireEnvelope.isValidFormatId("a\n"));
        Assert.assertEquals("coding-pb1", WireEnvelope.requireValidFormatId("coding-pb1"));
    }

    /** Parsing a null payload is rejected. */
    @Test(timeout = 60000, expected = IllegalArgumentException.class)
    public void nullPayloadRejected() {
        WireEnvelope.parse(null);
    }

    /**
     * A marked payload claiming the string format is rejected rather than
     * misparsed: {@code render()} never marks the string format, so such a
     * marker could only strip the class name and corrupt the remaining entries.
     */
    @Test(timeout = 60000)
    public void markedStringFormatRejected() {
        try {
            WireEnvelope.parse("@wire=string::io.flowtree.jobs.CodingAgentJob::taskId:=t7");
            Assert.fail("expected IllegalArgumentException for a marked string format");
        } catch (IllegalArgumentException e) {
            Assert.assertTrue(e.getMessage().contains("string format is never marked"));
        }
    }
}
