package org.almostrealism.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.almostrealism.io.ConsoleFeatures;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

public class DefaultHttpEventDelivery<T extends AbstractEvent> implements EventDelivery<T>, ConsoleFeatures {
	private final String deliveryUri;
	private final ObjectMapper mapper;

	public DefaultHttpEventDelivery(String deliveryUri) {
		this.deliveryUri = deliveryUri;
		this.mapper = new ObjectMapper();
	}

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
