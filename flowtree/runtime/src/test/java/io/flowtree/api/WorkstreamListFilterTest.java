/*
 * Copyright 2026 Michael Murray
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.flowtree.api;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import fi.iki.elonen.NanoHTTPD;
import io.flowtree.slack.SlackNotifier;
import io.flowtree.workstream.Workstream;
import org.almostrealism.util.TestSuiteBase;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Server-side filtering on {@code GET /api/workstreams}.
 *
 * <p>Answering "which workstreams match this predicate?" previously meant
 * listing every workstream and scanning the result, one round trip per entry
 * to fill in what the listing omitted. The filters move the predicate to the
 * side that already holds the data.</p>
 *
 * <p>These drive the real endpoint over HTTP rather than calling the handler
 * directly, because the part most likely to be wrong is the query-parameter
 * parsing — absent versus empty versus false — and a direct call would skip
 * exactly that.</p>
 */
public class WorkstreamListFilterTest extends TestSuiteBase {

	/** Endpoint under test, bound to an ephemeral port. */
	private FlowTreeApiEndpoint endpoint;

	/** Notifier owning the workstreams the endpoint lists. */
	private SlackNotifier notifier;

	/** Registers a small fleet spanning both workspaces, repositories and flags. */
	@Before
	public void setUp() throws IOException {
		notifier = new SlackNotifier(null);

		Workstream live = new Workstream("ws-live", "C1", "#live");
		live.setWorkspaceId("space-a");
		live.setRepoUrl("git@github.com:org/alpha.git");
		notifier.registerWorkstream(live);

		Workstream dispatcher = new Workstream("ws-dispatch", "C2", "#dispatch");
		dispatcher.setWorkspaceId("space-a");
		dispatcher.setRepoUrl("https://github.com/org/beta");
		dispatcher.setDispatchCapable(true);
		notifier.registerWorkstream(dispatcher);

		Workstream other = new Workstream("ws-other", "C3", "#other");
		other.setWorkspaceId("space-b");
		other.setRepoUrl("git@github.com:org/beta.git");
		notifier.registerWorkstream(other);

		Workstream archived = new Workstream("ws-archived", "C4", "#archived");
		archived.setWorkspaceId("space-a");
		archived.setRepoUrl("git@github.com:org/alpha.git");
		archived.setArchived(true);
		notifier.registerWorkstream(archived);

		Workstream standing = new Workstream("ws-standing", "C5", "#standing");
		standing.setWorkspaceId("space-c");
		standing.setRepoUrl("git@github.com:org/gamma.git");
		standing.setKind("standing");
		notifier.registerWorkstream(standing);

		endpoint = new FlowTreeApiEndpoint(0, notifier);
		endpoint.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);
	}

	/** Releases the ephemeral port between tests. */
	@After
	public void tearDown() {
		if (endpoint != null) endpoint.stop();
	}

	/** No filters lists every live workstream and no archived one. */
	@Test(timeout = 10000)
	public void unfilteredListsLiveWorkstreams() throws Exception {
		String body = get("");
		assertTrue(body.contains("ws-live"));
		assertTrue(body.contains("ws-dispatch"));
		assertTrue(body.contains("ws-other"));
		assertFalse("archived workstreams stay out of the default listing",
			body.contains("ws-archived"));
	}

	/** The workspace filter narrows to one workspace. */
	@Test(timeout = 10000)
	public void filtersByWorkspace() throws Exception {
		String body = get("?workspaceId=space-b");
		assertTrue(body.contains("ws-other"));
		assertFalse(body.contains("ws-live"));
		assertFalse(body.contains("ws-dispatch"));
	}

	/**
	 * The repository filter matches on repository identity, so the SSH and
	 * HTTPS spellings of one repository are the same repository. Requiring a
	 * caller to guess which spelling was registered would make the filter
	 * unusable for the question it exists to answer.
	 */
	@Test(timeout = 10000)
	public void filtersByRepositoryAcrossUrlSpellings() throws Exception {
		String body = get("?repoUrl=git@github.com:org/beta.git");
		assertTrue("the https-registered workstream must match an ssh-spelled filter",
			body.contains("ws-dispatch"));
		assertTrue(body.contains("ws-other"));
		assertFalse(body.contains("ws-live"));
	}

	/** The dispatch filter selects on the flag. */
	@Test(timeout = 10000)
	public void filtersByDispatchCapability() throws Exception {
		String enabled = get("?dispatchCapable=true");
		assertTrue(enabled.contains("ws-dispatch"));
		assertFalse(enabled.contains("ws-live"));

		String disabled = get("?dispatchCapable=false");
		assertTrue(disabled.contains("ws-live"));
		assertFalse("dispatchCapable=false must exclude, not be ignored",
			disabled.contains("ws-dispatch"));
	}

	/** The archived selector reaches archived entries the default hides. */
	@Test(timeout = 10000)
	public void archivedSelectorSelectsArchivedOnly() throws Exception {
		String body = get("?archived=true");
		assertTrue(body.contains("ws-archived"));
		assertFalse(body.contains("ws-live"));
	}

	/** The older includeArchived parameter still works. */
	@Test(timeout = 10000)
	public void includeArchivedStillWorks() throws Exception {
		String body = get("?includeArchived=true");
		assertTrue(body.contains("ws-archived"));
		assertTrue(body.contains("ws-live"));
	}

	/** Filters compose, which is the point — one call, one predicate. */
	@Test(timeout = 10000)
	public void filtersCompose() throws Exception {
		String body = get("?workspaceId=space-a&dispatchCapable=true");
		assertTrue(body.contains("ws-dispatch"));
		assertFalse(body.contains("ws-live"));
		assertFalse(body.contains("ws-other"));
	}

	/**
	 * An empty parameter value is absent, not a filter on the empty string.
	 * A caller building a query from optional values sends these routinely,
	 * and matching them literally would return nothing at all.
	 */
	@Test(timeout = 10000)
	public void emptyParameterValuesAreIgnored() throws Exception {
		String body = get("?workspaceId=&repoUrl=");
		assertTrue(body.contains("ws-live"));
		assertTrue(body.contains("ws-other"));
	}

	/**
	 * Enrichment is off by default, so an ordinary listing is unchanged by the
	 * status fields existing. This is what keeps the common call cheap.
	 *
	 * @throws Exception if the request fails
	 */
	@Test(timeout = 10000)
	public void statusFieldsAreAbsentUnlessRequested() throws Exception {
		String body = get("");
		assertFalse("lastJobStatus must not appear unless include_status asked",
			body.contains("lastJobStatus"));
		assertFalse("pullRequest must not appear unless include_pull_request asked",
			body.contains("pullRequest"));
	}

	/**
	 * Asking for enrichment when no job store is configured yields the plain
	 * listing rather than an error. A controller without job stats is a
	 * supported configuration, and the filters must keep working there.
	 *
	 * @throws Exception if the request fails
	 */
	@Test(timeout = 10000)
	public void enrichmentWithoutAStoreDegradesQuietly() throws Exception {
		String body = get("?includeStatus=true&includePullRequest=true");
		assertTrue("the listing itself must still be returned", body.contains("ws-live"));
		assertFalse("no status field can be invented without a store",
			body.contains("lastJobStatus"));
	}

	/**
	 * Enrichment composes with the filters: the caller narrows first and pays
	 * the per-workstream read only for what survives.
	 *
	 * @throws Exception if the request fails
	 */
	@Test(timeout = 10000)
	public void enrichmentComposesWithFilters() throws Exception {
		String body = get("?workspaceId=space-b&includeStatus=true");
		assertTrue(body.contains("ws-other"));
		assertFalse("the filter still applies when enrichment is on",
			body.contains("ws-live"));
	}

	/**
	 * A {@code lifecycle} filter must trigger classification on its own,
	 * without requiring the caller to separately pass {@code includeLifecycle}
	 * — otherwise the filter compares against a classification that never ran
	 * and drops every row.
	 *
	 * @throws Exception if the request fails
	 */
	@Test(timeout = 10000)
	public void lifecycleFilterWorksWithoutIncludeLifecycle() throws Exception {
		String body = get("?lifecycle=standing");
		assertTrue("the lifecycle filter must trigger classification on its own",
			body.contains("ws-standing"));
		assertFalse(body.contains("ws-live"));
		assertFalse(body.contains("ws-dispatch"));
		assertFalse(body.contains("ws-other"));
	}

	/**
	 * Issues a GET against the workstream listing.
	 *
	 * @param query the query string, including its leading {@code ?}, or empty
	 * @return the response body
	 */
	private String get(String query) throws Exception {
		URL url = new URL("http://localhost:" + endpoint.getListeningPort()
			+ "/api/workstreams" + query);
		HttpURLConnection conn = (HttpURLConnection) url.openConnection();
		conn.setRequestMethod("GET");
		assertEquals(200, conn.getResponseCode());
		return new String(conn.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
	}
}
