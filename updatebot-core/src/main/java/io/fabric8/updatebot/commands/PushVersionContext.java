/*
 * Copyright 2016 Red Hat, Inc.
 *
 * Red Hat licenses this file to you under the Apache License, version
 * 2.0 (the "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied.  See the License for the specific language governing
 * permissions and limitations under the License.
 */
package io.fabric8.updatebot.commands;

import io.fabric8.updatebot.kind.Kind;
import io.fabric8.updatebot.model.PushVersionDetails;
import io.fabric8.utils.Objects;

import java.util.ArrayList;
import java.util.List;

/**
 */
public class PushVersionContext extends CommandContext {
    private final PushVersionDetails step;
    private List<Change> changes = new ArrayList<>();

    public PushVersionContext(CommandContext parentContext, PushVersionDetails step) {
        super(parentContext);
        this.step = step;
    }

    @Override
    public String toString() {
        return "PushVersionContext{" +
                "kind='" + getKind() + '\'' +
                ", name='" + getName() + '\'' +
                ", value='" + getValue() + '\'' +
                '}';
    }

    @Override
    public String createTitle() {
        return "fix(version): update " + step.getProperty() + " to " + step.getValue();
    }

    @Override
    public String createTitlePrefix() {
        return "fix(version): update " + step.getProperty() + " to ";
    }

    public Kind getKind() {
        return step.getKind();
    }

    public String getName() {
        return step.getProperty();
    }

    public String getValue() {
        return step.getValue();
    }

    public List<Change> getChanges() {
        return changes;
    }

    public void updatedVersion(String dependencyKey, String name, String newValue, String oldValue) {
        changes.add(new Change(dependencyKey, name, newValue, oldValue));
    }

    /**
     * Returns the change for the given name or null if there is none
     */
    public Change change(String name) {
        for (Change change : changes) {
            if (Objects.equal(name, change.getName())) {
                return change;
            }
        }
        return null;
    }

    public static class Change {
        private final String dependencyKey;
        private final String name;
        private final String newValue;
        private final String oldValue;

        public Change(String dependencyKey, String name, String newValue, String oldValue) {
            this.dependencyKey = dependencyKey;
            this.name = name;
            this.newValue = newValue;
            this.oldValue = oldValue;
        }

        @Override
        public String toString() {
            return "Change{" +
                    "dependencyKey='" + dependencyKey + '\'' +
                    ", name='" + name + '\'' +
                    ", newValue='" + newValue + '\'' +
                    ", oldValue='" + oldValue + '\'' +
                    '}';
        }

        public String getDependencyKey() {
            return dependencyKey;
        }

        public String getName() {
            return name;
        }

        public String getNewValue() {
            return newValue;
        }

        public String getOldValue() {
            return oldValue;
        }
    }
}