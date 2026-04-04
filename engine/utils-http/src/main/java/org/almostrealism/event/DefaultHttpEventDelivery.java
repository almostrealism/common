package org.almostrealism.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.almostrealism.io.ConsoleFeatures;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * HTTP POST-based implementation of {@code EventDelivery} for sending events to a remote endpoint.
 *
 * <p>Events are serialized to JSON using Jackson's {@link com.fasterxml.jackson.databind.ObjectMapper}
 * and delivered synchronously via a {@link java.net.http.HttpClient} POST request. Delivery
 * failures are logged as warnings rather than thrown, so callers always receive a boolean result
 * indicating whether delivery was attempted without throwing.</p>
 *
 * @param <T> the concrete {@code AbstractEvent} subtype delivered by this instance
 */
public class DefaultHttpEventDelivery<T extends AbstractEvent> implements EventDelivery<T>, ConsoleFeatures {
	/** The target URI to which serialized event JSON is POSTed. */
	private final String deliveryUri;
	/** Jackson mapper used to serialize events to JSON for HTTP delivery. */
	private final ObjectMapper mapper;

	/**
	 * Constructs a delivery instance that sends events to the specified URI.
	 *
	 * @param deliveryUri the HTTP endpoint that will receive the serialized event payloads
	 */
	public DefaultHttpEventDelivery(String deliveryUri) {
		this.deliveryUri = deliveryUri;
		this.mapper = new ObjectMapper();
	}

	/**
	 * Serializes the event to JSON and POSTs it to the configured delivery URI.
	 *
	 * <p>Returns {@code false} immediately if {@code event} is {@code null}. Any exception
	 * thrown during serialization or HTTP communication is caught and logged as a warning;
	 * the method still returns {@code true} in that case because the delivery was attempted.</p>
	 *
	 * @param event the event to deliver; if {@code null}, no request is made
	 * @return {@code false} if {@code event} is {@code null}; {@code true} otherwise
	 */
	public boolean deliver(T event) {
		if (event == null) {
			return false;
		}

		try {
			HttpRequest request = HttpRequest.newBuilder()
					.uri(URI.create(deliveryUri))
					.header("Content-Type", "application/json")
					.POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(event)))
					.build();
			HttpResponse<String> response = HttpClient.newHttpClient()
					.send(request, HttpResponse.BodyHandlers.ofString());

			log("Sent " + event.getName() + " event (" + response.statusCode() + ")");
		} catch (Exception e) {
			warn(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
		}

		return true;
	}
}
