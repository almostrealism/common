package org.almostrealism.util;

/**
 * Interface for objects that can be serialized as key-value pairs for network transmission.
 *
 * <p>This interface enables objects to be encoded into a string format suitable for
 * transmission between nodes in a distributed system, then reconstructed on the
 * receiving end using the key-value pairs.</p>
 *
 * <h2>Encoding Format</h2>
 * <p>The encoded string follows the format:</p>
 * <pre>classname:key0=value0:key1=value1:key2=value2...</pre>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * public class Configuration implements KeyValueStore {
 *     private String host;
 *     private int port;
 *
 *     public String encode() {
 *         return "Configuration:host=" + host + ":port=" + port;
 *     }
 *
 *     public void set(String key, String value) {
 *         switch (key) {
 *             case "host": this.host = value; break;
 *             case "port": this.port = Integer.parseInt(value); break;
 *         }
 *     }
 * }
 * }</pre>
 *
 * @author Michael Murray
 */
public interface KeyValueStore {

    /**
     * Encodes this object as a string of key-value pairs.
     *
     * <p>The returned string should follow the format:</p>
     * <pre>classname:key0=value0:key1=value1:key2=value2...</pre>
     *
     * <p>Where classname is the name of the implementing class, and the
     * key=value pairs contain all state necessary to reconstruct the object.</p>
     *
     * @return a string representation of this KeyValueStore
     */
    public String encode();

    /**
     * Sets a property of this KeyValueStore object from a key-value pair.
     *
     * <p>This method is called during deserialization to initialize the object's
     * state based on the string returned by {@link #encode()}.</p>
     *
     * @param key   the property name
     * @param value the property value as a string
     */
    public void set(String key, String value);
}
