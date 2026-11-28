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

package io.flowtree.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import fi.iki.elonen.NanoHTTPD;
import fi.iki.elonen.NanoHTTPD.Response;
import io.flowtree.github.PullRequest;
import io.flowtree.slack.NotifierRegistry;
import io.flowtree.slack.SlackNotifier;
import io.flowtree.workstream.Workstream;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.BiFunction;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for {@link WorkstreamRegistrationHandler}'s dependent-repo
 * resolution logic: a branch is reused from a downstream workstream only while
 * that workstream's primary repository carries an open pull request on the
 * branch. These exercise the handler directly with a stubbed pull-request
 * lookup so the branch's companion-PR state can be controlled precisely without
 * a live GitHub connection.
 *
 * <p>The integration-level counterparts (which drive the same paths through the
 * HTTP endpoint with no GitHub token — an "unknown" PR state) live in
 * {@code io.flowtree.slack.SlackApiWorkstreamTest}.</p>
 */
public class WorkstreamRegistrationHandlerTest extends TestSuiteBase {

    /** The branch shared by the downstream workstream and the submission. */
    private static final String BRANCH = "feature/shared";
    /** Clone URL of the downstream (primary) repository that lists Common as a dependent. */
    private static final String DOWNSTREAM_REPO = "git@github.com:almostrealism/downstream.git";
    /** The {@code owner/repo} slug of {@link #DOWNSTREAM_REPO}, as the PR lookup is keyed. */
    private static final String DOWNSTREAM_OWNER_REPO = "almostrealism/downstream";
    /** Clone URL of a second downstream repository that also lists Common as a dependent. */
    private static final String SECOND_DOWNSTREAM_REPO = "git@github.com:almostrealism/downstream-two.git";
    /** The {@code owner/repo} slug of {@link #SECOND_DOWNSTREAM_REPO}. */
    private static final String SECOND_DOWNSTREAM_OWNER_REPO = "almostrealism/downstream-two";
    /** Clone URL of the submitting repository. */
    private static final String COMMON_REPO = "git@github.com:almostrealism/common.git";
    /** A registration/submission body naming {@link #BRANCH} on {@link #COMMON_REPO}. */
    private static final String COMMON_BODY =
            "{\"defaultBranch\":\"" + BRANCH + "\",\"baseBranch\":\"master\","
            + "\"repoUrl\":\"" + COMMON_REPO + "\"}";

    /** Builds a one-element pull-request listing in the given GitHub state. */
    private static List<PullRequest> prs(String state) {
        try {
            List<PullRequest> list = new ArrayList<>();
            list.add(new PullRequest(new ObjectMapper().readTree("{\"state\":\"" + state + "\"}")));
            return list;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** A notifier holding a non-archived downstream workstream that lists Common as a dependent. */
    private static SlackNotifier downstreamNotifier(boolean archived) {
        SlackNotifier notifier = new SlackNotifier(null);
        Workstream wsDownstream = new Workstream("C_DOWNSTREAM", "#w-shared");
        wsDownstream.setDefaultBranch(BRANCH);
        wsDownstream.setRepoUrl(DOWNSTREAM_REPO);
        wsDownstream.setDependentRepos(List.of(COMMON_REPO));
        wsDownstream.setArchived(archived);
        notifier.registerWorkstream(wsDownstream);
        return notifier;
    }

    /**
     * A notifier holding two non-archived downstream workstreams on {@link #BRANCH}
     * that both list Common as a dependent, with distinct primary repositories
     * ({@link #DOWNSTREAM_REPO} and {@link #SECOND_DOWNSTREAM_REPO}) so a
     * per-candidate pull-request lookup resolves them independently.
     */
    private static SlackNotifier twoDownstreamNotifier() {
        SlackNotifier notifier = downstreamNotifier(false);
        Workstream second = new Workstream("C_DOWNSTREAM_TWO", "#w-shared-two");
        second.setDefaultBranch(BRANCH);
        second.setRepoUrl(SECOND_DOWNSTREAM_REPO);
        second.setDependentRepos(List.of(COMMON_REPO));
        notifier.registerWorkstream(second);
        return notifier;
    }

    /** Constructs the handler under test bound to {@code notifier} and {@code lookup}. */
    private static WorkstreamRegistrationHandler handler(SlackNotifier notifier,
            BiFunction<String, String, List<PullRequest>> lookup) {
        NotifierRegistry notifiers = new NotifierRegistry(notifier, null);
        return new WorkstreamRegistrationHandler(notifiers, Collections.emptyMap(), null,
                session -> "", msg -> NanoHTTPD.newFixedLengthResponse(Response.Status.BAD_REQUEST,
                        "application/json", "{\"error\":\"" + msg + "\"}"),
                msg -> { }, lookup);
    }

    /**
     * Normal companion case: the downstream workstream's primary repo carries an
     * open pull request on the branch, so registering Common reuses it as a
     * dependent-repo match and creates nothing new.
     */
    @Test(timeout = 10000)
    public void dependentMatchWithOpenCompanionPrIsReused() {
        SlackNotifier notifier = downstreamNotifier(false);
        List<String[]> calls = new ArrayList<>();
        WorkstreamRegistrationHandler handler = handler(notifier, (ownerRepo, branch) -> {
            calls.add(new String[] { ownerRepo, branch });
            return prs("open");
        });

        WorkstreamRegistrationHandler.Registration result = handler.register(COMMON_BODY, BRANCH);

        assertTrue("an open companion PR must keep the dependent-repo match",
                result.matchedAsDependentRepo());
        assertTrue(result.existing());
        assertEquals(DOWNSTREAM_REPO, result.workstream().getRepoUrl());
        assertEquals("no workstream must be created for Common", 1,
                notifier.getWorkstreams().size());
        assertEquals("the PR lookup must be keyed by the primary repo's owner/repo and branch",
                1, calls.size());
        assertEquals(DOWNSTREAM_OWNER_REPO, calls.get(0)[0]);
        assertEquals(BRANCH, calls.get(0)[1]);
    }

    /**
     * The defect case: the downstream workstream's primary repo has no pull
     * request on the branch (the change touched only Common). Reusing it would
     * strand Common's own pull request, so a workstream tracking Common is
     * registered instead and the match is not reported as dependent.
     */
    @Test(timeout = 10000)
    public void dependentMatchWithNoOpenPrRegistersOwnWorkstream() {
        SlackNotifier notifier = downstreamNotifier(false);
        WorkstreamRegistrationHandler handler = handler(notifier,
                (ownerRepo, branch) -> Collections.emptyList());

        WorkstreamRegistrationHandler.Registration result = handler.register(COMMON_BODY, BRANCH);

        assertFalse("with no open companion PR the match must not be a dependent-repo match",
                result.matchedAsDependentRepo());
        assertFalse("a workstream for Common must be newly created", result.existing());
        Workstream created = notifier.findWorkstreamByBranchAndRepo(BRANCH, COMMON_REPO);
        assertNotNull("a workstream tracking (branch, Common) must now exist", created);
        assertEquals(COMMON_REPO, created.getRepoUrl());
    }

    /**
     * A closed (unmerged) companion is not an open pull request, so it does not
     * keep the dependent-repo match alive: Common gets its own workstream.
     */
    @Test(timeout = 10000)
    public void dependentMatchWithClosedCompanionPrRegistersOwnWorkstream() {
        SlackNotifier notifier = downstreamNotifier(false);
        WorkstreamRegistrationHandler handler = handler(notifier,
                (ownerRepo, branch) -> prs("closed"));

        WorkstreamRegistrationHandler.Registration result = handler.register(COMMON_BODY, BRANCH);

        assertFalse("a closed companion PR must not keep the dependent-repo match",
                result.matchedAsDependentRepo());
        assertNotNull(notifier.findWorkstreamByBranchAndRepo(BRANCH, COMMON_REPO));
    }

    /**
     * When GitHub cannot be asked (the lookup returns {@code null}), the
     * deduplication is preserved — the dependent-repo match stands rather than
     * minting a duplicate on a transient outage.
     */
    @Test(timeout = 10000)
    public void dependentMatchWithUnknownPrStateIsReused() {
        SlackNotifier notifier = downstreamNotifier(false);
        WorkstreamRegistrationHandler handler = handler(notifier,
                (ownerRepo, branch) -> null);

        WorkstreamRegistrationHandler.Registration result = handler.register(COMMON_BODY, BRANCH);

        assertTrue("unknown PR state must preserve the dependent-repo match",
                result.matchedAsDependentRepo());
        assertEquals(DOWNSTREAM_REPO, result.workstream().getRepoUrl());
        assertEquals(1, notifier.getWorkstreams().size());
    }

    /**
     * An archived downstream workstream is excluded by the lookup itself, so it
     * never reaches the pull-request check and Common gets its own workstream.
     * The stubbed lookup fails the test if it is consulted.
     */
    @Test(timeout = 10000)
    public void archivedDependentMatchRegistersOwnWorkstreamWithoutConsultingPr() {
        SlackNotifier notifier = downstreamNotifier(true);
        WorkstreamRegistrationHandler handler = handler(notifier, (ownerRepo, branch) -> {
            throw new AssertionError("an archived candidate must never reach the PR lookup");
        });

        WorkstreamRegistrationHandler.Registration result = handler.register(COMMON_BODY, BRANCH);

        assertFalse("an archived candidate must not be a dependent-repo match",
                result.matchedAsDependentRepo());
        Workstream created = notifier.findWorkstreamByBranchAndRepo(BRANCH, COMMON_REPO);
        assertNotNull("a fresh workstream tracking Common must be created", created);
    }

    /**
     * An exact {@code (branch, repo)} match wins before the dependent lookup, so
     * it is never a dependent-repo match and the pull-request check is not run.
     */
    @Test(timeout = 10000)
    public void exactMatchIsNeverADependentMatch() {
        SlackNotifier notifier = new SlackNotifier(null);
        Workstream wsCommon = new Workstream("C_COMMON", "#w-shared");
        wsCommon.setDefaultBranch(BRANCH);
        wsCommon.setRepoUrl(COMMON_REPO);
        notifier.registerWorkstream(wsCommon);
        WorkstreamRegistrationHandler handler = handler(notifier, (ownerRepo, branch) -> {
            throw new AssertionError("an exact match must never reach the PR lookup");
        });

        WorkstreamRegistrationHandler.Registration result = handler.register(COMMON_BODY, BRANCH);

        assertTrue(result.existing());
        assertFalse(result.matchedAsDependentRepo());
        assertSame(wsCommon, result.workstream());
    }

    /**
     * The submit path resolves the same way: with no open companion PR,
     * {@code resolveOrRegister} (used by {@code /api/submit}) returns a plain
     * registration for Common — not a dependent-repo match — so the job is
     * queued on Common rather than silently skipped.
     */
    @Test(timeout = 10000)
    public void resolveOrRegisterWithNoOpenPrDoesNotReportDependentMatch() {
        SlackNotifier notifier = downstreamNotifier(false);
        WorkstreamRegistrationHandler handler = handler(notifier,
                (ownerRepo, branch) -> Collections.emptyList());

        WorkstreamRegistrationHandler.Registration result = handler.resolveOrRegister(
                COMMON_BODY, BRANCH, COMMON_REPO, null, true);

        assertNull(result.failure());
        assertFalse("submit resolution must not skip Common as a dependent match",
                result.matchedAsDependentRepo());
        assertNotNull(notifier.findWorkstreamByBranchAndRepo(BRANCH, COMMON_REPO));
    }

    /**
     * The submit path still honors a live companion: with an open PR on the
     * primary repo, {@code resolveOrRegister} reports the dependent-repo match so
     * {@code /api/submit} declines to queue a job on the wrong checkout.
     */
    @Test(timeout = 10000)
    public void resolveOrRegisterWithOpenPrReportsDependentMatch() {
        SlackNotifier notifier = downstreamNotifier(false);
        WorkstreamRegistrationHandler handler = handler(notifier,
                (ownerRepo, branch) -> prs("open"));

        WorkstreamRegistrationHandler.Registration result = handler.resolveOrRegister(
                COMMON_BODY, BRANCH, COMMON_REPO, null, true);

        assertTrue("an open companion PR must still skip the duplicate submission",
                result.matchedAsDependentRepo());
        assertEquals(DOWNSTREAM_REPO, result.workstream().getRepoUrl());
        assertEquals(1, notifier.getWorkstreams().size());
    }

    /**
     * Several downstream workstreams can share the branch and depend on Common
     * at once. The dependent lookup used to consider only the first, so a stale
     * first candidate could bypass a valid later one. All candidates must be
     * examined: when one primary repo has no open companion PR but another does,
     * the live one is reused and no workstream is created for Common.
     */
    @Test(timeout = 10000)
    public void multipleDependentCandidatesReuseTheOneWithLiveCompanionPr() {
        SlackNotifier notifier = twoDownstreamNotifier();
        WorkstreamRegistrationHandler handler = handler(notifier, (ownerRepo, branch) ->
                SECOND_DOWNSTREAM_OWNER_REPO.equals(ownerRepo) ? prs("open") : Collections.emptyList());

        WorkstreamRegistrationHandler.Registration result = handler.register(COMMON_BODY, BRANCH);

        assertTrue("a live companion on any candidate must keep the dependent-repo match",
                result.matchedAsDependentRepo());
        assertEquals(SECOND_DOWNSTREAM_REPO, result.workstream().getRepoUrl());
        assertEquals("no workstream must be created for Common", 2,
                notifier.getWorkstreams().size());
    }

    /**
     * With several downstream candidates and no open companion PR on any of
     * their primary repos, none still covers the branch, so Common gets its own
     * workstream rather than being absorbed by the first stale candidate.
     */
    @Test(timeout = 10000)
    public void multipleDependentCandidatesWithNoOpenPrRegisterOwnWorkstream() {
        SlackNotifier notifier = twoDownstreamNotifier();
        WorkstreamRegistrationHandler handler = handler(notifier,
                (ownerRepo, branch) -> Collections.emptyList());

        WorkstreamRegistrationHandler.Registration result = handler.register(COMMON_BODY, BRANCH);

        assertFalse("no live companion on any candidate must not be a dependent-repo match",
                result.matchedAsDependentRepo());
        Workstream created = notifier.findWorkstreamByBranchAndRepo(BRANCH, COMMON_REPO);
        assertNotNull("a workstream tracking (branch, Common) must now exist", created);
        assertEquals(COMMON_REPO, created.getRepoUrl());
    }

    /**
     * An unknown PR state on a later candidate still preserves deduplication:
     * with the first candidate's primary repo confirmed to have no open PR and
     * the second's lookup unreadable ({@code null}), the second is reused rather
     * than minting a duplicate for Common.
     */
    @Test(timeout = 10000)
    public void multipleDependentCandidatesReuseAnUnknownCandidate() {
        SlackNotifier notifier = twoDownstreamNotifier();
        WorkstreamRegistrationHandler handler = handler(notifier, (ownerRepo, branch) ->
                SECOND_DOWNSTREAM_OWNER_REPO.equals(ownerRepo) ? null : Collections.emptyList());

        WorkstreamRegistrationHandler.Registration result = handler.register(COMMON_BODY, BRANCH);

        assertTrue("an unknown candidate must preserve the dependent-repo match",
                result.matchedAsDependentRepo());
        assertEquals(SECOND_DOWNSTREAM_REPO, result.workstream().getRepoUrl());
        assertEquals(2, notifier.getWorkstreams().size());
    }

    /**
     * The submit path defaults a workstream it creates without a
     * {@code settledCiTrigger}, the same as the direct registration endpoint:
     * {@code resolveOrRegister} rebuilds the request through
     * {@code registrationRequest}, which omits an absent field so the create
     * path's field-absent default ({@code deployment:test}) applies.
     */
    @Test(timeout = 10000)
    public void resolveOrRegisterDefaultsAbsentSettledCiTrigger() {
        SlackNotifier notifier = new SlackNotifier(null);
        WorkstreamRegistrationHandler handler = handler(notifier,
                (ownerRepo, branch) -> Collections.emptyList());
        String body = "{\"defaultBranch\":\"feature/submit-absent\",\"baseBranch\":\"master\","
                + "\"repoUrl\":\"" + COMMON_REPO + "\"}";

        WorkstreamRegistrationHandler.Registration result = handler.resolveOrRegister(
                body, "feature/submit-absent", COMMON_REPO, null, true);

        assertNull(result.failure());
        Workstream created = notifier.findWorkstreamByBranchAndRepo("feature/submit-absent", COMMON_REPO);
        assertNotNull("a workstream must be created on submit", created);
        assertNotNull("an absent settledCiTrigger on the submit path gets the default",
                created.getSettledCiTrigger());
        assertEquals("deployment:test", created.getSettledCiTrigger().spec());
    }

    /**
     * The submit path preserves an <em>explicit empty</em> {@code settledCiTrigger}
     * as a deliberate clear rather than collapsing it into the default. The
     * rebuilt registration request keeps the field's presence, so {@code register}
     * reads the empty value as a clear exactly as the direct endpoint does; the
     * previous request builder dropped an empty value, turning an explicit clear
     * into {@code deployment:test} and diverging from direct registration.
     */
    @Test(timeout = 10000)
    public void resolveOrRegisterExplicitEmptySettledCiTriggerIsNotDefaulted() {
        SlackNotifier notifier = new SlackNotifier(null);
        WorkstreamRegistrationHandler handler = handler(notifier,
                (ownerRepo, branch) -> Collections.emptyList());
        String body = "{\"defaultBranch\":\"feature/submit-empty\",\"baseBranch\":\"master\","
                + "\"repoUrl\":\"" + COMMON_REPO + "\",\"settledCiTrigger\":\"\"}";

        WorkstreamRegistrationHandler.Registration result = handler.resolveOrRegister(
                body, "feature/submit-empty", COMMON_REPO, null, true);

        assertNull(result.failure());
        Workstream created = notifier.findWorkstreamByBranchAndRepo("feature/submit-empty", COMMON_REPO);
        assertNotNull("a workstream must be created on submit", created);
        assertNull("an explicit empty settledCiTrigger on the submit path is honoured as a clear",
                created.getSettledCiTrigger());
    }

    /**
     * The submit path carries an explicit non-empty {@code settledCiTrigger}
     * through unchanged: the rebuilt registration request names it, so the
     * created workstream uses exactly the trigger the submission asked for
     * rather than the default.
     */
    @Test(timeout = 10000)
    public void resolveOrRegisterPreservesExplicitSettledCiTrigger() {
        SlackNotifier notifier = new SlackNotifier(null);
        WorkstreamRegistrationHandler handler = handler(notifier,
                (ownerRepo, branch) -> Collections.emptyList());
        String body = "{\"defaultBranch\":\"feature/submit-explicit\",\"baseBranch\":\"master\","
                + "\"repoUrl\":\"" + COMMON_REPO + "\",\"settledCiTrigger\":\"workflow:build.yaml\"}";

        WorkstreamRegistrationHandler.Registration result = handler.resolveOrRegister(
                body, "feature/submit-explicit", COMMON_REPO, null, true);

        assertNull(result.failure());
        Workstream created = notifier.findWorkstreamByBranchAndRepo("feature/submit-explicit", COMMON_REPO);
        assertNotNull("a workstream must be created on submit", created);
        assertNotNull("an explicit settledCiTrigger on the submit path is preserved",
                created.getSettledCiTrigger());
        assertEquals("workflow:build.yaml", created.getSettledCiTrigger().spec());
    }
}
