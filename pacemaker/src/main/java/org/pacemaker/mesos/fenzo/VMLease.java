package org.pacemaker.mesos.fenzo;

import com.netflix.fenzo.VirtualMachineLease;
import org.apache.mesos.Protos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;


public class VMLease implements VirtualMachineLease {
    private static final Logger logger = LoggerFactory.getLogger(VMLease.class);

    private final Protos.Offer offer;
    private final String hostname;
    private final String vmID;
    private final Map<String, Protos.Attribute> attributeMap;
    private final Map<String, Double> scalarResources;
    private final Map<String, List<Range>> rangeResources;
    private final long offeredTime;


    public VMLease(Collection<String> roles, String principal, Protos.Offer offer) {
        String rolesString = String.join(",");

        logger.debug("Filtering offer resources that does not match role and principal, for offerID={}, roles={} and principal={}", offer.getId().getValue(), rolesString, principal);
        this.offer = offer;
        hostname = offer.getHostname();
        this.vmID = offer.getSlaveId().getValue();
        offeredTime = System.currentTimeMillis();
        // parse out resources from offer
        // expects network bandwidth to come in as consumable scalar resource named "network"
        //TODO @etarascon This only supports Reservation Refinement mode http://mesos.apache.org/documentation/latest/reservation/
        Predicate<String> matchRoleOrWildcard = r -> roles.contains(r) || Objects.equals(r,"*");
        Predicate<Protos.Resource> matchesRoleAndPrincipal = r ->
                matchRoleOrWildcard.test(r.getRole()) ||
                        (r.hasAllocationInfo() && matchRoleOrWildcard.test(r.getAllocationInfo().getRole()) ||
                        (r.hasReservation() && r.getReservationsList().stream().anyMatch(x -> matchRoleOrWildcard.test(x.getRole()) && Objects.equals(x.getPrincipal(),principal))));

        scalarResources = offer.getResourcesList().stream()
                .filter(Protos.Resource::hasScalar)
                .peek(x -> logger.trace("Scalar resource before filter by roles={} and principal={}, offerID={}, resource {}", rolesString, principal, offer.getId(), x))
                .filter(matchesRoleAndPrincipal)
                .peek(x -> logger.trace("Adding scalar resource to lease after being filtered by roles={} and principal={}, offer {}, resource {}", rolesString, principal, offer.getId(), x))
                .collect(Collectors.groupingBy(Protos.Resource::getName,
                        Collectors.mapping(Protos.Resource::getScalar,Collectors.summingDouble(Protos.Value.Scalar::getValue))));
        rangeResources = offer.getResourcesList().stream()
                .filter(Protos.Resource::hasRanges)
                .peek(x -> logger.trace("Range resource before filter by roles={} and principal={}, offerID={}, resource {}", rolesString, principal, offer.getId(), x))
                .filter(matchesRoleAndPrincipal)
                .peek(x -> logger.trace("Adding range resource to lease after being filtered by roles={} and principal={}, offerID={}, resource {}", rolesString, principal, offer.getId(), x))
                .collect(Collectors.toMap(Protos.Resource::getName,
                        r -> r.getRanges().getRangeList().stream().map(x -> new Range((int)x.getBegin(),(int)x.getEnd())).collect(Collectors.toList()),
                        (a,b) -> Stream.concat(a.stream(), b.stream()).collect(Collectors.toList()))
                );

        attributeMap = offer.getAttributesList().stream()
                .collect(Collectors.toMap(
                        Protos.Attribute::getName,
                        Function.identity(),
                        (a,b) -> {
                            logger.warn("Duplicated attribute name, ignoring {}", b);
                           return a;
                        }));
    }
    @Override
    public String hostname() {
        return hostname;
    }
    @Override
    public String getVMID() {
        return vmID;
    }
    @Override
    public double cpuCores() {
        return scalarResources.get("cpus") == null? 0.0 : scalarResources.get("cpus");
    }
    @Override
    public double memoryMB() {
        return scalarResources.get("mem") == null? 0.0 : scalarResources.get("mem");
    }
    @Override
    public double networkMbps() {
        return scalarResources.get("network") == null? 0.0 : scalarResources.get("network");
    }
    @Override
    public double diskMB() {
        return scalarResources.get("disk") == null? 0.0 : scalarResources.get("disk");
    }

    public Protos.Offer getOffer(){
        return offer;
    }
    @Override
    public String getId() {
        return offer.getId().getValue();
    }
    @Override
    public long getOfferedTime() {
        return offeredTime;
    }

    @Override
    public List<Range> portRanges() {
        return rangeResources.get("ports") == null? Collections.emptyList() : rangeResources.get("ports");
    }
    @Override
    public Map<String, Protos.Attribute> getAttributeMap() {
        return attributeMap;
    }

    @Override
    public Double getScalarValue(String name) {
        return scalarResources.get(name);
    }

    @Override
    public Map<String, Double> getScalarValues() {
        return scalarResources;
    }


    @Override
    public String toString() {
        return "VMLeaseObject{" +
                "offer=" + offer +
                ", scalars: " + scalarResources.toString() +
                ", ranges: " + rangeResources.toString() +
                ", hostname='" + hostname + '\'' +
                ", vmID='" + vmID + '\'' +
                ", attributeMap=" + attributeMap +
                ", offeredTime=" + offeredTime +
                '}';
    }
}
