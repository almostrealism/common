package org.almostrealism.util;

public interface KeyValueStore {
    /**
     * This method should return a string of the form:
     * "classname:key0=value0:key1=value1:key2=value2..."
     * Where classname is the name of the class that is implementing KeyValueStore, and the
     * key=value pairs are pairs of keys and values that will be passed to the set method
     * of the class to initialize the state of the object after it has been transmitted
     * from one node to another.
     * 
     * @return  A String representation of this KeyValueStore.
     */
    public String encode();
    
    /**
     * Sets a property of this KeyValueStore object. Any KeyValueStore object that is to be
     * transmitted over a network will have this method called when it arrives at
     * a new host to initialize its variables based on the string returned by the encode
     * method.
     * 
     * @param key  Property name.
     * @param value  Property value.
     */
    public void set(String key, String value);
}
