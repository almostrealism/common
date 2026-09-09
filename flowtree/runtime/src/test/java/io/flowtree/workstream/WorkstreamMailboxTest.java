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

package io.flowtree.workstream;

import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for {@link WorkstreamMailbox}, the ordered conversation log two
 * agent sessions collaborate through.
 */
public class WorkstreamMailboxTest extends TestSuiteBase {

    /** Identifier used for the mailbox under test. */
    private static final String WORKSTREAM = "ws-collab";

    /**
     * Creates a temporary directory that the test may write mailbox files into.
     *
     * @return the directory
     * @throws IOException if the directory cannot be created
     */
    private static Path directory() throws IOException {
        return Files.createTempDirectory("mailbox-test");
    }

    /**
     * Messages are handed back in the order they were appended, each carrying
     * the next sequence number.
     */
    @Test(timeout = 10000)
    public void appendAssignsSequentialOrder() {
        WorkstreamMailbox mailbox = new WorkstreamMailbox(WORKSTREAM, null);

        assertEquals(0, mailbox.head());
        assertEquals(1, mailbox.append("ready", "job:a", "a", null).seq());
        assertEquals(2, mailbox.append("proceed", "caller:human", null, null).seq());
        assertEquals(2, mailbox.head());

        WorkstreamMailbox.Delivery delivery = mailbox.read(0, null, 0);
        assertEquals(2, delivery.messages().size());
        assertEquals("ready", delivery.messages().get(0).text());
        assertEquals("proceed", delivery.messages().get(1).text());
        assertEquals(2, delivery.nextSince());
    }

    /** A read starting from a cursor returns only what follows it. */
    @Test(timeout = 10000)
    public void readSinceReturnsOnlyLaterMessages() {
        WorkstreamMailbox mailbox = new WorkstreamMailbox(WORKSTREAM, null);
        mailbox.append("first", "job:a", "a", null);
        mailbox.append("second", "job:a", "a", null);

        WorkstreamMailbox.Delivery delivery = mailbox.read(1, null, 0);
        assertEquals(1, delivery.messages().size());
        assertEquals("second", delivery.messages().get(0).text());
        assertEquals(2, delivery.nextSince());
    }

    /**
     * A negative cursor means "from here on", so a reader joining a
     * conversation in progress is not handed its history.
     */
    @Test(timeout = 10000)
    public void negativeSinceStartsAtTheHead() {
        WorkstreamMailbox mailbox = new WorkstreamMailbox(WORKSTREAM, null);
        mailbox.append("history", "job:a", "a", null);

        WorkstreamMailbox.Delivery delivery = mailbox.read(-1, null, 0);
        assertTrue(delivery.messages().isEmpty());
        assertEquals(1, delivery.nextSince());
    }

    /**
     * A participant does not hear its own messages, and the cursor still
     * advances past them so they are not rescanned on every read.
     */
    @Test(timeout = 10000)
    public void senderExclusionAdvancesTheCursor() {
        WorkstreamMailbox mailbox = new WorkstreamMailbox(WORKSTREAM, null);
        mailbox.append("mine", "job:a", "a", null);
        mailbox.append("theirs", "job:b", "b", null);
        mailbox.append("mine again", "job:a", "a", null);

        WorkstreamMailbox.Delivery delivery = mailbox.read(0, "job:a", 0);
        assertEquals(1, delivery.messages().size());
        assertEquals("theirs", delivery.messages().get(0).text());
        assertEquals(3, delivery.nextSince());

        assertTrue(mailbox.read(delivery.nextSince(), "job:a", 0).messages().isEmpty());
    }

    /**
     * A caught-up reader blocks, and is released by the next append rather than
     * by the expiry of its wait. The elapsed time is asserted only as an upper
     * bound well under the requested wait, so the test proves the wakeup
     * happened without depending on scheduler precision.
     */
    @Test(timeout = 10000)
    public void waitingReaderIsWokenByAppend() throws InterruptedException {
        WorkstreamMailbox mailbox = new WorkstreamMailbox(WORKSTREAM, null);

        Thread sender = new Thread(() -> {
            try {
                Thread.sleep(150);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            mailbox.append("late arrival", "job:b", "b", null);
        });

        long start = System.currentTimeMillis();
        sender.start();
        WorkstreamMailbox.Delivery delivery = mailbox.read(0, null, 30);
        long elapsed = System.currentTimeMillis() - start;
        sender.join();

        assertEquals(1, delivery.messages().size());
        assertEquals("late arrival", delivery.messages().get(0).text());
        assertTrue("read blocked for the full wait: " + elapsed, elapsed < 10000);
    }

    /** A read that nothing arrives for returns empty rather than failing. */
    @Test(timeout = 10000)
    public void waitElapsesToAnEmptyDelivery() {
        WorkstreamMailbox mailbox = new WorkstreamMailbox(WORKSTREAM, null);
        mailbox.append("only message", "job:a", "a", null);

        WorkstreamMailbox.Delivery delivery = mailbox.read(1, null, 1);
        assertTrue(delivery.messages().isEmpty());
        assertEquals(1, delivery.nextSince());
    }

    /** A wait longer than the cap is clamped rather than honoured. */
    @Test(timeout = 20000)
    public void waitIsCappedAtTheMaximum() {
        WorkstreamMailbox mailbox = new WorkstreamMailbox(WORKSTREAM, null);

        long start = System.currentTimeMillis();
        assertTrue(mailbox.read(0, null, 1).messages().isEmpty());
        long elapsed = System.currentTimeMillis() - start;

        assertTrue("wait exceeded the cap", elapsed < WorkstreamMailbox.MAX_WAIT_SECONDS * 1000L);
    }

    /** A conversation is readable again after the controller restarts. */
    @Test(timeout = 10000)
    public void messagesSurviveAReload() throws IOException {
        File file = directory().resolve(WORKSTREAM + ".ndjson").toFile();

        WorkstreamMailbox original = new WorkstreamMailbox(WORKSTREAM, file);
        original.append("prepare the host", "caller:human", null, null);
        original.append("host is ready", "job:b", "b", "primary");

        WorkstreamMailbox reloaded = new WorkstreamMailbox(WORKSTREAM, file);
        WorkstreamMailbox.Delivery delivery = reloaded.read(0, null, 0);

        assertEquals(2, delivery.messages().size());
        assertEquals("prepare the host", delivery.messages().get(0).text());
        assertEquals("job:b", delivery.messages().get(1).sender());
        assertEquals("b", delivery.messages().get(1).jobId());
        assertEquals("primary", delivery.messages().get(1).activity());
        assertEquals(2, reloaded.head());
        assertEquals(3, reloaded.append("next", "job:b", "b", null).seq());
    }

    /** Text that would break the stored line survives a round trip intact. */
    @Test(timeout = 10000)
    public void quotesAndNewlinesSurviveAReload() throws IOException {
        File file = directory().resolve(WORKSTREAM + ".ndjson").toFile();
        String text = "line one\nline \"two\"\tend\\";

        new WorkstreamMailbox(WORKSTREAM, file).append(text, "job:a", "a", null);

        WorkstreamMailbox reloaded = new WorkstreamMailbox(WORKSTREAM, file);
        assertEquals(text, reloaded.read(0, null, 0).messages().get(0).text());
    }

    /** A line that is not a usable message is skipped rather than fatal. */
    @Test(timeout = 10000)
    public void unusableStoredLinesAreSkipped() throws IOException {
        File file = directory().resolve(WORKSTREAM + ".ndjson").toFile();
        Files.write(file.toPath(),
                ("not json\n" + "{\"seq\":4,\"text\":\"kept\",\"sender\":\"job:a\"}\n")
                        .getBytes(StandardCharsets.UTF_8));

        WorkstreamMailbox mailbox = new WorkstreamMailbox(WORKSTREAM, file);
        assertEquals(1, mailbox.size());
        assertEquals("kept", mailbox.read(0, null, 0).messages().get(0).text());
        assertEquals(5, mailbox.append("after", "job:b", "b", null).seq());
    }

    /** A message past the retention window is dropped when the log is loaded. */
    @Test(timeout = 10000)
    public void expiredMessagesAreDroppedOnLoad() throws IOException {
        File file = directory().resolve(WORKSTREAM + ".ndjson").toFile();
        long expired = System.currentTimeMillis() - WorkstreamMailbox.RETENTION_MILLIS - 1000;

        Files.write(file.toPath(), (new WorkstreamMailbox.Message(
                        1, expired, "job:a", "a", null, "ancient").toJson() + "\n"
                + new WorkstreamMailbox.Message(
                        2, System.currentTimeMillis(), "job:a", "a", null, "recent").toJson() + "\n")
                .getBytes(StandardCharsets.UTF_8));

        WorkstreamMailbox mailbox = new WorkstreamMailbox(WORKSTREAM, file);
        assertEquals(1, mailbox.size());
        assertEquals("recent", mailbox.read(0, null, 0).messages().get(0).text());
        assertFalse(Files.readString(file.toPath()).contains("ancient"));
    }

    /** A message with no sender is attributed rather than left unidentified. */
    @Test(timeout = 10000)
    public void missingSenderIsAttributed() {
        WorkstreamMailbox mailbox = new WorkstreamMailbox(WORKSTREAM, null);
        assertEquals("unknown", mailbox.append("anonymous", null, null, null).sender());
    }

    /**
     * A stored line with no sender decodes to the same attribution
     * {@link WorkstreamMailbox#append} gives one, so a message's identity does
     * not depend on whether it has been through the file yet.
     */
    @Test(timeout = 10000)
    public void storedLineWithoutSenderDecodesAsAttributed() {
        assertEquals("unknown",
                WorkstreamMailbox.Message.fromJson("{\"seq\":1,\"text\":\"ok\"}").sender());
        assertEquals("unknown", WorkstreamMailbox.Message
                .fromJson("{\"seq\":1,\"text\":\"ok\",\"sender\":\"\"}").sender());
    }

    /** A decoded message renders again without losing its attribution. */
    @Test(timeout = 10000)
    public void senderlessLineSurvivesReloadAndCompaction() throws IOException {
        File file = directory().resolve(WORKSTREAM + ".ndjson").toFile();
        Files.write(file.toPath(),
                "{\"seq\":1,\"text\":\"legacy\"}\n".getBytes(StandardCharsets.UTF_8));

        WorkstreamMailbox mailbox = new WorkstreamMailbox(WORKSTREAM, file);
        assertEquals("unknown", mailbox.read(0, null, 0).messages().get(0).sender());
        assertTrue(mailbox.read(0, null, 0).toJson().contains("\"sender\":\"unknown\""));
    }

    /** A stored line without a usable sequence or body is not a message. */
    @Test(timeout = 10000)
    public void malformedMessagesDoNotParse() {
        assertNull(WorkstreamMailbox.Message.fromJson(null));
        assertNull(WorkstreamMailbox.Message.fromJson("   "));
        assertNull(WorkstreamMailbox.Message.fromJson("{\"seq\":1}"));
        assertNull(WorkstreamMailbox.Message.fromJson("{\"text\":\"no seq\"}"));
        assertNotNull(WorkstreamMailbox.Message.fromJson("{\"seq\":1,\"text\":\"ok\"}"));
    }

    /** The registry hands back one mailbox per workstream, created on demand. */
    @Test(timeout = 10000)
    public void registryResolvesOneMailboxPerWorkstream() throws IOException {
        MailboxRegistry registry = new MailboxRegistry(directory().toFile());

        WorkstreamMailbox first = registry.mailboxFor("one");
        assertTrue(first == registry.mailboxFor("one"));
        assertFalse(first == registry.mailboxFor("two"));
        assertEquals("one", first.getWorkstreamId());
    }

    /** A registry with no directory still carries a conversation. */
    @Test(timeout = 10000)
    public void memoryOnlyRegistryStillDelivers() {
        MailboxRegistry registry = new MailboxRegistry();
        assertNull(registry.getDirectory());

        registry.mailboxFor("one").append("hello", "job:a", "a", null);
        assertEquals(1, registry.mailboxFor("one").read(0, null, 0).messages().size());
    }

    /** The wire form of a delivery names both the messages and the next cursor. */
    @Test(timeout = 10000)
    public void deliveryRendersTheReadersNextCursor() {
        WorkstreamMailbox mailbox = new WorkstreamMailbox(WORKSTREAM, null);
        mailbox.append("hello", "job:a", "a", null);

        String json = mailbox.read(0, null, 0).toJson();
        assertTrue(json.contains("\"ok\":true"));
        assertTrue(json.contains("\"text\":\"hello\""));
        assertTrue(json.contains("\"sender\":\"job:a\""));
        assertTrue(json.endsWith("\"nextSince\":1}"));
    }
}
