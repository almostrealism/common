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

/*
 * Copyright (C) 2005  Mike Murray
 *
 *  All rights reserved.
 *  This document may not be reused without
 *  express written permission from Mike Murray.
 *
 */

package org.almostrealism.io;

import java.io.PrintStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.List;

/**
 * A simple RSS 2.0 feed generator.
 *
 * <p>RSSFeed provides functionality to create and output RSS 2.0 format feeds.
 * It supports basic feed metadata (title, description, link) and item management
 * with optional images.</p>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * RSSFeed feed = new RSSFeed("My Feed", "A sample RSS feed");
 * feed.setLink("http://example.com/feed");
 *
 * RSSFeed.Item item = new RSSFeed.Item("Article Title", "Article description");
 * item.setImage("http://example.com/image.jpg");
 * feed.postItem(item);
 *
 * feed.write(System.out, 60);  // TTL of 60 minutes
 * }</pre>
 *
 * @author Mike Murray
 */
public class RSSFeed {
	/**
	 * Represents a single item in the RSS feed.
	 */
	public static class Item {
		private String title, text, image;

		/**
		 * Creates a new RSS item with the specified title and text.
		 *
		 * @param title the item title
		 * @param text the item description/content
		 */
		public Item(String title, String text) {
			this.title = title;
			this.text = text;
		}

		/**
		 * Sets the image URL for this item.
		 *
		 * @param image the image URL
		 */
		public void setImage(String image) { this.image = image; }

		/**
		 * Returns the image URL for this item.
		 *
		 * @return the image URL, or null if not set
		 */
		public String getImage() { return this.image; }

		/**
		 * Converts this item to RSS XML format.
		 *
		 * @return the XML representation of this item
		 */
		public String toString() { return RSSFeed.generateItem(this.title, this.text, this.image, new Date()); }
	}

	/** HTML-escaped line break for RSS content. */
	public static final String lineBreak = "&lt;br /&gt;";
	/** CDATA section start tag. */
	public static final String startHtml = "<![CDATA[";
	/** CDATA section end tag. */
	public static final String endHtml = "]]>";

	private String title, desc, link;
	private List items;

	private SimpleDateFormat format = new SimpleDateFormat("EEE, d MMM yyyy HH:mm:ss Z");

	/**
	 * Creates a new RSS feed with the specified title and description.
	 *
	 * @param title the feed title
	 * @param desc the feed description
	 */
	public RSSFeed(String title, String desc) {
		this.title = title;
		this.desc = desc;
		
		this.items = new ArrayList();
	}

	/**
	 * Sets the link URL for this feed.
	 *
	 * @param url the feed link URL
	 */
	public void setLink(String url) { this.link = url; }

	/**
	 * Returns the link URL for this feed.
	 *
	 * @return the link URL
	 */
	public String getLink() { return this.link; }

	/**
	 * Adds an item to this feed.
	 *
	 * @param i the item to add
	 */
	public synchronized void postItem(Item i) { this.items.add(i.toString()); }

	/**
	 * Writes the RSS feed to the specified output stream.
	 *
	 * @param p the print stream to write to
	 * @param ttl the time-to-live in minutes (how long clients should cache the feed)
	 */
	public synchronized void write(PrintStream p, int ttl) {
		p.println("<?xml version=\"1.0\" encoding=\"utf-8\"?><rss version=\"2.0\"><channel>");
		p.println("<title>" + this.title + "</title>");
		if (this.link != null) p.println("<link>" + this.link + "</link>");
		p.println("<description>" + this.desc + "</description>");
		p.println("<lastBuildDate>" + format.format(new Date()) + "</lastBuildDate>");
		p.println("<ttl>" + ttl + "</ttl>");
		p.println("<language>en-us</language>");
		
		Iterator itr = this.items.iterator();
		while (itr.hasNext()) p.println(itr.next());
		
		p.println("</channel></rss>");
	}

	/**
	 * Generates the XML for an RSS item.
	 *
	 * @param title the item title
	 * @param text the item description
	 * @param image the image URL, or null if no image
	 * @param d the publication date
	 * @return the XML string for the item
	 */
	public synchronized static String generateItem(String title, String text, String image, Date d) {
		StringBuffer b = new StringBuffer();
		
		b.append("<item><title>");
		b.append(title);
		b.append("</title><pubDate>");
		b.append(new SimpleDateFormat("EEE, d MMM yyyy HH:mm:ss Z").format(d));
		b.append("</pubDate><description>");
		b.append(text);
		
		if (image != null) {
			b.append(RSSFeed.lineBreak);
			b.append(RSSFeed.startHtml);
			b.append("<img src=\"");
			b.append(image);
			b.append("\" />");
			b.append(RSSFeed.endHtml);
		}
		
		b.append("</description></item>");
		
		return b.toString();
	}
}
