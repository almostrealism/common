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

package org.almostrealism.io;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * A directory of named alert recipients, each with the
 * {@link AlertDeliveryProvider} that reaches them.
 *
 * <p>This is the targeted counterpart to {@link Console#alert(Alert)}. The
 * console broadcasts an alert to every provider registered on it, which is
 * what a system-wide condition wants; this directory delivers an alert to
 * the specific people it names, which is what a message addressed to
 * someone wants. A recipient name is a short handle chosen by the operator
 * ({@code "michael"}, {@code "mmurray"}), not an address — the address lives
 * inside the provider, so the caller never handles a phone number or an
 * e-mail.</p>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * AlertRecipients recipients = new AlertRecipients();
 * recipients.add("michael", smsProviderFor("+15551234567"));
 *
 * List<String> unknown = recipients.send(
 *         new Alert(Alert.Severity.INFO, "Build finished"),
 *         List.of("michael", "nobody"));
 * // unknown == ["nobody"]; michael was texted
 * }</pre>
 *
 * <p>Providers registered here are deliberately not registered on a
 * {@link Console}: doing so would turn every targeted recipient into a
 * subscriber to every broadcast alert in the process.</p>
 *
 * @see Alert
 * @see AlertDeliveryProvider
 */
public class AlertRecipients implements ConsoleFeatures {

	/**
	 * Providers keyed by recipient name. Concurrent because the directory is
	 * typically populated once at startup and then read by request threads.
	 */
	private final Map<String, AlertDeliveryProvider> providers = new ConcurrentHashMap<>();

	/**
	 * Registers the provider that reaches the named recipient, replacing any
	 * provider already registered under that name.
	 *
	 * @param name     the recipient handle; ignored when null or blank
	 * @param provider the provider reaching that recipient; ignored when null
	 * @return this directory, for chaining
	 */
	public AlertRecipients add(String name, AlertDeliveryProvider provider) {
		if (name == null || name.isBlank() || provider == null) return this;
		providers.put(name.trim(), provider);
		return this;
	}

	/**
	 * Returns the provider reaching the named recipient.
	 *
	 * @param name the recipient handle
	 * @return the provider, or {@code null} when the name is not registered
	 */
	public AlertDeliveryProvider providerFor(String name) {
		return name == null ? null : providers.get(name.trim());
	}

	/**
	 * Returns whether the named recipient is registered.
	 *
	 * @param name the recipient handle
	 * @return {@code true} when a provider is registered under that name
	 */
	public boolean contains(String name) {
		return providerFor(name) != null;
	}

	/**
	 * Returns every registered recipient name, sorted.
	 *
	 * @return the recipient handles
	 */
	public List<String> names() {
		List<String> result = new ArrayList<>(providers.keySet());
		result.sort(String::compareTo);
		return result;
	}

	/**
	 * Returns the number of registered recipients.
	 *
	 * @return the recipient count
	 */
	public int size() {
		return providers.size();
	}

	/**
	 * Delivers the alert to each named recipient this directory knows, and
	 * returns the names it does not know.
	 *
	 * <p>An unknown name is reported rather than thrown, so that alerting a
	 * group still reaches everyone reachable when one handle is stale. A
	 * provider that throws is likewise contained: it is logged and the
	 * remaining recipients are still attempted, and its name is <em>not</em>
	 * reported as unknown, because the name resolved.</p>
	 *
	 * @param alert the alert to deliver
	 * @param names the recipient handles to deliver it to
	 * @return the names that are not registered, in the order given; empty
	 *         when every name resolved
	 */
	public List<String> send(Alert alert, Collection<String> names) {
		List<String> unknown = new ArrayList<>();
		if (alert == null || names == null) return unknown;

		for (String name : names) {
			AlertDeliveryProvider provider = providerFor(name);

			if (provider == null) {
				unknown.add(name);
				continue;
			}

			try {
				provider.sendAlert(alert);
			} catch (RuntimeException ex) {
				warn("Failed to deliver an alert to " + name, ex);
			}
		}

		return unknown;
	}

	/**
	 * Returns a consumer that delivers each message it accepts to every recipient
	 * in this directory as an {@link Alert} of the given severity.
	 *
	 * <p>This is the directory's view as a plain {@link Consumer} of message
	 * text, for a caller that produces escalation text and should not itself
	 * assemble an {@link Alert} or enumerate recipients — it hands the text to
	 * the returned sink and the directory does the rest. Recipients are resolved
	 * at delivery time, so a recipient added after this call still receives later
	 * messages, and an empty directory makes the sink a harmless no-op.</p>
	 *
	 * @param severity the severity stamped on every alert the sink delivers
	 * @return a consumer that broadcasts each accepted message to all recipients
	 */
	public Consumer<String> sink(Alert.Severity severity) {
		return text -> send(new Alert(severity, text), names());
	}
}
