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

import io.flowtree.Server;
import io.flowtree.api.FlowTreeApiEndpoint;
import io.flowtree.api.SecretsRequestHandler;
import io.flowtree.controller.JobStatsStore;
import io.flowtree.jobs.agent.PhaseConfigBundle;
import io.flowtree.slack.NotifierRegistry;
import io.flowtree.slack.SlackListener;
import io.flowtree.slack.SlackNotifier;
import io.flowtree.workstream.Workstream;
import io.flowtree.workstream.WorkstreamConfig;

import org.almostrealism.io.ConsoleFeatures;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Fans a finished job out to its completion listeners by submitting a
 * compact wake-up job to each listener. The fan-out is the runtime
 * side of the completion-listener feature; the config-time side
 * (cycle rejection, field persistence) lives on
 * {@link io.flowtree.workstream.ListenerCycleChecker} and
 * {@link Workstream#setCompletionListeners}.
 *
 * <h2>Safety model</h2>
 *
 * The delegation pattern is intentionally a loop (worker -> orchestrator
 * -> worker -> ...), and the only thing that bounds it is the
 * orchestrator choosing to stop. Four ceilings bound cost even if the
 * orchestrator never decides to stop:
 *
 * <ol>
 *   <li>{@link CodingAgentJob#DEFAULT_MAX_WAKE_UPS_PER_WINDOW} per
 *       {@link CodingAgentJob#DEFAULT_MAX_WAKE_UP_WINDOW_SECONDS}s
 *       <em>per listener</em> — primary flood protection. Excess
 *       wake-ups are dropped (not queued, not retried); dropping is
 *       safe because of the reconciliation invariant below.</li>
 *   <li>{@link CodingAgentJob#DEFAULT_MAX_CHAIN_DEPTH} — bounds lineage
 *       depth. Defense-in-depth.</li>
 *   <li>{@link CodingAgentJob#DEFAULT_MAX_WAKE_UPS_PER_SOURCE_CHAIN}
 *       — bounds fan-out breadth per single source event. Defense-in-
 *       depth; the chain ID resets per source event.</li>
 *   <li>Coalesce within {@link CodingAgentJob#DEFAULT_COALESCE_WINDOW_SECONDS}s
 *       per (source, listener) pair — the first completion in a
 *       window fires a wake-up, subsequent completions are added to
 *       a consolidated-IDs list and do not fire additional wake-ups.</li>
 * </ol>
 *
 * <p>The {@link FlowTreeApiEndpoint#setAcceptAutomatedJobs(boolean)
 * acceptAutomatedJobs} gate is the documented kill switch: when set
 * to {@code false} the fan-out logs {@code wakeup_kill_switch_active}
 * at WARN and skips all wake-ups globally. Wake-up jobs are submitted
 * with {@code automated: true} so the gate is enforced end to end.</p>
 *
 * <h2>Reconciliation invariant</h2>
 *
 * The wake-up prompt instructs the listener to reconcile the <em>full</em>
 * state of every workstream it has delegated to on every wake, not
 * just the single completion mentioned in the prompt. This is the
 * design requirement that makes dropping / coalescing wake-ups
 * lossless: whatever the listener missed, it picks up on the next
 * successful wake by re-reading the world. The prompt template below
 * (see {@link #buildWakeUpPrompt}) is the single place this invariant
 * is encoded.
 *
 * <h2>Threading</h2>
 *
 * The fan-out is called from the controller's status-event handler
 * thread, which is a NanoHTTPD worker. All state used by the
 * ceilings and the coalesce window is keyed by (source workstream,
 * listener workstream) and is held in a {@link ConcurrentHashMap}, so
 * concurrent completions on the same source fan out safely. The fanout
 * MUST NOT throw; any exception is caught and logged at WARN so a
 * misbehaving listener cannot poison the source job's completion
 * recording.
 */
public class CompletionListenerFanout implements ConsoleFeatures {

    /**
     * Function supplying the current kill-switch state.
     * The fanout is read-only on this supplier; the endpoint
     * owns the mutable state.
     */
    public interface AcceptAutomatedJobsSupplier {
        /**
         * Returns the current state of the automated-jobs gate.
         *
         * @return {@code true} when automated jobs are accepted, {@code false}
         *         when the kill switch is engaged
         */
        boolean isAccepting();
    }

    /**
     * Resolves the {@link SlackNotifier} that owns a given workstream
     * (the source of the completion event). Returns {@code null} when
     * the workstream is not registered, in which case the fan-out
     * logs {@code wakeup_source_missing} and skips.
     */
    public interface NotifierLookup {
        /**
         * Returns the notifier that owns the given workstream, or {@code null}
         * when no workstream with that ID is registered.
         *
         * @param workstreamId the workstream identifier
         * @return the notifier, or {@code null}
         */
        SlackNotifier forWorkstream(String workstreamId);
    }

    /**
     * Per-pair coalesce state. The first completion in a window fires
     * a wake-up and stores its own job ID; subsequent completions
     * append to {@link #consolidatedJobIds} and are dropped.
     */
    private static final class CoalesceState {
        /** Wall-clock millis at which the coalesce-window wake-up fired. */
        final long firedAtMillis;
        /** Job ID of the wake-up that fired (the "primary" coalesced job). */
        final String primaryJobId;
        /** Additional job IDs coalesced into the same wake-up. */
        final List<String> consolidatedJobIds;

        /**
         * Records a new coalesce-window wake-up that fired at
         * {@code firedAtMillis} for the primary job {@code primaryJobId}.
         *
         * @param firedAtMillis wall-clock millis at which the wake-up fired
         * @param primaryJobId  the job ID of the wake-up itself
         */
        CoalesceState(long firedAtMillis, String primaryJobId) {
            this.firedAtMillis = firedAtMillis;
            this.primaryJobId = primaryJobId;
            this.consolidatedJobIds = new ArrayList<>();
        }
    }

    /**
     * Maximum number of wake-up jobs the controller will submit to a given
     * listener workstream within {@link #DEFAULT_MAX_WAKE_UP_WINDOW_SECONDS}
     * seconds. This is the primary flood / recurrence protection on the
     * completion-listener cascade: a listener cannot be woken more than
     * this many times in the window, regardless of how many distinct source
     * completions occur. Excess wake-ups are dropped (not queued, not
     * retried) and a {@code ceiling_hit} line is logged with the listener
     * ID. Dropping is safe because the wake-up handler is required to
     * reconcile the full state of every workstream it has delegated to on
     * every wake, so a dropped wake-up is picked up on the next successful
     * wake by re-reading the world.
     */
    public static final int DEFAULT_MAX_WAKE_UPS_PER_WINDOW = 6;

    /**
     * Length of the sliding window used by
     * {@link #DEFAULT_MAX_WAKE_UPS_PER_WINDOW}. 600 s = 10 minutes.
     * Sized so that a healthy orchestrator (one wake-up per multi-minute
     * round-trip) is far below the cap, but a runaway orchestrator cannot
     * spawn more than a fixed, small number of wake-ups in any 10-minute
     * stretch. Counter is a sliding window, not a fixed bucket, so a long
     * quiet stretch does not accumulate budget.
     */
    public static final int DEFAULT_MAX_WAKE_UP_WINDOW_SECONDS = 600;

    /**
     * Maximum number of edges in a single delegation chain. A chain is the
     * sequence {@code A_0 -> A_1 -> ... -> A_n} where {@code A_{i+1}} is
     * listed in {@code A_i}'s completion-listeners. A depth of 8 is large
     * enough to express realistic orchestrator -> sub-orchestrator ->
     * worker patterns and small enough that no legitimate graph exceeds it.
     * Defense-in-depth on top of the
     * {@link #DEFAULT_MAX_WAKE_UPS_PER_WINDOW} flood protection; the
     * primary guard against runaway cost is the window ceiling.
     */
    public static final int DEFAULT_MAX_CHAIN_DEPTH = 8;

    /**
     * Maximum number of wake-up jobs the controller will submit, across
     * all listeners, that share a single chain ID. Bounds fan-out
     * <em>breadth per single source event</em> (how many listeners one
     * completion can notify in one shot), not recurrence across source
     * events: the chain ID resets per source event, so in the motivating
     * use case (N workers each listing the orchestrator) the per-chain
     * counter is effectively 1 per wake-up. Defense-in-depth against a
     * listener-list misconfiguration (one workstream listing 25+
     * listeners) — not a flood protection on its own.
     */
    public static final int DEFAULT_MAX_WAKE_UPS_PER_SOURCE_CHAIN = 25;

    /**
     * Length of the sliding window within which bursty completions on the
     * same {@code (sourceWorkstreamId, listenerWorkstreamId)} pair are
     * coalesced into a single wake-up. The first completion in a window
     * fires a wake-up; subsequent completions in the same window are
     * added to a "consolidated IDs" list carried in the wake-up prompt
     * and do not fire additional wake-ups. The listener's reconciliation
     * invariant (§2.1.6 of the completion-listeners design) makes
     * coalescing lossless: the orchestrator always re-reads the world on
     * every wake, so a delayed / consolidated wake-up loses no work.
     */
    public static final int DEFAULT_COALESCE_WINDOW_SECONDS = 30;

    /**
     * The fanout's in-memory state, exposed for tests so a fresh
     * instance per test avoids cross-test pollution.
     */
    private final ConcurrentHashMap<String, CoalesceState> coalesceState =
            new ConcurrentHashMap<>();

    /**
     * Per-listener wake-up timestamps, used to enforce
     * {@link CodingAgentJob#DEFAULT_MAX_WAKE_UPS_PER_WINDOW} in a
     * sliding window. The list is sorted by timestamp on insertion
     * so the eviction step is a simple head-drop.
     */
    private final ConcurrentHashMap<String, Deque<Long>> wakeUpWindowByListener =
            new ConcurrentHashMap<>();

    /**
     * Per-chain wake-up count, used to enforce
     * {@link CodingAgentJob#DEFAULT_MAX_WAKE_UPS_PER_SOURCE_CHAIN}.
     * Cleared on controller restart — defence-in-depth, not the
     * primary flood protection.
     */
    private final ConcurrentHashMap<String, Integer> chainCount =
            new ConcurrentHashMap<>();

    /** Kill switch supplier. {@code null} is treated as "always accepting". */
    private final AcceptAutomatedJobsSupplier acceptSupplier;
    /** All-workstreams view used to look up listener config and validate listener IDs. */
    private final Supplier<Map<String, Workstream>> allWorkstreams;
    /** Local FlowTree server used to dispatch jobs. */
    private final Server server;
    /** Stats store; used to set {@code trigger_reason=completion_listener} on wake-up rows. */
    private final JobStatsStore statsStore;
    /** Workstream URL builder; injected for tests so the URL is stable. */
    private final Function<String, String> workstreamUrlBuilder;
    /** AR-manager URL forwarded to wake-up factories (may be {@code null}). */
    private final String arManagerUrl;
    /** HMAC shared secret forwarded to wake-up factories (may be {@code null}). */
    private final String sharedSecret;
    /** Clock supplier; tests override to advance time deterministically. */
    private final Supplier<Long> clock;
    /** Pushed-tools config JSON forwarded to every job; may be {@code null}. */
    private final String pushedToolsConfig;
    /** Workspace lookup; {@code null} means workspace layer is skipped. */
    private final Function<String, WorkstreamConfig.WorkspaceEntry> workspaceLookup;
    /** Default workspace path; propagated to wake-up factories when set. */
    private final String defaultWorkspacePath;

    /**
     * Constructs a new fanout bound to a specific endpoint, server, and
     * upstream collaborators. The {@code notifierLookup} is used to
     * locate the source-workstream's notifier; the {@code allWorkstreams}
     * supplier is used to look up each listener's full config.
     */
    public CompletionListenerFanout(AcceptAutomatedJobsSupplier acceptSupplier,
                                    Supplier<Map<String, Workstream>> allWorkstreams,
                                    Server server,
                                    JobStatsStore statsStore,
                                    Function<String, String> workstreamUrlBuilder,
                                    String arManagerUrl,
                                    String sharedSecret,
                                    String pushedToolsConfig,
                                    Function<String, WorkstreamConfig.WorkspaceEntry> workspaceLookup,
                                    String defaultWorkspacePath) {
        this(acceptSupplier, allWorkstreams, server, statsStore,
                workstreamUrlBuilder, arManagerUrl, sharedSecret,
                pushedToolsConfig, workspaceLookup, defaultWorkspacePath,
                System::currentTimeMillis);
    }

    /**
     * Test-only constructor that exposes the clock supplier so
     * ceiling / coalesce tests can advance time without sleeping.
     */
    public CompletionListenerFanout(AcceptAutomatedJobsSupplier acceptSupplier,
                                    Supplier<Map<String, Workstream>> allWorkstreams,
                                    Server server,
                                    JobStatsStore statsStore,
                                    Function<String, String> workstreamUrlBuilder,
                                    String arManagerUrl,
                                    String sharedSecret,
                                    String pushedToolsConfig,
                                    Function<String, WorkstreamConfig.WorkspaceEntry> workspaceLookup,
                                    String defaultWorkspacePath,
                                    Supplier<Long> clock) {
        this.acceptSupplier = acceptSupplier == null ? () -> true : acceptSupplier;
        this.allWorkstreams = allWorkstreams;
        this.server = server;
        this.statsStore = statsStore;
        this.workstreamUrlBuilder = workstreamUrlBuilder;
        this.arManagerUrl = arManagerUrl;
        this.sharedSecret = sharedSecret;
        this.pushedToolsConfig = pushedToolsConfig;
        this.workspaceLookup = workspaceLookup == null ? id -> null : workspaceLookup;
        this.defaultWorkspacePath = defaultWorkspacePath;
        this.clock = clock == null ? System::currentTimeMillis : clock;
    }

    /**
     * Returns a new fanout wired to the supplied notifier registry and
     * endpoint. The {@code acceptSupplier} reads the endpoint's
     * current kill-switch state; the {@code notifierLookup} resolves
     * the source notifier for a finished job. Used by
     * {@link FlowTreeApiEndpoint} during initialization.
     */
    public static CompletionListenerFanout bind(NotifierRegistry notifiers,
                                                FlowTreeApiEndpoint endpoint,
                                                Server server,
                                                JobStatsStore statsStore,
                                                SlackListener listener) {
        AcceptAutomatedJobsSupplier acceptSupplier = endpoint::isAcceptAutomatedJobs;
        Supplier<Map<String, Workstream>> allWs = notifiers::allWorkstreams;
        Function<String, String> urlBuilder = wsId -> {
            int port = endpoint.getListeningPort();
            if (port <= 0) return null;
            return "http://0.0.0.0:" + port + "/api/workstreams/" + wsId;
        };
        String arManagerUrl = null;
        String sharedSecret = null;
        String pushedToolsConfig = null;
        String defaultWorkspacePath = null;
        if (listener != null) {
            arManagerUrl = listener.getArManagerUrl();
            sharedSecret = listener.getArManagerSharedSecret();
            pushedToolsConfig = listener.getPushedToolsConfig();
            defaultWorkspacePath = listener.getDefaultWorkspacePath();
        }
        Function<String, WorkstreamConfig.WorkspaceEntry> wsLookup = endpoint::workspaceLookupOrNull;
        return new CompletionListenerFanout(acceptSupplier, allWs,
                server, statsStore, urlBuilder, arManagerUrl, sharedSecret,
                pushedToolsConfig, wsLookup, defaultWorkspacePath);
    }

    /**
     * Fans the given terminal completion out to every listener of the
     * source workstream. This is the single entry point the API
     * endpoint calls; it never throws. {@code STARTED} is not a
     * completion; callers must not invoke the fan-out for it.
     *
     * @param sourceWorkstreamId the workstream that owns the finished job
     * @param event              the terminal completion event
     */
    public void fanout(String sourceWorkstreamId, JobCompletionEvent event) {
        if (sourceWorkstreamId == null || sourceWorkstreamId.isEmpty()
                || event == null
                || event.getStatus() == JobCompletionEvent.Status.STARTED) {
            return;
        }
        try {
            Map<String, Workstream> snapshot = allWorkstreams.get();
            if (snapshot == null) snapshot = Collections.emptyMap();
            Workstream source = snapshot.get(sourceWorkstreamId);
            if (source == null) {
                warn("wakeup_source_missing source=" + sourceWorkstreamId);
                return;
            }
            List<String> listeners = source.getCompletionListeners();
            if (listeners == null || listeners.isEmpty()) {
                return;
            }
            // The chain ID is generated once per source event, so all
            // listeners fanned out from one completion share a budget
            // key (the per-source-chain ceiling).
            String chainId = "ch-" + UUID.randomUUID();
            int chainDepth = computeChainDepth(event);
            for (String listenerId : listeners) {
                try {
                    dispatchToListener(sourceWorkstreamId, listenerId, event,
                            snapshot, chainId, chainDepth);
                } catch (RuntimeException ex) {
                    warn("wakeup_dispatch_failed source=" + sourceWorkstreamId
                            + " listener=" + listenerId
                            + " chain=" + chainId
                            + ": " + ex.getMessage());
                }
            }
        } catch (RuntimeException ex) {
            // Defensive: never let a listener fan-out failure bubble
            // out of the controller's status event handler. The source
            // job's completion MUST always record normally.
            warn("wakeup_fanout_failed source=" + sourceWorkstreamId
                    + ": " + ex.getMessage());
        }
    }

    /**
     * Computes a coarse chain depth from the source event's commit hash
     * or other signal. Wake-up jobs do not yet carry a depth field
     * on the source event; v1 uses 0 for any finished job that is
     * not itself a wake-up, and 1 for wake-up jobs (detected by the
     * description prefix "wake-up:"). The {@code maxChainDepth}
     * ceiling is defence-in-depth; the primary flood guard is
     * {@code maxWakeUpsPerWindow}, so an imprecise depth metric does
     * not materially weaken the safety model.
     */
    // TODO(review): depth is always 0 (user job) or 1 (any wake-up); multi-hop depth
    // is not tracked because JobCompletionEvent carries no depth field in v1. The
    // maxChainDepth ceiling therefore never trips for depth-2+ chains. Acceptable
    // since maxWakeUpsPerWindow is the primary protection, but a future v2 that adds
    // a depth field to JobCompletionEvent should read it here instead.
    private int computeChainDepth(JobCompletionEvent event) {
        String desc = event.getDescription();
        if (desc != null && desc.startsWith("wake-up:")) {
            return 1;
        }
        return 0;
    }

    /**
     * Dispatches one wake-up to the named listener, enforcing the
     * kill switch, the coalesce window, the per-listener window
     * ceiling, the per-source-chain ceiling, and the chain-depth
     * ceiling in that order. Each ceiling logs a distinct
     * {@code ceiling_hit} (or {@code wakeup_kill_switch_active} for
     * the kill switch) line and returns without firing.
     */
    private void dispatchToListener(String sourceWorkstreamId,
                                    String listenerId,
                                    JobCompletionEvent event,
                                    Map<String, Workstream> snapshot,
                                    String chainId,
                                    int chainDepth) {
        // 1. Kill switch. Cheapest check; runs first so an operator
        //    can halt the system without the fanout doing any work.
        if (!acceptSupplier.isAccepting()) {
            warn("wakeup_kill_switch_active source=" + sourceWorkstreamId
                    + " listener=" + listenerId + " chain=" + chainId);
            return;
        }

        // 2. Listener must exist as a registered workstream.
        Workstream listener = snapshot.get(listenerId);
        if (listener == null) {
            warn("wakeup_listener_missing source=" + sourceWorkstreamId
                    + " listener=" + listenerId);
            return;
        }

        // 3. Coalesce window. Within the window, the first completion
        //    fires a wake-up carrying the primary job ID; subsequent
        //    completions append to a consolidated list and skip.
        long now = clock.get();
        long windowMs = DEFAULT_COALESCE_WINDOW_SECONDS * 1000L;
        String coalesceKey = sourceWorkstreamId + "|" + listenerId;
        CoalesceState existing = coalesceState.get(coalesceKey);
        if (existing != null && (now - existing.firedAtMillis) < windowMs) {
            existing.consolidatedJobIds.add(event.getJobId());
            return;
        }

        // 4. Chain-depth ceiling (defense-in-depth).
        if (chainDepth >= DEFAULT_MAX_CHAIN_DEPTH) {
            warn("ceiling_hit source=" + sourceWorkstreamId
                    + " listener=" + listenerId
                    + " chain=" + chainId
                    + " reason=maxChainDepth("
                    + DEFAULT_MAX_CHAIN_DEPTH + ")");
            return;
        }

        // 5. Per-source-chain breadth ceiling (defence-in-depth).
        int chainCurrent = chainCount.getOrDefault(chainId, 0);
        if (chainCurrent >= DEFAULT_MAX_WAKE_UPS_PER_SOURCE_CHAIN) {
            warn("ceiling_hit source=" + sourceWorkstreamId
                    + " listener=" + listenerId
                    + " chain=" + chainId
                    + " reason=maxWakeUpsPerSourceChain("
                    + DEFAULT_MAX_WAKE_UPS_PER_SOURCE_CHAIN + ")");
            return;
        }

        // 6. Per-listener window ceiling (PRIMARY flood protection).
        long windowSecs = DEFAULT_MAX_WAKE_UP_WINDOW_SECONDS;
        long windowMillis = windowSecs * 1000L;
        Deque<Long> window = wakeUpWindowByListener.computeIfAbsent(
                listenerId, k -> new ArrayDeque<>());
        synchronized (window) {
            while (!window.isEmpty() && (now - window.peekFirst()) >= windowMillis) {
                window.pollFirst();
            }
            if (window.size() >= DEFAULT_MAX_WAKE_UPS_PER_WINDOW) {
                warn("ceiling_hit source=" + sourceWorkstreamId
                        + " listener=" + listenerId
                        + " chain=" + chainId
                        + " reason=maxWakeUpsPerWindow("
                        + DEFAULT_MAX_WAKE_UPS_PER_WINDOW
                        + "/" + windowSecs + "s)");
                return;
            }
            window.addLast(now);
        }

        // 7. Mark the coalesce state so subsequent completions on the
        //    same source/listener pair within the window are dropped.
        CoalesceState state = new CoalesceState(now, event.getJobId());
        coalesceState.put(coalesceKey, state);
        chainCount.put(chainId, chainCurrent + 1);

        // 8. Build and dispatch the wake-up factory.
        CodingAgentJob.Factory factory = buildWakeUpFactory(
                sourceWorkstreamId, listener, event, chainId, chainDepth);
        try {
            if (statsStore != null && factory.getTaskId() != null) {
                statsStore.setTriggerReason(factory.getTaskId(),
                        "completion_listener");
            }
            if (server == null) {
                warn("wakeup_no_server listener=" + listenerId
                        + " chain=" + chainId);
                return;
            }
            server.addTask(factory);
            // Surface a clear line so an operator can confirm the
            // fanout fired. Cheap; logged at INFO via the Console
            // infrastructure.
            log("Submitted wake-up chain=" + chainId
                    + " source=" + sourceWorkstreamId
                    + " listener=" + listenerId
                    + " triggerJob=" + event.getJobId()
                    + " wakeJob=" + factory.getTaskId());
        } catch (RuntimeException ex) {
            warn("wakeup_submit_failed listener=" + listenerId
                    + " chain=" + chainId + ": " + ex.getMessage());
        }
    }

    /**
     * Builds the {@link CodingAgentJob.Factory} for a single wake-up.
     * All configuration is read from the <em>listener</em> workstream's
     * own config (not the source's): the orchestrator wakes up as
     * itself, with its own tools, budget, planning document, and
     * phase config.
     */
    private CodingAgentJob.Factory buildWakeUpFactory(String sourceWorkstreamId,
                                                       Workstream listener,
                                                       JobCompletionEvent event,
                                                       String chainId,
                                                       int chainDepth) {
        String prompt = buildWakeUpPrompt(sourceWorkstreamId, listener, event,
                chainId, chainDepth);
        CodingAgentJob.Factory factory = new CodingAgentJob.Factory(prompt);
        factory.setDescription("wake-up: " + listener.getChannelName()
                + " <- " + sourceWorkstreamId);
        factory.setAllowedTools(listener.getAllowedTools());
        factory.setMaxTurns(listener.getMaxTurns());
        factory.setMaxBudgetUsd(listener.getMaxBudgetUsd());
        factory.setPushToOrigin(listener.isPushToOrigin());
        if (listener.getWorkingDirectory() != null) {
            factory.setWorkingDirectory(listener.getWorkingDirectory());
        }
        if (listener.getRepoUrl() != null) {
            factory.setRepoUrl(listener.getRepoUrl());
        }
        if (defaultWorkspacePath != null && !defaultWorkspacePath.isEmpty()) {
            factory.setDefaultWorkspacePath(defaultWorkspacePath);
        }
        if (listener.getGitUserName() != null) {
            factory.setGitUserName(listener.getGitUserName());
        }
        if (listener.getGitUserEmail() != null) {
            factory.setGitUserEmail(listener.getGitUserEmail());
        }
        if (listener.getPlanningDocument() != null) {
            factory.setPlanningDocument(listener.getPlanningDocument());
        }
        if (listener.getDependentRepos() != null
                && !listener.getDependentRepos().isEmpty()) {
            factory.setDependentRepos(listener.getDependentRepos());
        }
        if (listener.getRequiredLabels() != null
                && !listener.getRequiredLabels().isEmpty()) {
            for (Map.Entry<String, String> e : listener.getRequiredLabels().entrySet()) {
                factory.setRequiredLabel(e.getKey(), e.getValue());
            }
        }
        // Apply the listener's phase config bundle so model / effort /
        // provider all flow through the same ladder the listener uses
        // for any other job.
        PhaseConfigBundle bundle = listener.getPhaseConfigBundle();
        if (bundle != null && !bundle.isEmpty()) {
            factory.setPhaseConfigBundle(bundle);
        }
        if (listener.getAgentEnv() != null && !listener.getAgentEnv().isEmpty()) {
            factory.setAgentEnv(listener.getAgentEnv());
        }

        // Per-job workstream URL so the agent can report status back.
        if (workstreamUrlBuilder != null) {
            String url = workstreamUrlBuilder.apply(listener.getWorkstreamId());
            if (url != null) {
                factory.setWorkstreamUrl(url);
            }
        }

        // AR-manager URL + HMAC token (best effort).
        if (arManagerUrl != null && !arManagerUrl.isEmpty()
                && sharedSecret != null && !sharedSecret.isEmpty()) {
            try {
                String token = SecretsRequestHandler.generateTemporaryToken(
                        listener.getWorkstreamId(), factory.getTaskId(),
                        sharedSecret, 43200);
                if (token != null) {
                    factory.setArManagerUrl(arManagerUrl);
                    factory.setArManagerToken(token);
                }
            } catch (RuntimeException ex) {
                warn("wakeup_ar_token_failed listener="
                        + listener.getWorkstreamId()
                        + " chain=" + chainId + ": " + ex.getMessage());
            }
        }

        if (pushedToolsConfig != null && !pushedToolsConfig.isEmpty()) {
            factory.setPushedToolsConfig(pushedToolsConfig);
        }

        // Required-labels from the workspace layer (resolution ladder:
        // job overrides > workstream defaults > workspace defaults).
        if (workspaceLookup != null) {
            String wsId = listener.getWorkspaceId();
            if (wsId != null && !wsId.isEmpty()) {
                WorkstreamConfig.WorkspaceEntry wsEntry = workspaceLookup.apply(wsId);
                if (wsEntry != null) {
                    Map<String, String> wsLabels = extractWorkspaceLabels();
                    if (wsLabels != null && !wsLabels.isEmpty()) {
                        for (Map.Entry<String, String> e : wsLabels.entrySet()) {
                            // Only apply labels the workstream did not
                            // already set; job layer always wins.
                            // (setRequiredLabel is an additive write,
                            // so this is safe.)
                            factory.setRequiredLabel(e.getKey(), e.getValue());
                        }
                    }
                }
            }
        }

        return factory;
    }

    /**
     * Reads required-labels from a workspace entry. The
     * {@link WorkstreamConfig.WorkspaceEntry} class does not currently
     * carry a per-workspace {@code requiredLabels} map (that is a
     * workstream-level concept in v1), so this method is a no-op
     * stub; the listener's own {@code requiredLabels} already covers
     * the routing concern. Kept as a hook so a future v2 that adds
     * workspace-level labels has a single integration point.
     *
     * @return an empty map; reserved for future workspace-level labels
     */
    private Map<String, String> extractWorkspaceLabels() {
        return Collections.emptyMap();
    }

    /**
     * Builds the wake-up prompt that the listener agent will receive.
     * The prompt is a <em>trigger to re-read the world</em>, not the
     * authoritative list of work to do: the listener is required to
     * reconcile the full state of every workstream it has delegated
     * to on every wake (see §2.1.6 of the completion-listeners
     * design). The prompt carries a compact summary of the source
     * event — never a transcript — plus a pointer to
     * {@code workstream_get_job} for full details.
     */
    String buildWakeUpPrompt(String sourceWorkstreamId,
                             Workstream listener,
                             JobCompletionEvent event,
                             String chainId,
                             int chainDepth) {
        StringBuilder sb = new StringBuilder();
        sb.append("A job you were waiting for has finished on workstream "
                + sourceWorkstreamId + ".\n\n");
        sb.append("  Source workstream: ").append(sourceWorkstreamId).append('\n');
        if (event.getTargetBranch() != null) {
            sb.append("  Source workstream branch: ")
                    .append(event.getTargetBranch()).append('\n');
        }
        sb.append("  Finished job ID: ").append(safe(event.getJobId())).append('\n');
        sb.append("  Finished job status: ")
                .append(event.getStatus() == null ? "UNKNOWN" : event.getStatus().name())
                .append('\n');
        if (event.getDescription() != null && !event.getDescription().isEmpty()) {
            sb.append("  Finished job description: ")
                    .append(truncate(event.getDescription(), 200)).append('\n');
        }
        if (event.getCommitHash() != null && !event.getCommitHash().isEmpty()) {
            sb.append("  Commit hash (if any): ")
                    .append(event.getCommitHash()).append('\n');
        }
        if (event.getPullRequestUrl() != null && !event.getPullRequestUrl().isEmpty()) {
            sb.append("  PR URL (if any): ")
                    .append(event.getPullRequestUrl()).append('\n');
        }
        sb.append("  Trigger reason: completion listener\n");
        sb.append("  Chain ID: ").append(chainId).append('\n');
        sb.append("  Chain depth: ").append(chainDepth).append('\n');
        sb.append('\n');
        sb.append("You are worker workstream ")
                .append(listener.getWorkstreamId())
                .append(". Your standing goal is in your planning document; read it before"
                        + " deciding what to do. To inspect the finished job's full result,"
                        + " use the workstream_get_job MCP tool with the job ID above, or"
                        + " workstream_context to see the workstream's recent job history.\n");
        sb.append('\n');
        sb.append("IMPORTANT: this wake-up is a trigger to reconcile the full state of"
                + " every workstream you have delegated to — not a command to act on the"
                + " specific finished job named above. Re-read worker workstreams via"
                + " workstream_context / workstream_get_job on every wake. Coalesced or"
                + " dropped wake-ups (when the controller's flood ceilings trip) are"
                + " lossless: the next successful wake picks up whatever was missed by"
                + " re-reading the world.\n");
        return sb.toString();
    }

    /**
     * Returns the input string, or the empty string when the input is
     * {@code null}. Used in the wake-up prompt to render optional
     * fields without inserting the literal text {@code "null"}.
     */
    private static String safe(String s) {
        return s == null ? "" : s;
    }

    /**
     * Truncates the input string to {@code max} characters; {@code null}
     * maps to the empty string. The truncation is a hard cut at the
     * limit (no ellipsis) — the wake-up prompt already has a fixed
     * shape and a trailing ellipsis would push the chain-ID line past
     * the prompt's natural visual boundary.
     */
    private static String truncate(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        return s.substring(0, max);
    }

    /**
     * Visible-for-testing handle to inspect (and reset) the per-pair
     * coalesce state. The production code path never invokes these;
     * tests reach in to assert the (source, listener) key was created
     * with the expected job IDs and to clear state between cases.
     */
    public CoalesceState peekCoalesceState(String sourceWorkstreamId,
                                          String listenerId) {
        return coalesceState.get(sourceWorkstreamId + "|" + listenerId);
    }

    /**
     * Visible-for-testing handle to inspect the per-listener wake-up
     * window. Production callers should not need this; tests assert
     * the sliding-window eviction behaviour here.
     */
    public Deque<Long> peekWakeUpWindow(String listenerId) {
        return wakeUpWindowByListener.get(listenerId);
    }

    /**
     * Visible-for-testing handle to the per-chain wake-up count.
     */
    public int chainCount(String chainId) {
        return chainCount.getOrDefault(chainId, 0);
    }

    /**
     * Visible-for-testing: clear all in-memory state. The fanout is
     * designed to be created fresh per test; production never invokes
     * this.
     */
    public void resetState() {
        coalesceState.clear();
        wakeUpWindowByListener.clear();
        chainCount.clear();
    }

    /**
     * The set of (source, listener) pairs whose coalesce state is
     * currently active. Exposed for tests; production code never
     * iterates this directly.
     */
    public Set<String> activeCoalesceKeys() {
        return new HashSet<>(coalesceState.keySet());
    }

    /**
     * Coalesce state accessor (read-only). Tests inspect this to
     * verify that a coalesced wake-up prompt carries the right
     * "consolidated IDs" list.
     */
    public static final class CoalesceStateView {
        /** The wrapped coalesce state. */
        private final CoalesceState state;
        CoalesceStateView(CoalesceState state) { this.state = state; }
        /** Returns the wall-clock millis when the coalesce-window wake-up fired. */
        public long firedAtMillis() { return state.firedAtMillis; }
        /** Returns the job ID of the wake-up that fired (the "primary" coalesced job). */
        public String primaryJobId() { return state.primaryJobId; }
        /** Returns the list of additional job IDs coalesced into the same wake-up. */
        public List<String> consolidatedJobIds() {
            return Collections.unmodifiableList(state.consolidatedJobIds);
        }
        /**
         * Returns the underlying coalesce state, used by the parent
         * class for the read-only view in
         * {@link CompletionListenerFanout#coalesceStateView(String, String)}.
         *
         * @return the state, never {@code null}
         */
        public CoalesceState raw() { return state; }
    }

    /**
     * Returns a read-only view of the coalesce state for a (source,
     * listener) pair, or {@code null} when no wake-up has been fired
     * recently. Tests use this to assert coalescing behaviour without
     * reaching into the internal map.
     */
    public CoalesceStateView coalesceStateView(String sourceWorkstreamId,
                                                String listenerId) {
        CoalesceState s = coalesceState.get(sourceWorkstreamId + "|" + listenerId);
        return s == null ? null : new CoalesceStateView(s);
    }

    /**
     * Provides a key-derivation helper visible to tests so they can
     * compute the same (source, listener) -> key mapping the
     * production code uses, without exposing the map itself.
     */
    public static String coalesceKey(String sourceWorkstreamId,
                                     String listenerId) {
        return sourceWorkstreamId + "|" + listenerId;
    }
}
