/*
 * Copyright 2018 Michael Murray
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

package io.almostrealism.resource;

/**
 * Represents Unix-style read/write/execute permissions for a resource, covering the
 * owning user, group, and others.
 *
 * <p>Default construction (no args) grants the {@code root} user full access,
 * group read access, and no access for others.</p>
 */
public class Permissions {
    /**
     * Enumeration of the possible permission combinations for a single principal category
     * (user, group, or others), corresponding to Unix octal permission bits 0–7.
     */
    public enum Setting {
        /** No permissions (---). */
        EMPTY,
        /** Execute only (--x). */
        EXECUTE,
        /** Write only (-w-). */
        WRITE,
        /** Write and execute (-wx). */
        WRITE_EXECUTE,
        /** Read only (r--). */
        READ,
        /** Read and execute (r-x). */
        READ_EXECUTE,
        /** Read and write (rw-). */
        READ_WRITE,
        /** Read, write, and execute (rwx). */
        READ_WRITE_EXECUTE
	}

    /** The name of the user who owns this resource. */
    private String owner;
    /** The permission setting for the owning user. */
    private Setting user;
    /** The permission setting for the owning group. */
    private Setting group;
    /** The permission setting for all other users. */
    private Setting others;

    /**
     * Constructs default permissions for the {@code root} user:
     * full access for the user, read access for the group, and no access for others.
     */
    public Permissions() {
        this("root");
    }

    /**
     * Constructs permissions for the given owner with full user access, group read access,
     * and no access for others.
     *
     * @param owner The owner's user name
     */
    public Permissions(String owner) {
        this(owner, Setting.READ_WRITE_EXECUTE, Setting.READ);
    }

    /**
     * Constructs permissions with the specified user and group settings, and no access for others.
     *
     * @param owner The owner's user name
     * @param user  The permission setting for the owner
     * @param group The permission setting for the group
     */
    public Permissions(String owner, Setting user, Setting group) {
        this(owner, user, group, Setting.EMPTY);
    }

    /**
     * Constructs permissions with fully specified user, group, and others settings.
     *
     * @param owner  The owner's user name
     * @param user   The permission setting for the owner
     * @param group  The permission setting for the group
     * @param others The permission setting for all other users
     */
    public Permissions(String owner, Setting user, Setting group, Setting others) {
        this.owner = owner;
        this.user = user;
        this.group = group;
        this.others = others;
    }

    /**
     * Returns the name of the resource owner.
     *
     * @return The owner's user name
     */
    public String getOwner() { return owner; }

    /**
     * Returns the permission setting for the owning user.
     *
     * @return The user's permission setting
     */
    public Setting getUserSetting() { return user; }

    /**
     * Returns the permission setting for the owning group.
     *
     * @return The group's permission setting
     */
    public Setting getGroupSetting() { return group; }

    /**
     * Returns the permission setting for all other users.
     *
     * @return The others' permission setting
     */
    public Setting getOthersSetting() { return others; }

    /**
     * Replaces all fields of this {@link Permissions} instance with the values from
     * the given permissions object.
     *
     * @param p The permissions to copy from
     */
    public void update(Permissions p) {
        this.owner = p.getOwner();
        this.user = p.getUserSetting();
        this.group = p.getGroupSetting();
        this.others = p.getOthersSetting();
    }
}
