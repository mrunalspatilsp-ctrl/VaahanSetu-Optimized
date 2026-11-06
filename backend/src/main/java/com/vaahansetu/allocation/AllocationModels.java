package com.vaahansetu.allocation;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;

/** Shared input/output models for the VaahanSetu allocation module. */
public final class AllocationModels {
    private AllocationModels() {}

    /** Complete bus details used by the bus list and allocation engine. */
    public record Bus(long id, String regNo, int capacity, String status,
                      String currentLocation, int restMinutes, LocalTime restStart,
                      LocalTime restEnd, String notes, OffsetDateTime createdAt,
                      boolean selectedForSession) {
        /** Convenience constructor for database-free algorithm tests and callers. */
        public Bus(long id, String regNo, int capacity, String status, int restMinutes) {
            this(id, regNo, capacity, status, null, restMinutes, null, null, null, null, true);
        }
        public boolean hasFixedRestWindow() { return restStart != null && restEnd != null && !restStart.equals(restEnd); }
    }

    /** stopIds are in route order (ordered by route_stops.stop_sequence). */
    public record Route(long id, String code, String name, List<Long> stopIds) {}

    /** A trip to (re)allocate. currentBusId is null when no ACTIVE assignment exists. */
    public record Trip(long id, long routeId, LocalDateTime departure, LocalDateTime arrival,
                       Long currentBusId, int currentBusCapacity) {}

    public record Journey(long tripId, long boardingStopId, long destinationStopId, int passengers) {}

    /** A trip assignment outside this run; the allocator must preserve it. */
    public record Commitment(long busId, long tripId, String routeCode,
                             LocalDateTime start, LocalDateTime end) {}

    public record Input(LocalDateTime time, List<Route> routes, List<Bus> buses, List<Trip> trips,
                        List<Journey> journeys, List<Commitment> commitments) {}

    /** All figures are passenger-segments summed across all segments/trips. */
    public record Metrics(int demand, int capacity, int accommodated, int unmet, int unused) {}

    public record SegmentRow(long routeId, long startStopId, long endStopId, int demand, int capacity) {
        public int accommodated() { return Math.min(demand, capacity); }
        public int unmet() { return Math.max(0, demand - capacity); }
        public int unused() { return Math.max(0, capacity - demand); }
    }

    public record TripOutcome(long tripId, long routeId, Long previousBusId, Long proposedBusId,
                              String type, int peakDemand, int proposedCapacity, int occupancyPercent,
                              int unmetDemand, String reason) {
        /** Convenience constructor for backward compatibility with 6-arg callers. */
        public TripOutcome(long tripId, long routeId, Long previousBusId, Long proposedBusId,
                           String type, String reason) {
            this(tripId, routeId, previousBusId, proposedBusId, type, 0, 0, 0, 0, reason);
        }
    }

    public record RouteReport(long routeId, String code, String name,
                              Metrics before, Metrics after, List<Long> assignedBusIds) {}

    public record BusReport(long busId, String regNo, int capacity, String originalRoutes,
                            String newRoutes, boolean availableAtTime, String unavailableReason) {}

    public record Result(LocalDateTime time, Metrics beforeTotal, Metrics afterTotal,
                         int busesConsidered, int busesAssigned, int busesLeftUnassigned,
                         List<RouteReport> routes, List<BusReport> buses, List<TripOutcome> outcomes,
                         List<SegmentRow> beforeSegments, List<SegmentRow> afterSegments) {}
}

