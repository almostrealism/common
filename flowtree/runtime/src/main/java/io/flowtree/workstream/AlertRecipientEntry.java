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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.almostrealism.io.AlertDeliveryProvider;
import org.almostrealism.io.AlertRecipients;
import org.almostrealism.util.SignalWireDeliveryProvider;

import java.util.List;

/**
 * Configuration entry for a named alert recipient.
 *
 * <p>Maps an operator-chosen handle to the address an alert reaches that
 * person at. The handle is what callers name — {@code "michael"},
 * {@code "mmurray"} — so a caller asking for an alert never handles a phone
 * number, and the number can change without any caller changing.</p>
 *
 * <p>Declared under the top-level {@code alertRecipients:} key:</p>
 * <pre>
 * alertRecipients:
 *   - name: michael
 *     smsNumber: "+15551234567"
 * </pre>
 *
 * <p>The SignalWire account these messages are sent through is configured
 * separately, in {@code signalwire.properties}; only the destination belongs
 * here. That keeps API credentials out of the file the workspace tools
 * edit.</p>
 *
 * @author Michael Murray
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class AlertRecipientEntry {
    /** The operator-chosen handle callers use to name this recipient. */
    private String name;

    /** The destination phone number in E.164 format, or {@code null}. */
    private String smsNumber;

    /** Returns the handle callers use to name this recipient. */
    public String getName() { return name; }
    /** Sets the handle callers use to name this recipient. */
    public void setName(String name) { this.name = name; }

    /** Returns the destination phone number in E.164 format. */
    public String getSmsNumber() { return smsNumber; }
    /** Sets the destination phone number in E.164 format. */
    public void setSmsNumber(String smsNumber) { this.smsNumber = smsNumber; }

    /**
     * Returns the provider that reaches this recipient.
     *
     * <p>Built from the SignalWire account configured in
     * {@code signalwire.properties}, so the account credentials are declared
     * once no matter how many recipients are listed. Returns {@code null}
     * when this entry names no destination, or when no account has been
     * configured — in either case the recipient is simply unreachable, which
     * the directory reports rather than treating as a startup failure.</p>
     *
     * @return the delivery provider, or {@code null} when unreachable
     */
    public AlertDeliveryProvider deliveryProvider() {
        if (smsNumber == null || smsNumber.isBlank()) return null;

        SignalWireDeliveryProvider account = SignalWireDeliveryProvider.getDefaultProvider();
        return account == null ? null : account.withRecipient(smsNumber.trim());
    }

    /**
     * Builds the recipient directory described by the given entries.
     *
     * <p>Entries that name no reachable destination are skipped, so a
     * half-written configuration yields a directory of the recipients that
     * do work rather than no directory at all.</p>
     *
     * @param entries the configured entries, or {@code null}
     * @return the directory, never {@code null}
     */
    public static AlertRecipients directory(List<AlertRecipientEntry> entries) {
        AlertRecipients recipients = new AlertRecipients();
        if (entries == null) return recipients;

        for (AlertRecipientEntry entry : entries) {
            recipients.add(entry.getName(), entry.deliveryProvider());
        }

        return recipients;
    }
}
