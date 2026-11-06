package com.vaahansetu;

import static org.junit.jupiter.api.Assertions.*;

import com.vaahansetu.allocation.AllocationEngine;
import com.vaahansetu.allocation.AllocationModels.*;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AllocationEngineTest {

    private final LocalDateTime base = LocalDateTime.of(2026, 4, 1, 10, 0);

    // -------------------------------------------------------------
    // Scenario 1: Demand lower than available bus capacity
    // -------------------------------------------------------------
    @Test
    @DisplayName("Scenario 1: Demand lower than available bus capacity -> 0 unmet demand, correct occupancy")
    void testDemandLowerThanBusCapacity() {
        Route r1 = new Route(1L, "R1", "Route 1", List.of(101L, 102L));
        Trip t1 = new Trip(10L, 1L, base.plusHours(1), base.plusHours(2), null, 0);
        Journey j1 = new Journey(10L, 101L, 102L, 25);
        Bus b1 = new Bus(1L, "TS-01-30", 50, "ACTIVE", 30);

        Input in = new Input(base, List.of(r1), List.of(b1), List.of(t1), List.of(j1), List.of());
        Result res = AllocationEngine.allocate(in);

        assertNotNull(res);
        assertEquals(0, res.afterTotal().unmet(), "All 25 passengers accommodated");
        TripOutcome o = res.outcomes().get(0);
        assertEquals(1L, o.proposedBusId());
        assertEquals(25, o.peakDemand());
        assertEquals(50, o.proposedCapacity());
        assertEquals(50, o.occupancyPercent(), "25 / 50 = 50% occupancy");
    }

    // -------------------------------------------------------------
    // Scenario 2: Demand greater than individual bus capacity
    // -------------------------------------------------------------
    @Test
    @DisplayName("Scenario 2: Demand greater than individual bus capacity -> unmet demand recorded correctly")
    void testDemandGreaterThanBusCapacity() {
        Route r1 = new Route(1L, "R1", "Route 1", List.of(101L, 102L));
        Trip t1 = new Trip(10L, 1L, base.plusHours(1), base.plusHours(2), null, 0);
        Journey j1 = new Journey(10L, 101L, 102L, 75);
        Bus b1 = new Bus(1L, "TS-01-50", 50, "ACTIVE", 30);

        Input in = new Input(base, List.of(r1), List.of(b1), List.of(t1), List.of(j1), List.of());
        Result res = AllocationEngine.allocate(in);

        assertEquals(25, res.afterTotal().unmet(), "75 - 50 = 25 unmet passenger demand");
        TripOutcome o = res.outcomes().get(0);
        assertEquals(1L, o.proposedBusId());
        assertEquals(75, o.peakDemand());
        assertEquals(100, o.occupancyPercent(), "Bus is at 100% seating capacity");
        assertTrue(o.reason().contains("75 passengers") || o.reason().contains("unmet"), "Explanation documents high demand");
    }

    // -------------------------------------------------------------
    // Scenario 3: Multiple buses available for multiple trips
    // -------------------------------------------------------------
    @Test
    @DisplayName("Scenario 3: Multiple buses available for multiple trips -> right-sized assignments")
    void testMultipleBusesForMultipleTrips() {
        Route r1 = new Route(1L, "R1", "Route 1", List.of(101L, 102L));
        Trip t1 = new Trip(10L, 1L, base.plusHours(1), base.plusHours(2), null, 0);
        Trip t2 = new Trip(20L, 1L, base.plusHours(1), base.plusHours(2), null, 0);

        Journey j1 = new Journey(10L, 101L, 102L, 25);
        Journey j2 = new Journey(20L, 101L, 102L, 55);

        Bus bSmall = new Bus(1L, "TS-SMALL", 30, "ACTIVE", 30);
        Bus bLarge = new Bus(2L, "TS-LARGE", 60, "ACTIVE", 30);

        Input in = new Input(base, List.of(r1), List.of(bSmall, bLarge), List.of(t1, t2), List.of(j1, j2), List.of());
        Result res = AllocationEngine.allocate(in);

        assertEquals(0, res.afterTotal().unmet(), "Both trips fully accommodated");
        TripOutcome o1 = res.outcomes().stream().filter(o -> o.tripId() == 10L).findFirst().orElseThrow();
        TripOutcome o2 = res.outcomes().stream().filter(o -> o.tripId() == 20L).findFirst().orElseThrow();

        assertEquals(1L, o1.proposedBusId(), "Trip 10 (25 pax) gets 30-seater bus");
        assertEquals(2L, o2.proposedBusId(), "Trip 20 (55 pax) gets 60-seater bus");
    }

    // -------------------------------------------------------------
    // Scenario 4: Unmet demand with additional eligible buses available
    // -------------------------------------------------------------
    @Test
    @DisplayName("Scenario 4: Unmet demand with additional eligible buses -> idle bus assigned to eliminate unmet demand")
    void testUnmetDemandWithAdditionalBusesAvailable() {
        Route r1 = new Route(1L, "R1", "Route 1", List.of(101L, 102L));
        // Baseline has small bus (30) on high demand trip (60 pax) -> 30 unmet
        Trip t1 = new Trip(10L, 1L, base.plusHours(1), base.plusHours(2), 1L, 30);
        Journey j1 = new Journey(10L, 101L, 102L, 60);

        Bus b1 = new Bus(1L, "BUS-30", 30, "ACTIVE", 30);
        Bus b2Available = new Bus(2L, "BUS-60-IDLE", 60, "ACTIVE", 30); // Idle and eligible

        Input in = new Input(base, List.of(r1), List.of(b1, b2Available), List.of(t1), List.of(j1), List.of());
        Result res = AllocationEngine.allocate(in);

        assertEquals(0, res.afterTotal().unmet(), "Idle 60-seat bus should be assigned to eliminate unmet demand");
        assertEquals(2L, res.outcomes().get(0).proposedBusId(), "Proposed bus is the 60-seater");
    }

    // -------------------------------------------------------------
    // Scenario 5: No feasible additional bus assignment
    // -------------------------------------------------------------
    @Test
    @DisplayName("Scenario 5: No feasible additional bus assignment -> trip marked UNASSIGNED with clear explanation")
    void testNoFeasibleAdditionalBusAssignment() {
        Route r1 = new Route(1L, "R1", "Route 1", List.of(101L, 102L));
        Trip t1 = new Trip(10L, 1L, base.plusHours(1), base.plusHours(2), null, 0);
        Trip t2 = new Trip(20L, 1L, base.plusHours(1), base.plusHours(2), null, 0);

        Journey j1 = new Journey(10L, 101L, 102L, 30);
        Journey j2 = new Journey(20L, 101L, 102L, 30);

        // Only one active bus for two concurrent trips
        Bus b1 = new Bus(1L, "BUS-SOLO", 50, "ACTIVE", 30);

        Input in = new Input(base, List.of(r1), List.of(b1), List.of(t1, t2), List.of(j1, j2), List.of());
        Result res = AllocationEngine.allocate(in);

        assertEquals(1, res.busesAssigned());
        long unassignedCount = res.outcomes().stream().filter(o -> "UNASSIGNED".equals(o.type())).count();
        assertEquals(1, unassignedCount, "Second concurrent trip must be UNASSIGNED when no feasible bus exists");
        TripOutcome unassigned = res.outcomes().stream().filter(o -> "UNASSIGNED".equals(o.type())).findFirst().orElseThrow();
        assertTrue(unassigned.reason().contains("No eligible active bus available") || unassigned.reason().contains("turnaround"),
                "Explanation should explain operational constraint");
    }

    // -------------------------------------------------------------
    // Scenario 6: Bus turnaround less than 30 minutes
    // -------------------------------------------------------------
    @Test
    @DisplayName("Scenario 6: Bus turnaround less than 30 minutes -> rejected as infeasible")
    void testTurnaroundLessThan30MinutesRejected() {
        Route r1 = new Route(1L, "R1", "Route 1", List.of(101L, 102L));
        // Trip 1 arrives at 11:00
        Trip t1 = new Trip(1L, 1L, base, base.plusHours(1), null, 0);
        // Trip 2 departs at 11:20 (only 20 min turnaround, < 30 min required)
        Trip t2 = new Trip(2L, 1L, base.plusHours(1).plusMinutes(20), base.plusHours(2), null, 0);

        Journey j1 = new Journey(1L, 101L, 102L, 20);
        Journey j2 = new Journey(2L, 101L, 102L, 20);

        Bus b1 = new Bus(1L, "BUS-1", 50, "ACTIVE", 20); // Note: bus restMinutes is 20, but engine enforces MIN 30

        Input in = new Input(base, List.of(r1), List.of(b1), List.of(t1, t2), List.of(j1, j2), List.of());
        Result res = AllocationEngine.allocate(in);

        // Cannot assign both trips to b1
        Set<Long> busesUsed = new HashSet<>();
        for (TripOutcome o : res.outcomes()) {
            if (o.proposedBusId() != null) busesUsed.add(o.proposedBusId());
        }
        long assignedTrips = res.outcomes().stream().filter(o -> o.proposedBusId() != null).count();
        assertEquals(1, assignedTrips, "Turnaround of 20 min violates mandatory 30-min buffer; only 1 trip can be assigned");
    }

    // -------------------------------------------------------------
    // Scenario 7: Bus turnaround exactly 30 minutes
    // -------------------------------------------------------------
    @Test
    @DisplayName("Scenario 7: Bus turnaround exactly 30 minutes -> accepted as feasible")
    void testTurnaroundExactly30MinutesAccepted() {
        Route r1 = new Route(1L, "R1", "Route 1", List.of(101L, 102L));
        // Trip 1 arrives at 11:00
        Trip t1 = new Trip(1L, 1L, base, base.plusHours(1), null, 0);
        // Trip 2 departs at 11:30 (exactly 30 min turnaround)
        Trip t2 = new Trip(2L, 1L, base.plusHours(1).plusMinutes(30), base.plusHours(2).plusMinutes(30), null, 0);

        Journey j1 = new Journey(1L, 101L, 102L, 20);
        Journey j2 = new Journey(2L, 101L, 102L, 20);

        Bus b1 = new Bus(1L, "BUS-1", 50, "ACTIVE", 30);

        Input in = new Input(base, List.of(r1), List.of(b1), List.of(t1, t2), List.of(j1, j2), List.of());
        Result res = AllocationEngine.allocate(in);

        long assignedTrips = res.outcomes().stream().filter(o -> o.proposedBusId() != null).count();
        assertEquals(2, assignedTrips, "Exactly 30 min turnaround is fully compliant and both trips are assigned");
        assertEquals(1L, res.outcomes().get(0).proposedBusId());
        assertEquals(1L, res.outcomes().get(1).proposedBusId());
    }

    // -------------------------------------------------------------
    // Scenario 8: Overlapping trips assigned to the same bus
    // -------------------------------------------------------------
    @Test
    @DisplayName("Scenario 8: Overlapping trips assigned to the same bus -> prevented")
    void testOverlappingTripsPrevented() {
        Route r1 = new Route(1L, "R1", "Route 1", List.of(101L, 102L));
        // Trip 1: 10:00 - 11:00
        Trip t1 = new Trip(1L, 1L, base, base.plusHours(1), null, 0);
        // Trip 2: 10:30 - 11:30 (overlaps with Trip 1)
        Trip t2 = new Trip(2L, 1L, base.plusMinutes(30), base.plusHours(1).plusMinutes(30), null, 0);

        Journey j1 = new Journey(1L, 101L, 102L, 20);
        Journey j2 = new Journey(2L, 101L, 102L, 20);

        Bus b1 = new Bus(1L, "BUS-1", 50, "ACTIVE", 30);

        Input in = new Input(base, List.of(r1), List.of(b1), List.of(t1, t2), List.of(j1, j2), List.of());
        Result res = AllocationEngine.allocate(in);

        long assigned = res.outcomes().stream().filter(o -> o.proposedBusId() != null).count();
        assertEquals(1, assigned, "Two overlapping trips cannot share the same bus");
    }

    // -------------------------------------------------------------
    // Scenario 9: Driver mandatory rest not completed
    // -------------------------------------------------------------
    @Test
    @DisplayName("Scenario 9: Driver mandatory rest window in progress -> bus not assigned during rest window")
    void testDriverMandatoryRestWindowEnforced() {
        Route r1 = new Route(1L, "R1", "Route 1", List.of(101L, 102L));
        // Trip 10:15 - 11:00
        Trip t1 = new Trip(10L, 1L, base.plusMinutes(15), base.plusHours(1), null, 0);
        Journey j1 = new Journey(10L, 101L, 102L, 20);

        // Bus has fixed rest window from 10:00 to 11:00
        Bus restingBus = new Bus(1L, "REST-BUS", 50, "ACTIVE", null, 30,
                LocalTime.of(10, 0), LocalTime.of(11, 0), "Rest", null, true);

        Input in = new Input(base, List.of(r1), List.of(restingBus), List.of(t1), List.of(j1), List.of());
        Result res = AllocationEngine.allocate(in);

        assertNull(res.outcomes().get(0).proposedBusId(), "Resting bus cannot be assigned during its mandatory rest window");
        assertEquals("UNASSIGNED", res.outcomes().get(0).type());
    }

    // -------------------------------------------------------------
    // Scenario 10: Multiple trips sharing the same bus
    // -------------------------------------------------------------
    @Test
    @DisplayName("Scenario 10: Multiple trips sharing the same bus sequentially with turnaround -> valid chain")
    void testMultipleTripsSharingSameBusSequentially() {
        Route r1 = new Route(1L, "R1", "Route 1", List.of(101L, 102L));
        // Trip 1: 08:00 - 09:00
        Trip t1 = new Trip(1L, 1L, base.minusHours(2), base.minusHours(1), null, 0);
        // Trip 2: 10:00 - 11:00 (1 hour buffer)
        Trip t2 = new Trip(2L, 1L, base, base.plusHours(1), null, 0);
        // Trip 3: 12:00 - 13:00 (1 hour buffer)
        Trip t3 = new Trip(3L, 1L, base.plusHours(2), base.plusHours(3), null, 0);

        Journey j1 = new Journey(1L, 101L, 102L, 15);
        Journey j2 = new Journey(2L, 101L, 102L, 20);
        Journey j3 = new Journey(3L, 101L, 102L, 25);

        Bus b1 = new Bus(1L, "BUS-CHAIN", 40, "ACTIVE", 30);

        Input in = new Input(base, List.of(r1), List.of(b1), List.of(t1, t2, t3), List.of(j1, j2, j3), List.of());
        Result res = AllocationEngine.allocate(in);

        assertEquals(3, res.outcomes().stream().filter(o -> o.proposedBusId() != null && o.proposedBusId() == 1L).count(),
                "All 3 sequential trips should be served by the same bus");
        assertEquals(0, res.afterTotal().unmet());
    }

    // -------------------------------------------------------------
    // Scenario 11: Missing or null passenger demand
    // -------------------------------------------------------------
    @Test
    @DisplayName("Scenario 11: Missing or zero passenger demand -> handled gracefully with 0 unmet and 0% occupancy")
    void testMissingOrNullDemandHandledGracefully() {
        Route r1 = new Route(1L, "R1", "Route 1", List.of(101L, 102L));
        Trip t1 = new Trip(10L, 1L, base.plusHours(1), base.plusHours(2), null, 0);

        Bus b1 = new Bus(1L, "BUS-EMPTY", 50, "ACTIVE", 30);

        // Zero journeys in input
        Input in = new Input(base, List.of(r1), List.of(b1), List.of(t1), List.of(), List.of());
        Result res = AllocationEngine.allocate(in);

        assertNotNull(res);
        assertEquals(0, res.afterTotal().demand());
        assertEquals(0, res.afterTotal().unmet());
        TripOutcome o = res.outcomes().get(0);
        assertEquals(0, o.peakDemand());
        assertEquals(0, o.occupancyPercent());
    }

    // -------------------------------------------------------------
    // Scenario 12: Correct baseline versus proposed unmet demand
    // -------------------------------------------------------------
    @Test
    @DisplayName("Scenario 12: Correct baseline vs proposed unmet demand -> proposed is NEVER worse than baseline")
    void testProposedUnmetDemandNeverExceedsBaseline() {
        Route r1 = new Route(1L, "R1", "Route 1", List.of(101L, 102L));
        // Trip 1 has 40 demand, currently assigned 30-seat bus (10 unmet)
        Trip t1 = new Trip(1L, 1L, base.plusHours(1), base.plusHours(2), 1L, 30);
        Journey j1 = new Journey(1L, 101L, 102L, 40);

        Bus b1 = new Bus(1L, "BUS-30", 30, "ACTIVE", 30);

        Input in = new Input(base, List.of(r1), List.of(b1), List.of(t1), List.of(j1), List.of());
        Result res = AllocationEngine.allocate(in);

        int baselineUnmet = res.beforeTotal().unmet();
        int proposedUnmet = res.afterTotal().unmet();

        assertTrue(proposedUnmet <= baselineUnmet,
                "Proposed unmet demand (" + proposedUnmet + ") must never exceed baseline (" + baselineUnmet + ")");
        assertEquals(10, baselineUnmet);
        assertEquals(10, proposedUnmet);
    }

    // -------------------------------------------------------------
    // Scenario 13: Correct occupancy calculation
    // -------------------------------------------------------------
    @Test
    @DisplayName("Scenario 13: Correct occupancy percentage formula: (passengers / capacity) * 100")
    void testOccupancyCalculationFormula() {
        Route r1 = new Route(1L, "R1", "Route 1", List.of(101L, 102L));
        Trip t1 = new Trip(1L, 1L, base.plusHours(1), base.plusHours(2), null, 0);
        // 30 passengers on 60 capacity bus -> exactly 50%
        Journey j1 = new Journey(1L, 101L, 102L, 30);

        Bus b1 = new Bus(1L, "BUS-60", 60, "ACTIVE", 30);

        Input in = new Input(base, List.of(r1), List.of(b1), List.of(t1), List.of(j1), List.of());
        Result res = AllocationEngine.allocate(in);

        TripOutcome o = res.outcomes().get(0);
        assertEquals(30, o.peakDemand());
        assertEquals(60, o.proposedCapacity());
        assertEquals(50, o.occupancyPercent(), "Occupancy must be exactly 50% for 30 passengers in a 60-seat bus");
    }

    // -------------------------------------------------------------
    // Scenario 14: Database demand retrieval / segment demand check
    // -------------------------------------------------------------
    @Test
    @DisplayName("Scenario 14: Segment demand calculated across boarding and destination stops")
    void testSegmentDemandMultiStopRoute() {
        Route r1 = new Route(1L, "R1", "3-Stop Route", List.of(10L, 20L, 30L));
        Trip t1 = new Trip(100L, 1L, base.plusHours(1), base.plusHours(2), null, 0);

        // Journey 1: stop 10 -> 20 (10 pax)
        // Journey 2: stop 20 -> 30 (15 pax)
        // Journey 3: stop 10 -> 30 (5 pax)
        Journey j1 = new Journey(100L, 10L, 20L, 10);
        Journey j2 = new Journey(100L, 20L, 30L, 15);
        Journey j3 = new Journey(100L, 10L, 30L, 5);

        Bus b1 = new Bus(1L, "BUS-50", 50, "ACTIVE", 30);

        Input in = new Input(base, List.of(r1), List.of(b1), List.of(t1), List.of(j1, j2, j3), List.of());
        Result res = AllocationEngine.allocate(in);

        assertEquals(2, res.afterSegments().size(), "3 stops produce 2 segments");
        // Segment 10->20: j1 (10) + j3 (5) = 15 pax
        assertEquals(15, res.afterSegments().get(0).demand());
        // Segment 20->30: j2 (15) + j3 (5) = 20 pax
        assertEquals(20, res.afterSegments().get(1).demand());
        // Peak load is 20 pax
        TripOutcome o = res.outcomes().get(0);
        assertEquals(20, o.peakDemand());
    }

    // -------------------------------------------------------------
    // Scenario 15: API to frontend field mapping consistency
    // -------------------------------------------------------------
    @Test
    @DisplayName("Scenario 15: TripOutcome has non-zero demand, capacity, and matching explanation")
    void testApiToFrontendFieldMappingConsistency() {
        Route r1 = new Route(1L, "R1", "Route 1", List.of(1L, 2L));
        Trip t1 = new Trip(10L, 1L, base.plusHours(1), base.plusHours(2), 1L, 25);
        Journey j1 = new Journey(10L, 1L, 2L, 40);

        Bus b1 = new Bus(1L, "MH12-A", 25, "ACTIVE", 30);
        Bus b2 = new Bus(2L, "MH12-B", 50, "ACTIVE", 30);

        Input in = new Input(base, List.of(r1), List.of(b1, b2), List.of(t1), List.of(j1), List.of());
        Result res = AllocationEngine.allocate(in);

        TripOutcome o = res.outcomes().get(0);
        assertEquals(40, o.peakDemand());
        assertEquals(50, o.proposedCapacity());
        assertEquals(80, o.occupancyPercent(), "40 / 50 = 80%");
        assertTrue(o.reason().contains("40"), "Explanation must contain the exact peak demand");
        assertTrue(o.reason().contains("80%"), "Explanation must contain the exact occupancy percent");
    }

    // -------------------------------------------------------------
    // Scenario 16: Fleet status reconciliation
    // -------------------------------------------------------------
    @Test
    @DisplayName("Scenario 16: Fleet status reconciliation (Total = Eligible + Ineligible; Eligible = Assigned + Unassigned)")
    void testFleetStatusReconciliation() {
        Route r1 = new Route(1L, "R1", "Route 1", List.of(1L, 2L));
        Trip t1 = new Trip(10L, 1L, base.plusHours(1), base.plusHours(2), null, 0);
        Journey j1 = new Journey(10L, 1L, 2L, 20);

        // 3 buses: 2 ACTIVE (1 assigned, 1 unassigned), 1 MAINTENANCE
        Bus bActive1 = new Bus(1L, "BUS-ACT-1", 50, "ACTIVE", 30);
        Bus bActive2 = new Bus(2L, "BUS-ACT-2", 50, "ACTIVE", 30);
        Bus bMaint = new Bus(3L, "BUS-MAINT", 50, "MAINTENANCE", 30);

        Input in = new Input(base, List.of(r1), List.of(bActive1, bActive2, bMaint), List.of(t1), List.of(j1), List.of());
        Result res = AllocationEngine.allocate(in);

        int totalFleet = in.buses().size(); // 3
        int eligible = res.busesConsidered(); // 2
        int assigned = res.busesAssigned(); // 1
        int unassigned = res.busesLeftUnassigned(); // 1
        int ineligible = totalFleet - eligible; // 1

        assertEquals(3, totalFleet);
        assertEquals(2, eligible);
        assertEquals(1, assigned);
        assertEquals(1, unassigned);
        assertEquals(1, ineligible);
        assertEquals(eligible, assigned + unassigned, "Eligible must equal assigned + unassigned");
        assertEquals(totalFleet, eligible + ineligible, "Total fleet must equal eligible + ineligible");
    }
}
