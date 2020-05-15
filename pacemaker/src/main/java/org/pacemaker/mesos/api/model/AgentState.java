package org.pacemaker.mesos.api.model;

import com.google.gson.annotations.SerializedName;

import java.util.Map;

public class AgentState {

    @SerializedName("reserved_resources_full")
    private Map<String,Resource[]> reservedResources;

    public Map<String, Resource[]> getReservedResources() {
        return reservedResources;
    }

    public class Resource{
        String name;
        Reservation[] reservations;
        Disk disk;

        public String getName() {
            return name;
        }

        public Reservation[] getReservations() {
            return reservations;
        }

        public Disk getDisk() {
            return disk;
        }
    }

    public class Reservation {
        String role;
        String principal;

        public String getRole() {
            return role;
        }

    }

    public class Disk {
        Persistence persistence;

        public Persistence getPersistence() {
            return persistence;
        }
    }

    public class Persistence {
        String id;
        String principal;

        public String getId() {
            return id;
        }

        public String getPrincipal() {
            return principal;
        }

    }
}
