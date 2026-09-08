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

import io.flowtree.JsonFieldExtractor;
import org.almostrealism.io.ConsoleFeatures;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The ordered message log of a single workstream, and the means by which two
 * agent sessions hold a conversation.
 *
 * <p>Every message posted to a workstream is appended here and assigned a
 * monotonically increasing {@code seq}, so the log is totally ordered. Readers
 * hold no state on this side: each read names the {@code seq} it has already
 * seen and receives everything after it, together with the {@code seq} to name
 * next. That makes a reader's cursor its own business, and lets a job submitted
 * days later replay the whole conversation by starting from zero.</p>
 *
 * <p>{@link #read} may block. A reader that has caught up waits on this
 * mailbox's monitor and is woken by the next {@link #append}, so a message
 * reaches a waiting peer in a round trip rather than at the end of a polling
 * interval. The wait is bounded by {@link #MAX_WAIT_SECONDS} because the caller
 * is an HTTP request thread; longer waits are the caller's business to compose
 * out of several reads.</p>
 *
 * <p>Messages are persisted as NDJSON, one file per workstream, so a controller
 * restart does not lose an instruction that nobody has read yet. They are
 * retained for {@link #RETENTION_MILLIS} — a conversation is not an archive,
 * and the {@code messages} memory namespace already keeps one.</p>
 *
 * @author Michael Murray
 * @see MailboxRegistry
 */
public class WorkstreamMailbox implements ConsoleFeatures {

    /** Longest a single {@link #read} will block, in seconds. */
    public static final int MAX_WAIT_SECONDS = 120;

    /** Age at which a message is dropped from the log. */
    public static final long RETENTION_MILLIS = 7L * 24 * 60 * 60 * 1000;

    /**
     * Readers permitted to wait on one mailbox at a time. Each waiter occupies
     * an HTTP request thread, so the ceiling keeps a runaway client from
     * exhausting the server's pool.
     */
    public static final int MAX_WAITERS = 32;

    /** Identifier of the workstream this mailbox belongs to. */
    private final String workstreamId;

    /** Backing NDJSON file, or {@code null} when this mailbox is memory-only. */
    private final File file;

    /** The retained messages, oldest first. Guarded by {@code this}. */
    private final List<Message> messages;

    /** The {@code seq} the next appended message will receive. Guarded by {@code this}. */
    private long nextSeq;

    /** Readers currently blocked in {@link #read}. Guarded by {@code this}. */
    private int waiters;

    /**
     * Constructs a mailbox, loading any persisted messages that are still
     * within the retention window.
     *
     * @param workstreamId identifier of the owning workstream
     * @param file         backing NDJSON file, or {@code null} for a
     *                     memory-only mailbox
     */
    public WorkstreamMailbox(String workstreamId, File file) {
        if (workstreamId == null || workstreamId.isEmpty()) {
            throw new IllegalArgumentException("workstreamId must not be null or empty");
        }

        this.workstreamId = workstreamId;
        this.file = file;
        this.messages = new ArrayList<>();
        this.nextSeq = 1;

        load();
    }

    /**
     * Returns the identifier of the workstream this mailbox belongs to.
     *
     * @return the workstream identifier; never {@code null}
     */
    public String getWorkstreamId() { return workstreamId; }

    /**
     * Returns the highest {@code seq} assigned so far, which is also the
     * cursor a reader names to receive only messages sent from now on.
     *
     * @return the highest assigned {@code seq}, or {@code 0} when empty
     */
    public synchronized long head() { return nextSeq - 1; }

    /**
     * Returns the number of retained messages.
     *
     * @return the retained message count
     */
    public synchronized int size() { return messages.size(); }

    /**
     * Appends a message and wakes every reader waiting for one.
     *
     * @param text     the message body; must not be {@code null} or empty
     * @param sender   identity of the sender, used by readers to skip their own
     *                 messages; {@code null} becomes {@code "unknown"}
     * @param jobId    job the message was sent from, or {@code null}
     * @param activity enforcement phase the message belongs to, or {@code null}
     * @return the appended message, carrying its assigned {@code seq}
     */
    public synchronized Message append(String text, String sender, String jobId, String activity) {
        if (text == null || text.isEmpty()) {
            throw new IllegalArgumentException("text must not be null or empty");
        }

        Message message = new Message(nextSeq++, System.currentTimeMillis(),
                sender == null || sender.isEmpty() ? "unknown" : sender,
                jobId, activity, text);
        messages.add(message);

        if (pruneExpired()) {
            rewrite();
        } else {
            persist(message);
        }

        notifyAll();
        return message;
    }

    /**
     * Reads the messages that follow {@code since}, blocking for up to
     * {@code waitSeconds} when there are none.
     *
     * <p>A negative {@code since} means "from here on": it resolves to the
     * current {@link #head()} before waiting, so a reader joining a long
     * conversation is not handed its whole history.</p>
     *
     * <p>{@code excludeSender} is how a participant avoids hearing its own
     * echo. Excluded messages still advance {@link Delivery#nextSince()}, so a
     * reader that only ever talks to itself does not rescan them forever.</p>
     *
     * @param since         the highest {@code seq} already seen, or negative to
     *                      start from the current head
     * @param excludeSender sender whose messages to skip, or {@code null} to
     *                      receive every message
     * @param waitSeconds   how long to block when nothing new is available;
     *                      clamped to {@link #MAX_WAIT_SECONDS}
     * @return the delivery, possibly empty when the wait elapsed
     */
    public synchronized Delivery read(long since, String excludeSender, int waitSeconds) {
        long cursor = since < 0 ? head() : since;
        long deadline = System.currentTimeMillis()
                + Math.min(Math.max(waitSeconds, 0), MAX_WAIT_SECONDS) * 1000L;

        while (true) {
            Delivery delivery = since(cursor, excludeSender);
            if (!delivery.messages().isEmpty()) return delivery;

            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0 || waiters >= MAX_WAITERS) return delivery;

            waiters++;

            try {
                wait(remaining);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return delivery;
            } finally {
                waiters--;
            }
        }
    }

    /**
     * Collects the retained messages that follow {@code cursor} without
     * waiting.
     *
     * @param cursor        the highest {@code seq} already seen
     * @param excludeSender sender whose messages to skip, or {@code null}
     * @return the delivery; empty when nothing follows {@code cursor}
     */
    private synchronized Delivery since(long cursor, String excludeSender) {
        List<Message> found = new ArrayList<>();
        long next = cursor;

        for (Message message : messages) {
            if (message.seq() <= cursor) continue;

            next = message.seq();
            if (excludeSender != null && excludeSender.equals(message.sender())) continue;

            found.add(message);
        }

        return new Delivery(Collections.unmodifiableList(found), Math.max(next, cursor));
    }

    /**
     * Drops messages that have outlived {@link #RETENTION_MILLIS}.
     *
     * @return {@code true} when at least one message was dropped
     */
    private synchronized boolean pruneExpired() {
        long oldest = System.currentTimeMillis() - RETENTION_MILLIS;
        return messages.removeIf(message -> message.createdAtMillis() < oldest);
    }

    /** Loads persisted messages, discarding any that have expired or cannot be parsed. */
    private synchronized void load() {
        if (file == null || !file.isFile()) return;

        List<String> lines;

        try {
            lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            warn("Unable to read mailbox for " + workstreamId + ": " + e.getMessage());
            return;
        }

        for (String line : lines) {
            Message message = Message.fromJson(line);
            if (message == null) continue;

            messages.add(message);
            nextSeq = Math.max(nextSeq, message.seq() + 1);
        }

        if (pruneExpired()) {
            rewrite();
        }
    }

    /**
     * Appends one message to the backing file.
     *
     * @param message the message to write
     */
    private void persist(Message message) {
        if (file == null) return;

        try {
            File parent = file.getParentFile();
            if (parent != null) parent.mkdirs();

            try (BufferedWriter writer = Files.newBufferedWriter(file.toPath(),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
                writer.write(message.toJson());
                writer.newLine();
            }
        } catch (IOException e) {
            warn("Unable to persist mailbox message for " + workstreamId + ": " + e.getMessage());
        }
    }

    /** Rewrites the backing file from the retained messages, compacting away expired ones. */
    private synchronized void rewrite() {
        if (file == null) return;

        try {
            File parent = file.getParentFile();
            if (parent != null) parent.mkdirs();

            try (BufferedWriter writer = Files.newBufferedWriter(file.toPath(),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                for (Message message : messages) {
                    writer.write(message.toJson());
                    writer.newLine();
                }
            }
        } catch (IOException e) {
            warn("Unable to compact mailbox for " + workstreamId + ": " + e.getMessage());
        }
    }

    /**
     * What a {@link WorkstreamMailbox#read} returned: the messages the reader
     * had not seen, and the cursor to name on its next read.
     *
     * <p>{@code nextSince} advances past messages that were filtered out as
     * well as those delivered, so it is not simply the {@code seq} of the last
     * entry in {@code messages}.</p>
     *
     * @param messages  the delivered messages, oldest first
     * @param nextSince the cursor for the reader's next read
     */
    public record Delivery(List<Message> messages, long nextSince) {

        /**
         * Renders this delivery as the body of a mailbox read response.
         *
         * @return a JSON object with {@code ok}, {@code messages}, and
         *         {@code nextSince}
         */
        public String toJson() {
            StringBuilder out = new StringBuilder("{\"ok\":true,\"messages\":[");

            for (int i = 0; i < messages.size(); i++) {
                if (i > 0) out.append(',');
                out.append(messages.get(i).toJson());
            }

            return out.append("],\"nextSince\":").append(nextSince).append('}').toString();
        }
    }

    /**
     * One message on a workstream's log.
     *
     * @param seq             position in the total order, assigned on append
     * @param createdAtMillis wall-clock time the message was appended
     * @param sender          identity of the sender
     * @param jobId           job the message was sent from, or {@code null}
     * @param activity        enforcement phase, or {@code null} for primary work
     * @param text            the message body
     */
    public record Message(long seq, long createdAtMillis, String sender,
                          String jobId, String activity, String text) {

        /**
         * Renders this message as a JSON object, which is both its wire form
         * and the line stored in the backing file.
         *
         * @return the JSON representation
         */
        public String toJson() {
            StringBuilder out = new StringBuilder("{\"seq\":").append(seq)
                    .append(",\"createdAt\":\"")
                    .append(Instant.ofEpochMilli(createdAtMillis))
                    .append("\",\"sender\":\"").append(JsonFieldExtractor.escapeJson(sender))
                    .append('"');

            if (jobId != null && !jobId.isEmpty()) {
                out.append(",\"jobId\":\"").append(JsonFieldExtractor.escapeJson(jobId)).append('"');
            }

            if (activity != null && !activity.isEmpty()) {
                out.append(",\"activity\":\"")
                        .append(JsonFieldExtractor.escapeJson(activity)).append('"');
            }

            return out.append(",\"text\":\"").append(JsonFieldExtractor.escapeJson(text))
                    .append("\"}").toString();
        }

        /**
         * Parses a message from one line of a mailbox file.
         *
         * @param json the stored JSON object
         * @return the message, or {@code null} when {@code json} is not a
         *         usable message
         */
        public static Message fromJson(String json) {
            if (json == null || json.isBlank()) return null;

            long seq = JsonFieldExtractor.extractLong(json, "seq");
            String text = JsonFieldExtractor.extractString(json, "text");
            if (seq <= 0 || text == null || text.isEmpty()) return null;

            String createdAt = JsonFieldExtractor.extractString(json, "createdAt");
            long createdAtMillis;

            try {
                createdAtMillis = createdAt == null
                        ? System.currentTimeMillis() : Instant.parse(createdAt).toEpochMilli();
            } catch (DateTimeParseException e) {
                createdAtMillis = System.currentTimeMillis();
            }

            return new Message(seq, createdAtMillis,
                    JsonFieldExtractor.extractString(json, "sender"),
                    JsonFieldExtractor.extractString(json, "jobId"),
                    JsonFieldExtractor.extractString(json, "activity"),
                    text);
        }
    }
}
