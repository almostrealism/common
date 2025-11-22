/*
 * Copyright 2016 Michael Murray
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
package org.almostrealism.util;

import org.almostrealism.algebra.Vector;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.net.URL;

/**
 * Client for retrieving satellite and map imagery from Google Maps Static API.
 *
 * <p>This class fetches map images for a specified geographic extent (latitude/longitude
 * bounding box) at the optimal zoom level, then extracts the requested region from
 * the downloaded tile.</p>
 *
 * <h2>Coordinate System</h2>
 * <p>Geographic coordinates use the format {@code Vector(latitude, longitude, 0)}
 * where latitude is the X component and longitude is the Y component.</p>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * GoogleImagery imagery = new GoogleImagery("YOUR_API_KEY");
 *
 * // Define bounding box (SW and NE corners)
 * Vector sw = new Vector(37.7749, -122.4194, 0);  // San Francisco
 * Vector ne = new Vector(37.7849, -122.4094, 0);
 *
 * // Fetch satellite imagery
 * BufferedImage image = imagery.getImagery(sw, ne, MapType.SATELLITE);
 *
 * // Save or process the image
 * ImageIO.write(image, "png", new File("sf_satellite.png"));
 * }</pre>
 *
 * <h2>API Limits</h2>
 * <p>Uses Google Maps Static API free tier limits:</p>
 * <ul>
 *   <li>Maximum tile size: 640x640 pixels</li>
 *   <li>Maximum scale: 2x (1280x1280 actual pixels)</li>
 *   <li>22 zoom levels available</li>
 * </ul>
 *
 * @author Dan Chivers
 */
public class GoogleImagery {
	private static String apiKey;

	private static final String REQUEST_URL = "https://maps.googleapis.com/maps/api/staticmap";
	private static final String REQUEST_PARAMS = "?center=%s,%s&zoom=%s&size=%sx%s&scale=%s&maptype=%s&key=%s";

	private static final int ZOOM_LEVELS = 22;
	private static final int SCALE = 2;                         // Max for the free plan.
	private static final int TILE_SIZE = 640 * SCALE;           // Max for the free plan.

	private static final double MERCATOR_RANGE = 256;
	private static final Vector PX_ORIGIN = new Vector(MERCATOR_RANGE / 2, MERCATOR_RANGE / 2, 0);
	private static final double PX_LON_DEGREE = MERCATOR_RANGE / 360;
	private static final double PX_LON_RAD = MERCATOR_RANGE / (2 * Math.PI);

	private static final boolean ADD_MARKERS = false;

	/**
	 * Creates a new GoogleImagery client with the specified API key.
	 *
	 * @param apiKey the Google Maps API key
	 */
	public GoogleImagery(String apiKey) {
		GoogleImagery.apiKey = apiKey;
	}

	/**
	 * Retrieves map imagery for the specified geographic extent.
	 *
	 * <p>The method automatically determines the optimal zoom level to fit
	 * the requested extent, downloads the map tile, and extracts the
	 * region matching the bounding box.</p>
	 *
	 * @param swLatLng the southwest corner as Vector(latitude, longitude, 0)
	 * @param neLatLng the northeast corner as Vector(latitude, longitude, 0)
	 * @param mapType  the type of map imagery to retrieve
	 * @return a BufferedImage containing the extracted imagery
	 * @throws Exception if the API request fails or image cannot be read
	 */
	public BufferedImage getImagery(Vector swLatLng, Vector neLatLng, MapType mapType) throws Exception {
		Vector center = getCenter(swLatLng, neLatLng);
		int zoom = determineZoomLevel(center, swLatLng);
		String requestUrl = buildRequest(center, zoom, mapType);

		if (ADD_MARKERS) {
			requestUrl = addMarkers(requestUrl, center, swLatLng, neLatLng);
		}

		System.out.println(requestUrl);
		BufferedImage fullImage = ImageIO.read(new URL(requestUrl));

		return extractExtent(fullImage, zoom, center, swLatLng);
	}

	private static Vector getCenter(Vector swLatLng, Vector neLatLng) {
		return new Vector(swLatLng.getX() + (neLatLng.getX() - swLatLng.getX()) / 2,
				swLatLng.getY() + (neLatLng.getY() - swLatLng.getY()) / 2,
				0);
	}

	private int determineZoomLevel(Vector center, Vector swLatLng) {
		int zoom = ZOOM_LEVELS;
		while (zoom >= 0) {
			Vector[] corners = getCorners(center, zoom);
			Vector sw = corners[0];
			if (sw.getX() <= swLatLng.getX() && sw.getY() < swLatLng.getY()) {
				break;
			}
			--zoom;
		}
		return zoom;
	}

	private BufferedImage extractExtent(BufferedImage image, int zoom, Vector center, Vector swLatLng) {
		double scale = Math.pow(2, zoom) * SCALE;

		Vector centerPixel = latLngToPoint(center).multiply(scale);
		Vector swPixel = latLngToPoint(swLatLng).multiply(scale);

		int dx = (int) ((centerPixel.getX() - swPixel.getX()) * 2);
		int dy = (int) ((swPixel.getY() - centerPixel.getY()) * 2);
		int xMin = (int) (TILE_SIZE / 2d - dx / 2d);
		int yMin = (int) (TILE_SIZE / 2d - dy / 2d);

		return image.getSubimage(xMin, yMin, dx, dy);
	}

	private Vector[] getCorners(Vector centerLatLng, int zoom) {
		double scale = Math.pow(2, zoom) * SCALE;

		Vector centerPoint = latLngToPoint(centerLatLng);
		Vector swPoint = new Vector(centerPoint.getX() - (TILE_SIZE / 2d) / scale,
				centerPoint.getY() + (TILE_SIZE / 2d) / scale,
				0);
		Vector nePoint = new Vector(centerPoint.getX() + (TILE_SIZE / 2d) / scale,
				centerPoint.getY() - (TILE_SIZE / 2d) / scale,
				0);

		Vector swLatLng = pointToLatLng(swPoint);
		Vector neLatLng = pointToLatLng(nePoint);

		return new Vector[]{swLatLng, neLatLng};
	}

	private Vector latLngToPoint(Vector latLng) {
		double x = PX_ORIGIN.getX() + latLng.getY() * PX_LON_DEGREE;
		double sinY = bound(Math.sin(Math.toRadians(latLng.getX())), -0.9999, 0.9999);
		double y = PX_ORIGIN.getY() + 0.5 * Math.log((1 + sinY) / (1 - sinY)) * -PX_LON_RAD;

		return new Vector(x, y, 0);
	}

	private Vector pointToLatLng(Vector point) {
		double lng = (point.getX() - PX_ORIGIN.getX()) / PX_LON_DEGREE;
		double latRadians = (point.getY() - PX_ORIGIN.getY()) / -PX_LON_RAD;
		double lat = Math.toDegrees(2 * Math.atan(Math.exp(latRadians)) - Math.PI / 2);

		return new Vector(lat, lng, 0);
	}

	private double bound(double value, double min, double max) {
		return value < min ? min : value > max ? max : value;
	}

	private String buildRequest(Vector center, int zoom, MapType mapType) {
		return String.format(REQUEST_URL + REQUEST_PARAMS, center.getX(), center.getY(), zoom, TILE_SIZE, TILE_SIZE,
				SCALE, mapType.value, apiKey);
	}

	private String addMarkers(String request, Vector... markers) {
		StringBuilder sb = new StringBuilder().append("&markers=");
		for (int i=0; i<markers.length; ++i) {
			Vector marker = markers[i];
			sb.append(marker.getX()).append(",").append(marker.getY());
			if (i < markers.length-1) {
				sb.append("|");
			}
		}
		return request + sb.toString();
	}

	/**
	 * Types of map imagery available from the Google Maps Static API.
	 */
	public enum MapType {
		/** Standard road map showing streets and labels */
		ROADMAP     ("roadmap"),
		/** Satellite imagery */
		SATELLITE   ("satellite"),
		/** Satellite imagery with road overlay */
		HYBRID      ("hybrid"),
		/** Physical terrain map showing elevation */
		TERRAIN     ("terrain");

		String value;

		MapType(String value) {
			this.value = value;
		}
	}
}
