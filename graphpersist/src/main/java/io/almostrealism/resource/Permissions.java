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

public class Permissions {
    public enum Setting {
        EMPTY, EXECUTE, WRITE, WRITE_EXECUTE, READ, READ_EXECUTE, READ_WRITE, READ_WRITE_EXECUTE
	}

    private String owner;
    private Setting user, group, others;

    public Permissions() {
        this("root");
    }

    public Permissions(String owner) {
        this(owner, Setting.READ_WRITE_EXECUTE, Setting.READ);
    }

    public Permissions(String owner, Setting user, Setting group) {
        this(owner, user, group, Setting.EMPTY);
    }

    public Permissions(String owner, Setting user, Setting group, Setting others) {
        this.owner = owner;
        this.user = user;
        this.group = group;
        this.others = others;
    }

    public String getOwner() { return owner; }
    public Setting getUserSetting() { return user; }
    public Setting getGroupSetting() { return group; }
    public Setting getOthersSetting() { return others; }

    public void update(Permissions p) {
        this.owner = p.getOwner();
        this.user = p.getUserSetting();
        this.group = p.getGroupSetting();
        this.others = p.getOthersSetting();
    }
}
