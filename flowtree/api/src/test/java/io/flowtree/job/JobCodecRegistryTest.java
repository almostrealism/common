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
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.ServiceConfigurationError;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Verifies that {@link JobCodecRegistry} selects a codec by format id and that
 * the {@link JobFactory#decode(String)} and {@link JobFactory#toWire()} paths
 * honour the registry: an unknown format id is rejected naming the id, a class
 * the selected codec does not {@link JobCodec#supports(Class) support} is never
 * instantiated, and a sender whose chosen codec does not support its class fails
 * at the sender.
 *
 * @author Michael Murray
 */
public class JobCodecRegistryTest extends TestSuiteBase {

    /** Format id of a codec that accepts nothing, used to prove the supports() gate rejects before instantiation. */
    private static final String REJECTING_FORMAT = "reject-fac-test";

    /** Registers the rejecting codec once for the whole class. */
    @BeforeClass
    public static void registerRejectingCodec() {
        JobCodecRegistry.getInstance().register(new RejectingCodec());
    }

    /** The built-in string codec is always registered. */
    @Test(timeout = 60000)
    public void stringCodecAlwaysPresent() {
        JobCodec codec = JobCodecRegistry.getInstance().forFormat(StringJobCodec.FORMAT_ID);

        Assert.assertTrue(codec instanceof StringJobCodec);
    }

    /** An unregistered format id is rejected with a message naming the id. */
    @Test(timeout = 60000)
    public void unknownFormatIdRejectedNamingId() {
        try {
            JobCodecRegistry.getInstance().forFormat("no-such-format-xyz");
            Assert.fail("expected UnsupportedWireFormatException");
        } catch (UnsupportedWireFormatException e) {
            Assert.assertTrue("message must name the format id: " + e.getMessage(),
                    e.getMessage().contains("no-such-format-xyz"));
        }
    }

    /** decode() of an unknown-format envelope returns null and never constructs the named class. */
    @Test(timeout = 60000)
    public void decodeUnknownFormatReturnsNullWithoutInstantiating() {
        TrackingFactory.INSTANCES.set(0);
        String payload = "@wire=no-such-format-xyz::" + TrackingFactory.class.getName() + "::QUJD";

        Assert.assertNull(JobFactory.decode(payload));
        Assert.assertEquals(0, TrackingFactory.INSTANCES.get());
    }

    /** decode() for a codec that does not support the named class returns null and never constructs it. */
    @Test(timeout = 60000)
    public void decodeUnsupportedClassReturnsNullWithoutInstantiating() {
        TrackingFactory.INSTANCES.set(0);
        String payload = "@wire=" + REJECTING_FORMAT + "::" + TrackingFactory.class.getName() + "::QUJD";

        Assert.assertNull(JobFactory.decode(payload));
        Assert.assertEquals(0, TrackingFactory.INSTANCES.get());
    }

    /**
     * decode() of a payload whose marker is structurally malformed returns null
     * rather than propagating the parse failure, and never constructs the class.
     */
    @Test(timeout = 60000)
    public void decodeMalformedMarkerReturnsNullWithoutInstantiating() {
        TrackingFactory.INSTANCES.set(0);
        String name = TrackingFactory.class.getName();

        Assert.assertNull(JobFactory.decode("@wire=coding-pb1"));
        Assert.assertNull(JobFactory.decode("@wire=coding-pb1::" + name));
        Assert.assertNull(JobFactory.decode("@wire=::" + name + "::QUJD"));
        Assert.assertNull(JobFactory.decode("@wire=Coding_PB::" + name + "::QUJD"));
        Assert.assertEquals(0, TrackingFactory.INSTANCES.get());
    }

    /** Job.decode() of a structurally malformed marker returns null rather than throwing. */
    @Test(timeout = 60000)
    public void jobDecodeMalformedMarkerReturnsNull() {
        Assert.assertNull(Job.decode("@wire=coding-pb1"));
        Assert.assertNull(Job.decode("@wire=coding-pb1::a.b.C"));
        Assert.assertNull(Job.decode("@wire=::a.b.C::QUJD"));
        Assert.assertNull(Job.decode("@wire=Coding_PB::a.b.C::QUJD"));
        Assert.assertNull(Job.decode(null));
    }

    /**
     * A codec whose format id falls outside {@code [a-z0-9-]+} is refused at
     * registration, so it can never render a marker that fails to round-trip,
     * and the refused id is not left registered.
     */
    @Test(timeout = 60000)
    public void registerRejectsMalformedFormatIds() {
        for (String bad : new String[] { "foo::bar", "coding_pb", "Upper", "", "a b" }) {
            try {
                JobCodecRegistry.getInstance().register(new RejectingCodec() {
                    @Override
                    public String getFormatId() { return bad; }
                });
                Assert.fail("expected IllegalArgumentException for format id '" + bad + "'");
            } catch (IllegalArgumentException e) {
                Assert.assertTrue("message must name the id: " + e.getMessage(),
                        e.getMessage().contains("'" + bad + "'"));
            }

            try {
                JobCodecRegistry.getInstance().forFormat(bad);
                Assert.fail("format id '" + bad + "' must not have been registered");
            } catch (UnsupportedWireFormatException expected) {
                // the refused id stays unregistered
            }
        }
    }

    /** A codec with a null format id is refused at registration. */
    @Test(timeout = 60000)
    public void registerRejectsNullFormatId() {
        try {
            JobCodecRegistry.getInstance().register(new RejectingCodec() {
                @Override
                public String getFormatId() { return null; }
            });
            Assert.fail("expected IllegalArgumentException for a null format id");
        } catch (IllegalArgumentException e) {
            Assert.assertTrue(e.getMessage().contains("'null'"));
        }
    }

    /** A sender whose chosen codec does not support its class fails at toWire(), not on the receiver. */
    @Test(timeout = 60000)
    public void toWireFailsWhenCodecRejectsSenderClass() {
        TrackingFactory factory = new TrackingFactory();
        factory.wireFormat = REJECTING_FORMAT;

        try {
            factory.toWire();
            Assert.fail("expected UnsupportedWireFormatException");
        } catch (UnsupportedWireFormatException e) {
            Assert.assertTrue(e.getMessage().contains(REJECTING_FORMAT));
        }
    }

    /** Under the default string format toWire() equals encode() exactly. */
    @Test(timeout = 60000)
    public void toWireIsByteIdenticalToEncodeForStringFormat() {
        TrackingFactory factory = new TrackingFactory();

        Assert.assertEquals(factory.encode(), factory.toWire());
    }

    /**
     * A provider with a malformed format id, a duplicate of the string codec and
     * a provider whose {@code getFormatId()} throws are each skipped while the
     * registry is built; the string codec and the valid provider listed after
     * them are still registered.
     */
    @Test(timeout = 60000)
    public void badProvidersAreSkippedWithoutPoisoningRegistry() {
        JobCodec malformed = new RejectingCodec() {
            @Override
            public String getFormatId() { return "Bad::Id"; }
        };
        JobCodec throwing = new RejectingCodec() {
            @Override
            public String getFormatId() { throw new IllegalStateException("broken codec"); }
        };
        JobCodec valid = new RejectingCodec() {
            @Override
            public String getFormatId() { return "valid-after-bad"; }
        };

        JobCodecRegistry registry = new JobCodecRegistry(
                Arrays.asList(malformed, new StringJobCodec(), throwing, valid));

        Assert.assertTrue(registry.forFormat(StringJobCodec.FORMAT_ID) instanceof StringJobCodec);
        Assert.assertSame(valid, registry.forFormat("valid-after-bad"));
        try {
            registry.forFormat("Bad::Id");
            Assert.fail("the malformed provider must not have been registered");
        } catch (UnsupportedWireFormatException expected) {
            // skipped provider leaves its format unsupported
        }
    }

    /**
     * A provider iterator that fails with {@link ServiceConfigurationError} (a
     * broken service entry) stops the loading without failing construction, and
     * the codecs registered before the failure remain usable.
     */
    @Test(timeout = 60000)
    public void brokenProviderEntryKeepsEarlierCodecs() {
        JobCodec first = new RejectingCodec() {
            @Override
            public String getFormatId() { return "before-broken"; }
        };
        Iterable<JobCodec> providers = () -> new Iterator<JobCodec>() {
            private int position;

            @Override
            public boolean hasNext() {
                if (position == 1) throw new ServiceConfigurationError("broken provider entry");
                return position == 0;
            }

            @Override
            public JobCodec next() {
                position++;
                return first;
            }
        };

        JobCodecRegistry registry = new JobCodecRegistry(providers);

        Assert.assertTrue(registry.forFormat(StringJobCodec.FORMAT_ID) instanceof StringJobCodec);
        Assert.assertSame(first, registry.forFormat("before-broken"));
    }

    /** A registry built with no additional providers holds exactly the string codec. */
    @Test(timeout = 60000)
    public void emptyProvidersYieldStringCodecOnly() {
        JobCodecRegistry registry = new JobCodecRegistry(Collections.emptyList());

        Assert.assertTrue(registry.forFormat(StringJobCodec.FORMAT_ID) instanceof StringJobCodec);
        try {
            registry.forFormat(REJECTING_FORMAT);
            Assert.fail("only the string codec should be registered");
        } catch (UnsupportedWireFormatException e) {
            Assert.assertTrue(e.getMessage().contains("[" + StringJobCodec.FORMAT_ID + "]"));
        }
    }

    /**
     * The shared decode path hands the decoder the resolved class and the
     * payload, and returns the decoder's result unchanged.
     */
    @Test(timeout = 60000)
    public void sharedDecodePassesResolvedTypeAndPayload() {
        String payload = TrackingFactory.class.getName() + "::taskId:=track1";
        JobFactory expected = new TrackingFactory();

        JobFactory result = JobCodecRegistry.getInstance().decode(JobFactory.class, payload,
                (codec, type, body) -> {
                    Assert.assertTrue(codec instanceof StringJobCodec);
                    Assert.assertEquals(TrackingFactory.class, type);
                    Assert.assertEquals(payload, body);
                    return expected;
                });

        Assert.assertSame(expected, result);
    }

    /** A decoder that throws yields null from the shared decode path rather than propagating. */
    @Test(timeout = 60000)
    public void sharedDecodeReturnsNullWhenDecoderThrows() {
        String payload = TrackingFactory.class.getName() + "::taskId:=track1";

        Assert.assertNull(JobCodecRegistry.getInstance().decode(JobFactory.class, payload,
                (codec, type, body) -> { throw new IllegalStateException("decode failed"); }));
    }

    /**
     * A payload naming a class that is not the requested target type is
     * rejected before the decoder runs, so a factory payload never decodes as a
     * job and is never instantiated.
     */
    @Test(timeout = 60000)
    public void sharedDecodeRejectsClassOfWrongTargetType() {
        TrackingFactory.INSTANCES.set(0);
        String payload = TrackingFactory.class.getName() + "::taskId:=track1";

        Assert.assertNull(Job.decode(payload));
        Assert.assertNull(JobCodecRegistry.getInstance().decode(Job.class, payload,
                (codec, type, body) -> {
                    Assert.fail("decoder must not run for a class of the wrong type");
                    return null;
                }));
        Assert.assertEquals(0, TrackingFactory.INSTANCES.get());
    }

    /** A payload whose named class does not exist yields null from both decode entry points. */
    @Test(timeout = 60000)
    public void decodeMissingClassReturnsNull() {
        Assert.assertNull(JobFactory.decode("io.flowtree.job.NoSuchFactory::taskId:=x"));
        Assert.assertNull(Job.decode("io.flowtree.job.NoSuchJob::taskId:=x"));
    }

    /** A {@link JobCodec} that supports no type, used to exercise the supports() gate. */
    public static class RejectingCodec implements JobCodec {
        @Override
        public String getFormatId() { return REJECTING_FORMAT; }

        @Override
        public boolean supports(Class<?> type) { return false; }

        @Override
        public String encodePayload(JobFactory factory) {
            throw new UnsupportedWireFormatException(REJECTING_FORMAT + " supports nothing");
        }

        @Override
        public String encodePayload(Job job) {
            throw new UnsupportedWireFormatException(REJECTING_FORMAT + " supports nothing");
        }

        @Override
        public JobFactory decodeFactory(Class<? extends JobFactory> type, String payload) {
            throw new UnsupportedWireFormatException(REJECTING_FORMAT + " supports nothing");
        }

        @Override
        public Job decodeJob(Class<? extends Job> type, String payload) {
            throw new UnsupportedWireFormatException(REJECTING_FORMAT + " supports nothing");
        }
    }

    /**
     * A minimal {@link JobFactory} that counts how many times it is reflectively
     * constructed, so a test can assert that a rejected payload never
     * instantiates the named class. It must be public with a public no-argument
     * constructor so {@link JobFactory#decode(String)} can instantiate it by name.
     */
    public static class TrackingFactory implements JobFactory {

        /** Counts reflective constructions across the JVM. */
        public static final AtomicInteger INSTANCES = new AtomicInteger();

        /** The sender's chosen wire format; defaults to the string format. */
        private String wireFormat = StringJobCodec.FORMAT_ID;

        /** Completed, never awaited, so {@link #getCompletableFuture()} is non-null. */
        private final CompletableFuture<Void> future = new CompletableFuture<>();

        /** Public no-argument constructor required for reflective instantiation; counts each call. */
        public TrackingFactory() { INSTANCES.incrementAndGet(); }

        @Override
        public String getWireFormat() { return wireFormat; }

        @Override
        public String getTaskId() { return "track1"; }

        @Override
        public Job nextJob() { return null; }

        @Override
        public Job createJob(String data) { return null; }

        @Override
        public void set(String key, String value) { }

        @Override
        public String encode() { return getClass().getName() + "::taskId:=track1"; }

        @Override
        public String getName() { return "TrackingFactory"; }

        @Override
        public double getCompleteness() { return 0.0; }

        @Override
        public boolean isComplete() { return false; }

        @Override
        public void setPriority(double p) { }

        @Override
        public double getPriority() { return 1.0; }

        @Override
        public CompletableFuture<Void> getCompletableFuture() { return future; }
    }
}
