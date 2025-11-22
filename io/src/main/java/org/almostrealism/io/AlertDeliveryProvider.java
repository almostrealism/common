/*
 * Copyright 2024 Michael Murray
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

/**
 * A service provider interface for delivering alerts to external systems.
 *
 * <p>Implementations of this interface can be registered with a {@link Console}
 * to receive {@link Alert} notifications. Common implementations might send
 * alerts via email, SMS, Slack, webhooks, or other notification channels.</p>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * // Create a custom alert provider
 * AlertDeliveryProvider emailProvider = alert -> {
 *     if (alert.getSeverity() == Alert.Severity.ERROR) {
 *         sendEmail("ops@example.com", "Alert: " + alert.getMessage());
 *     }
 * };
 *
 * // Register with console
 * Console.root().addAlertDeliveryProvider(emailProvider);
 *
 * // Now alerts will be delivered to the provider
 * Console.root().alert(Alert.Severity.ERROR, "Database connection lost");
 * }</pre>
 *
 * <h2>Implementation Notes</h2>
 * <ul>
 *   <li>Providers should handle exceptions internally to avoid disrupting other providers</li>
 *   <li>Consider filtering by severity to avoid overwhelming notification channels</li>
 *   <li>Alerts propagate to parent consoles, so register providers at the appropriate level</li>
 * </ul>
 *
 * @see Alert
 * @see Console#addAlertDeliveryProvider(AlertDeliveryProvider)
 */
public interface AlertDeliveryProvider {
	/**
	 * Sends an alert through this delivery provider.
	 *
	 * @param alert the alert to deliver
	 */
	void sendAlert(Alert alert);
}
