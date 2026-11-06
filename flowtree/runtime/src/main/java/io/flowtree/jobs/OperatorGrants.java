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

import io.flowtree.JsonFieldExtractor;
import org.almostrealism.io.ConsoleFeatures;

import java.util.Collections;
import java.util.List;
import java.util.TreeSet;
import java.util.function.BiConsumer;

/**
 * The capabilities an operator granted one {@link CodingAgentJob} beyond what
 * its guardrails allow, each signed by the controller so that an agent can
 * never grant one to itself.
 *
 * <p>Two grants exist:</p>
 * <ul>
 *   <li><b>Sensitive-file bypass</b> — the job's sensitive-file protections
 *       are off, and its commit carries a {@code Sensitive-File-Bypass}
 *       trailer; see {@link SensitiveFileBypassTrailer}.</li>
 *   <li><b>Accepted merge resolutions</b> — the owner has looked at how an
 *       agent resolved a merge of the base branch and agrees with it for the
 *       named paths. {@link GitCommitHandler} then commits the agent's
 *       resolution of those paths even when a staging guardrail rejected it,
 *       instead of failing the merge, and the commit records the grant in a
 *       {@code Merge-Resolution-Accepted} trailer.</li>
 * </ul>
 *
 * <h2>Why an agent cannot set either</h2>
 * <p>Both are signed at submission time with {@code AR_AGENT_BYPASS_SECRET}
 * (see {@link #applySubmission}), a secret held by the controller and CI and
 * never present in an agent's environment. The signature is the only source of
 * either grant: a job carrying paths but no signature accepts nothing, and the
 * controller never takes a signature from the request. The submission tool
 * additionally refuses both options from a caller whose token is bound to a
 * running agent's workstream. Every trailer this class appends is first
 * stripped from the agent's own message, so an agent cannot copy one from an
 * earlier commit.</p>
 *
 * <p>Neither grant lifts a lock the pipeline enforces itself. CI still
 * applies its CI-file lock and test-integrity checks to an accepted
 * resolution; a CI file additionally needs the sensitive-file bypass.</p>
 *
 * <p>{@link CodingAgentJob} and {@link CodingAgentJobFactory} each delegate to
 * an instance of this class; see {@link WireConfig} for how the two serialize
 * it.</p>
 *
 * @author Michael Murray
 * @see SensitiveFileBypassTrailer
 */
public final class OperatorGrants extends WireConfig implements ConsoleFeatures {

    /** Wire and submission key of the sensitive-file protection flag. */
    static final String PROTECTION_KEY = "sensitiveFileProtectionEnabled";

    /** Wire key of the sensitive-file bypass signature. */
    static final String BYPASS_SIGNATURE_KEY = "sensitiveFileBypassSignature";

    /** Wire and submission key of the accepted merge resolutions. */
    static final String ACCEPTED_RESOLUTIONS_KEY = "acceptedMergeResolutions";

    /** Wire key of the signature over the accepted merge resolutions. */
    static final String RESOLUTION_SIGNATURE_KEY = "mergeResolutionSignature";

    /** Trailer key recording an owner-accepted merge resolution in a commit message. */
    static final String RESOLUTION_TRAILER_KEY = "Merge-Resolution-Accepted";

    /** The trailer recording an owner-accepted merge resolution in a commit message. */
    private static final CommitTrailer RESOLUTION_TRAILER = new CommitTrailer(RESOLUTION_TRAILER_KEY);

    /** Whether the sensitive-file protections are active; {@code true} unless an operator turned them off. */
    private boolean sensitiveFileProtectionEnabled = true;

    /** Controller-signed sensitive-file bypass signature, or {@code null}. */
    private String sensitiveFileBypassSignature;

    /** Paths whose merge resolution the owner accepted, in submission order. */
    private List<String> acceptedMergeResolutions = Collections.emptyList();

    /** Controller signature over the job id and {@link #acceptedMergeResolutions}, or {@code null}. */
    private String mergeResolutionSignature;

    /** Constructs grants whose state is not published as properties. */
    OperatorGrants() {
        this(null);
    }

    /**
     * Constructs grants that publish their wire properties as they change.
     *
     * @param propertySink receives {@code (key, value)} for each wire property
     *                     a mutation changed, or {@code null} for none
     */
    OperatorGrants(BiConsumer<String, String> propertySink) {
        super(propertySink);
    }

    /**
     * Returns whether the per-job sensitive-file protections are active.
     *
     * @return {@code true} unless an operator turned them off
     */
    public boolean isSensitiveFileProtectionEnabled() { return sensitiveFileProtectionEnabled; }

    /**
     * Sets whether the per-job sensitive-file protections are active.
     *
     * @param enabled {@code false} to authorize the job to modify protected files
     */
    public void setSensitiveFileProtectionEnabled(boolean enabled) {
        this.sensitiveFileProtectionEnabled = enabled;
        publish(PROTECTION_KEY, String.valueOf(enabled));
    }

    /**
     * Returns the controller-signed sensitive-file bypass signature.
     *
     * @return the signature, or {@code null} if none was issued
     */
    public String getSensitiveFileBypassSignature() { return sensitiveFileBypassSignature; }

    /**
     * Sets the controller-signed sensitive-file bypass signature.
     *
     * @param signature the signature, or {@code null} to clear it
     */
    public void setSensitiveFileBypassSignature(String signature) {
        this.sensitiveFileBypassSignature = signature;
        publish(BYPASS_SIGNATURE_KEY, encoded(signature));
    }

    /**
     * Returns the paths whose merge resolution the owner accepted. Empty
     * unless the controller signed the grant.
     *
     * @return the accepted paths, never {@code null}
     */
    public List<String> getAcceptedMergeResolutions() { return acceptedMergeResolutions; }

    /**
     * Returns the controller's signature over the accepted merge resolutions.
     *
     * @return the signature, or {@code null} if none was issued
     */
    public String getMergeResolutionSignature() { return mergeResolutionSignature; }

    /**
     * Sets the accepted merge resolutions together with the signature that
     * authorizes them. The two travel together because neither means anything
     * alone.
     *
     * @param paths     the accepted repository-relative paths; {@code null} for none
     * @param signature the controller's signature over them, or {@code null}
     */
    void setAcceptedMergeResolutions(List<String> paths, String signature) {
        this.acceptedMergeResolutions = paths == null || paths.isEmpty()
                ? Collections.emptyList() : List.copyOf(paths);
        this.mergeResolutionSignature = signature;
        publish(ACCEPTED_RESOLUTIONS_KEY, encodedPaths());
        publish(RESOLUTION_SIGNATURE_KEY, encoded(signature));
    }

    /**
     * Returns whether the owner accepted the agent's resolution of
     * {@code path}: the path is named, and the controller signed the grant.
     *
     * @param path a repository-relative path
     * @return {@code true} when the agent's resolution of the path may be committed
     */
    boolean acceptsMergeResolution(String path) {
        return mergeResolutionSignature != null && !mergeResolutionSignature.isEmpty()
                && acceptedMergeResolutions.contains(path);
    }

    /**
     * Returns {@code message} stamped with the trailers these grants call
     * for, after removing every agent-supplied copy of them.
     *
     * <p>The {@code Sensitive-File-Bypass} trailer is appended when the
     * protections are off and the controller signed the bypass (see
     * {@link SensitiveFileBypassTrailer}); without a signature it is only
     * stripped and CI blocks the commit. The {@code Merge-Resolution-Accepted}
     * trailer is appended when {@code accepted} is non-empty, naming the job
     * and the paths whose resolution the commit accepted.</p>
     *
     * @param message  the commit message
     * @param jobId    the job the grants were signed for
     * @param accepted the paths this commit accepted under the merge-resolution grant
     * @return the message with the trailers applied
     */
    String applyToMessage(String message, String jobId, List<String> accepted) {
        String bypassSignature = null;
        if (!sensitiveFileProtectionEnabled) {
            bypassSignature = sensitiveFileBypassSignature;
            if (bypassSignature != null && !bypassSignature.isEmpty()) {
                log("Appending controller-signed Sensitive-File-Bypass trailer (jobId=" + jobId + ")");
            } else {
                log("Sensitive-file protection disabled but no bypass signature"
                        + " present on the job; commit will be blocked by CI");
            }
        }
        String signed = SensitiveFileBypassTrailer.applyToMessage(message, jobId, bypassSignature);
        return RESOLUTION_TRAILER.apply(signed, accepted.isEmpty() ? null
                : jobId + "=" + mergeResolutionSignature + "; paths=" + String.join(",", accepted));
    }

    /**
     * Removes every operator-grant trailer an agent could have written into
     * {@code message}: both {@code Sensitive-File-Bypass} and
     * {@code Merge-Resolution-Accepted}. The harness appends its own copy of a
     * trailer only after an authorized grant, so a forged line in the agent's
     * {@code commit.txt} must never survive into a message the harness composes
     * from it.
     *
     * @param message the agent's commit message; may be {@code null}
     * @return the message with both grant trailers stripped, never {@code null}
     */
    static String stripGrantTrailers(String message) {
        return RESOLUTION_TRAILER.strip(SensitiveFileBypassTrailer.stripTrailer(message));
    }

    /**
     * Applies the operator grants a submission requests, signing each with
     * {@code secret}. Called by the controller only: the request supplies
     * what the operator asked for, and never a signature.
     *
     * <p>A grant the controller cannot sign (no secret, or no job id) is not
     * forwarded at all. For the sensitive-file bypass that leaves the
     * protections off and CI blocking the commit, as before; for accepted
     * merge resolutions it drops the paths, so a job never carries an
     * acceptance that nothing authorized. The paths are read strictly: a
     * value that is not an array of strings grants nothing.</p>
     *
     * <p>No signature is ever taken from the request. An earlier controller
     * accepted an explicit {@code sensitiveFileBypassSignature} from the body
     * as an override, which let any caller with API access inject a value the
     * controller had not computed; that path no longer exists.</p>
     *
     * <p>Reached through {@link CodingAgentJobFactory#applyOperatorGrants},
     * which supplies its own job id. Each grant that could not be signed is
     * reported as a warning.</p>
     *
     * @param body   the submission's JSON body
     * @param secret the controller's signing secret, or {@code null}
     * @param jobId  the job being submitted
     */
    void applySubmission(String body, String secret, String jobId) {
        if (JsonFieldExtractor.hasField(body, PROTECTION_KEY)) {
            setSensitiveFileProtectionEnabled(JsonFieldExtractor.extractBoolean(body, PROTECTION_KEY));
        }
        List<String> paths;
        try {
            paths = JsonFieldExtractor.hasField(body, ACCEPTED_RESOLUTIONS_KEY)
                    ? JsonFieldExtractor.extractStrictStringArray(body, ACCEPTED_RESOLUTIONS_KEY) : null;
        } catch (IllegalArgumentException e) {
            warn("acceptedMergeResolutions ignored, so no merge resolution will be accepted: "
                    + e.getMessage());
            paths = null;
        }
        boolean canSign = secret != null && !secret.isEmpty() && jobId != null && !jobId.isEmpty();
        if (!sensitiveFileProtectionEnabled) {
            if (canSign) {
                setSensitiveFileBypassSignature(SensitiveFileBypassTrailer.sign(secret, jobId));
            } else {
                warn("sensitiveFileProtectionEnabled=false but AR_AGENT_BYPASS_SECRET"
                        + " is unset or jobId is empty; commit will be blocked by CI");
            }
        }
        if (paths != null && !paths.isEmpty()) {
            if (canSign) {
                setAcceptedMergeResolutions(paths, signMergeResolutions(secret, jobId, paths));
            } else {
                warn("acceptedMergeResolutions requested but AR_AGENT_BYPASS_SECRET"
                        + " is unset or jobId is empty; no merge resolution will be accepted");
            }
        }
    }

    /**
     * Computes the controller signature over a merge-resolution grant: the
     * job id and the accepted paths, in sorted order so the signature does
     * not depend on how they were listed. Uses the same HMAC as the
     * sensitive-file bypass, over a payload prefixed so that neither
     * signature can stand in for the other.
     *
     * @param secret the signing secret
     * @param jobId  the job the grant is for
     * @param paths  the accepted paths
     * @return the signature, or {@code null} if it cannot be computed
     */
    static String signMergeResolutions(String secret, String jobId, List<String> paths) {
        return SensitiveFileBypassTrailer.sign(secret,
                "merge-resolution:" + jobId + "\n" + String.join("\n", new TreeSet<>(paths)));
    }

    /**
     * Copies these grants onto {@code target}.
     *
     * @param target the grants to populate, typically a new job's
     */
    void applyTo(OperatorGrants target) {
        target.setSensitiveFileProtectionEnabled(sensitiveFileProtectionEnabled);
        target.setSensitiveFileBypassSignature(sensitiveFileBypassSignature);
        target.setAcceptedMergeResolutions(acceptedMergeResolutions, mergeResolutionSignature);
    }

    /**
     * Hands these grants' wire properties to {@code sink}. The protection
     * flag is emitted only when off, and each signature or path list only
     * when present.
     *
     * @param sink receives {@code (key, value)} for each wire property
     */
    @Override
    protected void writeWireProperties(BiConsumer<String, String> sink) {
        sink.accept(PROTECTION_KEY, sensitiveFileProtectionEnabled ? null : "false");
        sink.accept(BYPASS_SIGNATURE_KEY, encoded(sensitiveFileBypassSignature));
        sink.accept(ACCEPTED_RESOLUTIONS_KEY, encodedPaths());
        sink.accept(RESOLUTION_SIGNATURE_KEY, encoded(mergeResolutionSignature));
    }

    /**
     * Applies one wire-format key/value pair, assigning without publishing.
     *
     * @param key   the wire-format key (without the leading {@code ::})
     * @param value the raw wire value
     * @return {@code true} if {@code key} belongs to these grants and was consumed
     */
    boolean applySetting(String key, String value) {
        switch (key) {
            case PROTECTION_KEY:
                this.sensitiveFileProtectionEnabled = Boolean.parseBoolean(value);
                return true;
            case BYPASS_SIGNATURE_KEY:
                this.sensitiveFileBypassSignature = GitManagedJob.base64Decode(value);
                return true;
            case ACCEPTED_RESOLUTIONS_KEY:
                String joined = GitManagedJob.base64Decode(value);
                this.acceptedMergeResolutions = joined == null ? Collections.emptyList()
                        : List.of(joined.split("\n"));
                return true;
            case RESOLUTION_SIGNATURE_KEY:
                this.mergeResolutionSignature = GitManagedJob.base64Decode(value);
                return true;
            default:
                return false;
        }
    }

    /**
     * Returns the accepted paths in their wire form, or {@code null} when none.
     *
     * @return the Base64 of the newline-joined paths, or {@code null}
     */
    private String encodedPaths() {
        return acceptedMergeResolutions.isEmpty() ? null
                : GitManagedJob.base64Encode(String.join("\n", acceptedMergeResolutions));
    }

    /**
     * Returns {@code value} in its wire form, or {@code null} when absent.
     *
     * @param value the string to encode
     * @return its Base64 form, or {@code null} when {@code value} is null or empty
     */
    private static String encoded(String value) {
        return value == null || value.isEmpty() ? null : GitManagedJob.base64Encode(value);
    }
}
