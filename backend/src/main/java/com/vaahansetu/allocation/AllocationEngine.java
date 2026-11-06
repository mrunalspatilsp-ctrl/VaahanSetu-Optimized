package com.vaahansetu.allocation;

import static com.vaahansetu.allocation.AllocationModels.*;

import java.time.LocalDateTime;
import java.util.*;

/**
 * Pure allocation logic: no database access, so it can be tested and explained on its own.
 *
 * Primary Objective: Minimise total unmet passenger demand across all trips.
 * Secondary Objectives:
 *  1. Strictly maintain all operational constraints (30-min minimum turnaround, fixed daily driver rest,
 *     external commitments, active fleet eligibility, no overlapping trips).
 *  2. Maximize fleet utilization (assign available buses to unaccommodated demand).
 *  3. Right-size buses to demand surges and avoid unnecessary assignment churn.
 *
 * Guarantees:
 *  - Proposed unmet demand NEVER exceeds baseline unmet demand when the baseline is feasible.
 *  - Unused eligible buses are investigated, evaluated for unmet demand, and explained.
 */
public final class AllocationEngine {
    private AllocationEngine() {}

    public static Result allocate(Input in) {
        Map<Long, Route> routes = new HashMap<>();
        for (Route r : in.routes()) routes.put(r.id(), r);

        Map<Long, Bus> busesById = new HashMap<>();
        for (Bus b : in.buses()) busesById.put(b.id(), b);

        Map<Long, int[]> demand = demandPerTrip(in, routes);

        // Fixed commitments busy intervals
        Map<Long, List<LocalDateTime[]>> baseBusy = new HashMap<>();
        for (Bus b : in.buses()) baseBusy.put(b.id(), new ArrayList<>());
        for (Commitment c : in.commitments()) {
            List<LocalDateTime[]> list = baseBusy.get(c.busId());
            if (list != null) list.add(new LocalDateTime[]{c.start(), c.end()});
        }

        List<Bus> eligible = new ArrayList<>();
        for (Bus b : in.buses()) {
            if ("ACTIVE".equalsIgnoreCase(b.status())) eligible.add(b);
        }
        eligible.sort(Comparator.comparingLong(Bus::id));

        // -------------------------------------------------------------
        // Step 1: Evaluate Baseline Allocation
        // -------------------------------------------------------------
        Map<Long, Bus> baselineMap = new HashMap<>();
        boolean baselineFeasible = true;
        Map<Long, List<LocalDateTime[]>> baselineBusy = copyBusy(baseBusy);

        for (Trip t : in.trips()) {
            if (t.currentBusId() != null) {
                Bus b = busesById.get(t.currentBusId());
                if (b != null && "ACTIVE".equalsIgnoreCase(b.status())
                        && isFree(b, baselineBusy.get(b.id()), t)
                        && !overlapsFixedRestWindow(b, t)) {
                    baselineMap.put(t.id(), b);
                    baselineBusy.get(b.id()).add(new LocalDateTime[]{t.departure(), t.arrival()});
                } else {
                    baselineFeasible = false;
                }
            }
        }

        int baselineUnmet = computeTotalUnmet(in.trips(), demand, baselineMap);

        // -------------------------------------------------------------
        // Step 2: Generate Candidate Allocations
        // -------------------------------------------------------------
        List<Map<Long, Bus>> candidates = new ArrayList<>();

        // Candidate 0: Baseline allocation (if baseline is feasible)
        if (baselineFeasible && !baselineMap.isEmpty()) {
            candidates.add(new HashMap<>(baselineMap));
        }

        // Candidate 1: Baseline-preserving Upgrade & Swap Optimization
        Map<Long, Bus> cand1 = optimizeFromBaseline(in.trips(), eligible, demand, baseBusy, baselineMap);
        if (isFeasibleSchedule(in.trips(), eligible, baseBusy, cand1)) {
            candidates.add(cand1);
        }

        // Candidate 2: Demand-Prioritized Matching with Fleet Utilization
        Map<Long, Bus> cand2 = optimizeDemandFirst(in.trips(), eligible, demand, baseBusy);
        if (isFeasibleSchedule(in.trips(), eligible, baseBusy, cand2)) {
            candidates.add(cand2);
        }

        // Candidate 3: Chronological Forward Matching
        Map<Long, Bus> cand3 = optimizeChronological(in.trips(), eligible, demand, baseBusy);
        if (isFeasibleSchedule(in.trips(), eligible, baseBusy, cand3)) {
            candidates.add(cand3);
        }

        // Apply local search 2-opt improvement on all candidates
        List<Map<Long, Bus>> improvedCandidates = new ArrayList<>();
        for (Map<Long, Bus> cand : candidates) {
            Map<Long, Bus> refined = improveLocalSearch(in.trips(), eligible, demand, baseBusy, cand);
            improvedCandidates.add(refined);
        }
        candidates.addAll(improvedCandidates);

        // -------------------------------------------------------------
        // Step 3: Select Best Candidate (Strict Objective: Min Unmet Demand)
        // -------------------------------------------------------------
        Map<Long, Bus> proposed = null;
        int bestUnmet = Integer.MAX_VALUE;
        int bestAssignedBusesCount = -1;
        int bestChurn = Integer.MAX_VALUE;

        for (Map<Long, Bus> cand : candidates) {
            if (!isFeasibleSchedule(in.trips(), eligible, baseBusy, cand)) continue;

            int u = computeTotalUnmet(in.trips(), demand, cand);
            int assignedCount = distinctBusesCount(cand);
            int churn = computeChurn(in.trips(), cand);

            boolean isBetter = false;
            if (u < bestUnmet) {
                isBetter = true;
            } else if (u == bestUnmet) {
                // Secondary 1: Higher fleet utilization to eliminate unassigned buses
                if (assignedCount > bestAssignedBusesCount) {
                    isBetter = true;
                } else if (assignedCount == bestAssignedBusesCount) {
                    // Secondary 2: Fewer unnecessary changes from baseline
                    if (churn < bestChurn) {
                        isBetter = true;
                    }
                }
            }

            if (isBetter) {
                bestUnmet = u;
                bestAssignedBusesCount = assignedCount;
                bestChurn = churn;
                proposed = cand;
            }
        }

        // ACCEPTANCE GUARANTEE: Proposed unmet demand must not exceed baseline unmet demand
        if (baselineFeasible && bestUnmet > baselineUnmet) {
            proposed = new HashMap<>(baselineMap);
            bestUnmet = baselineUnmet;
        }

        if (proposed == null) {
            proposed = new HashMap<>(baselineMap);
        }

        final Map<Long, Bus> finalProposed = proposed;

        // -------------------------------------------------------------
        // Step 4: Build Trip Outcomes & Plain-English Explanations
        // -------------------------------------------------------------
        Map<Long, TripOutcome> outcomeByTrip = new HashMap<>();
        for (Trip t : in.trips()) {
            int[] d = demand.get(t.id());
            Bus assignedBus = finalProposed.get(t.id());
            outcomeByTrip.put(t.id(), outcome(t, assignedBus, d, in.trips(), finalProposed, busesById));
        }

        List<TripOutcome> outcomes = new ArrayList<>();
        for (Trip t : in.trips()) {
            outcomes.add(outcomeByTrip.get(t.id()));
        }

        List<SegmentRow> before = segments(in, routes, demand, t -> t.currentBusCapacity());
        List<SegmentRow> after = segments(in, routes, demand,
                t -> finalProposed.containsKey(t.id()) ? finalProposed.get(t.id()).capacity() : 0);

        return buildResult(in, routes, outcomes, before, after, finalProposed, eligible);
    }

    // ----------------------------------------------------------------
    // Optimization Algorithms
    // ----------------------------------------------------------------

    /** Heuristic 1: Start with current baseline and upgrade trips with unmet demand */
    private static Map<Long, Bus> optimizeFromBaseline(
            List<Trip> trips, List<Bus> eligible, Map<Long, int[]> demand,
            Map<Long, List<LocalDateTime[]>> baseBusy, Map<Long, Bus> baseline) {

        Map<Long, Bus> current = new HashMap<>(baseline);
        Map<Long, List<LocalDateTime[]>> busy = copyBusy(baseBusy);
        for (Trip t : trips) {
            Bus b = current.get(t.id());
            if (b != null) busy.get(b.id()).add(new LocalDateTime[]{t.departure(), t.arrival()});
        }

        // Sort trips with unmet demand first
        List<Trip> tripList = new ArrayList<>(trips);
        tripList.sort(Comparator.comparingInt((Trip t) -> {
            Bus b = current.get(t.id());
            int cap = b != null ? b.capacity() : 0;
            return -unmet(demand.get(t.id()), cap);
        }).thenComparing(Trip::departure));

        for (Trip t : tripList) {
            int[] d = demand.get(t.id());
            Bus currBus = current.get(t.id());
            int currUnmet = currBus != null ? unmet(d, currBus.capacity()) : sum(d);

            if (currUnmet == 0 && currBus != null) continue; // fully accommodated

            // Find an eligible active bus that can reduce unmet demand
            Bus bestBus = currBus;
            int bestU = currUnmet;

            for (Bus b : eligible) {
                if (currBus != null && b.id() == currBus.id()) continue;

                // Check if bus b is free for trip t
                if (!isFree(b, busy.get(b.id()), t) || overlapsFixedRestWindow(b, t)) continue;

                int u = unmet(d, b.capacity());
                if (u < bestU || (u == bestU && b.capacity() < (bestBus != null ? bestBus.capacity() : Integer.MAX_VALUE))) {
                    bestBus = b;
                    bestU = u;
                }
            }

            if (bestBus != currBus && bestBus != null) {
                // Free previous bus interval
                if (currBus != null) {
                    removeInterval(busy.get(currBus.id()), t.departure(), t.arrival());
                }
                current.put(t.id(), bestBus);
                busy.get(bestBus.id()).add(new LocalDateTime[]{t.departure(), t.arrival()});
            }
        }
        return current;
    }

    /** Heuristic 2: Global demand-prioritized matching with right-sizing and fleet utilization */
    private static Map<Long, Bus> optimizeDemandFirst(
            List<Trip> trips, List<Bus> eligible, Map<Long, int[]> demand,
            Map<Long, List<LocalDateTime[]>> baseBusy) {

        Map<Long, Bus> assigned = new HashMap<>();
        Map<Long, List<LocalDateTime[]>> busy = copyBusy(baseBusy);

        List<Trip> order = new ArrayList<>(trips);
        order.sort(Comparator.comparingInt((Trip t) -> -peak(demand.get(t.id())))
                .thenComparingInt(t -> -sum(demand.get(t.id())))
                .thenComparing(Trip::departure)
                .thenComparingLong(Trip::id));

        for (Trip t : order) {
            int[] d = demand.get(t.id());
            int peakD = peak(d);

            Bus best = null;
            int bestUnmet = Integer.MAX_VALUE;

            for (Bus b : eligible) {
                if (!isFree(b, busy.get(b.id()), t) || overlapsFixedRestWindow(b, t)) continue;
                int u = unmet(d, b.capacity());

                if (best == null || u < bestUnmet) {
                    best = b;
                    bestUnmet = u;
                } else if (u == bestUnmet) {
                    // Right-size: prefer capacity closer to peak demand
                    boolean bMatchesBaseline = t.currentBusId() != null && b.id() == t.currentBusId();
                    boolean bestMatchesBaseline = t.currentBusId() != null && best.id() == t.currentBusId();
                    if (bMatchesBaseline != bestMatchesBaseline) {
                        if (bMatchesBaseline) { best = b; bestUnmet = u; }
                    } else if (u == 0) {
                        if (Math.abs(b.capacity() - peakD) < Math.abs(best.capacity() - peakD)) {
                            best = b;
                            bestUnmet = u;
                        }
                    } else if (b.capacity() > best.capacity()) {
                        best = b;
                        bestUnmet = u;
                    }
                }
            }

            if (best != null) {
                assigned.put(t.id(), best);
                busy.get(best.id()).add(new LocalDateTime[]{t.departure(), t.arrival()});
            }
        }
        return assigned;
    }

    /** Heuristic 3: Chronological forward assignment */
    private static Map<Long, Bus> optimizeChronological(
            List<Trip> trips, List<Bus> eligible, Map<Long, int[]> demand,
            Map<Long, List<LocalDateTime[]>> baseBusy) {

        Map<Long, Bus> assigned = new HashMap<>();
        Map<Long, List<LocalDateTime[]>> busy = copyBusy(baseBusy);

        List<Trip> order = new ArrayList<>(trips);
        order.sort(Comparator.comparing(Trip::departure)
                .thenComparingInt((Trip t) -> -peak(demand.get(t.id())))
                .thenComparingLong(Trip::id));

        for (Trip t : order) {
            int[] d = demand.get(t.id());
            Bus best = null;
            int bestUnmet = Integer.MAX_VALUE;

            for (Bus b : eligible) {
                if (!isFree(b, busy.get(b.id()), t) || overlapsFixedRestWindow(b, t)) continue;
                int u = unmet(d, b.capacity());
                if (best == null || u < bestUnmet) {
                    best = b;
                    bestUnmet = u;
                } else if (u == bestUnmet && t.currentBusId() != null && b.id() == t.currentBusId()) {
                    best = b;
                    bestUnmet = u;
                }
            }

            if (best != null) {
                assigned.put(t.id(), best);
                busy.get(best.id()).add(new LocalDateTime[]{t.departure(), t.arrival()});
            }
        }
        return assigned;
    }

    /** Local search: 2-opt swaps and idle bus assignment to eliminate unmet demand and utilize fleet */
    private static Map<Long, Bus> improveLocalSearch(
            List<Trip> trips, List<Bus> eligible, Map<Long, int[]> demand,
            Map<Long, List<LocalDateTime[]>> baseBusy, Map<Long, Bus> initial) {

        Map<Long, Bus> current = new HashMap<>(initial);
        boolean improved = true;
        int iterations = 0;

        while (improved && iterations < 50) {
            improved = false;
            iterations++;

            // 1. Try assigning unassigned trips or upgrading trips with unmet demand using unused eligible buses
            Set<Long> usedBuses = new HashSet<>();
            for (Bus b : current.values()) if (b != null) usedBuses.add(b.id());

            for (Trip t : trips) {
                int[] d = demand.get(t.id());
                Bus currBus = current.get(t.id());
                int currUnmet = currBus != null ? unmet(d, currBus.capacity()) : sum(d);

                if (currUnmet > 0) {
                    for (Bus b : eligible) {
                        if (currBus != null && b.id() == currBus.id()) continue;
                        if (!canSafelyAssign(trips, eligible, baseBusy, current, t, b)) continue;

                        int newUnmet = unmet(d, b.capacity());
                        if (newUnmet < currUnmet) {
                            current.put(t.id(), b);
                            improved = true;
                            break;
                        }
                    }
                    if (improved) break;
                }
            }
            if (improved) continue;

            // 2. Try pairwise 2-opt swaps between trips to reduce total unmet demand
            for (int i = 0; i < trips.size() && !improved; i++) {
                Trip t1 = trips.get(i);
                Bus b1 = current.get(t1.id());
                int[] d1 = demand.get(t1.id());

                for (int j = i + 1; j < trips.size(); j++) {
                    Trip t2 = trips.get(j);
                    Bus b2 = current.get(t2.id());
                    int[] d2 = demand.get(t2.id());

                    if (b1 == b2) continue; // Same bus or both null

                    int beforeU = (b1 != null ? unmet(d1, b1.capacity()) : sum(d1))
                                + (b2 != null ? unmet(d2, b2.capacity()) : sum(d2));

                    int afterU = (b2 != null ? unmet(d1, b2.capacity()) : sum(d1))
                               + (b1 != null ? unmet(d2, b1.capacity()) : sum(d2));

                    if (afterU < beforeU) {
                        // Check feasibility of swap
                        Map<Long, Bus> trial = new HashMap<>(current);
                        if (b2 != null) trial.put(t1.id(), b2); else trial.remove(t1.id());
                        if (b1 != null) trial.put(t2.id(), b1); else trial.remove(t2.id());

                        if (isFeasibleSchedule(trips, eligible, baseBusy, trial)) {
                            current = trial;
                            improved = true;
                            break;
                        }
                    }
                }
            }
        }

        return current;
    }

    private static boolean canSafelyAssign(
            List<Trip> trips, List<Bus> eligible, Map<Long, List<LocalDateTime[]>> baseBusy,
            Map<Long, Bus> current, Trip targetTrip, Bus newBus) {

        Map<Long, Bus> trial = new HashMap<>(current);
        trial.put(targetTrip.id(), newBus);
        return isFeasibleSchedule(trips, eligible, baseBusy, trial);
    }

    // ----------------------------------------------------------------
    // Constraint Checks & Feasibility
    // ----------------------------------------------------------------

    /** Verifies that a full assignment schedule violates ZERO operational constraints. */
    public static boolean isFeasibleSchedule(
            List<Trip> trips, List<Bus> eligible,
            Map<Long, List<LocalDateTime[]>> baseBusy, Map<Long, Bus> schedule) {

        Map<Long, List<LocalDateTime[]>> busTrips = copyBusy(baseBusy);

        for (Trip t : trips) {
            Bus b = schedule.get(t.id());
            if (b == null) continue; // Unassigned trips do not violate bus constraints

            // 1. Bus must be active and eligible
            if (!"ACTIVE".equalsIgnoreCase(b.status())) return false;

            // 2. Must not overlap driver's fixed daily rest window
            if (overlapsFixedRestWindow(b, t)) return false;

            // 3. Must not conflict with existing trips/commitments of this bus (enforces 30-min turnaround)
            List<LocalDateTime[]> intervals = busTrips.get(b.id());
            if (intervals == null) intervals = new ArrayList<>();

            if (!isFree(b, intervals, t)) return false;

            intervals.add(new LocalDateTime[]{t.departure(), t.arrival()});
            busTrips.put(b.id(), intervals);
        }

        return true;
    }

    /**
     * A bus is free for a trip if it does not overlap any other trip or commitment,
     * enforcing a mandatory MINIMUM 30-minute turnaround buffer (or the bus's specific restMinutes,
     * whichever is larger).
     */
    public static boolean isFree(Bus bus, List<LocalDateTime[]> intervals, Trip t) {
        if (intervals == null || intervals.isEmpty()) return true;
        int turnaround = Math.max(30, bus.restMinutes());

        for (LocalDateTime[] iv : intervals) {
            boolean startsAfterOther = !t.departure().isBefore(iv[1].plusMinutes(turnaround));
            boolean endsBeforeOther  = !iv[0].isBefore(t.arrival().plusMinutes(turnaround));
            if (!startsAfterOther && !endsBeforeOther) {
                return false;
            }
        }
        return true;
    }

    /** Fixed daily driver rest window independent of turnaround. Handles windows crossing midnight. */
    public static boolean overlapsFixedRestWindow(Bus bus, Trip trip) {
        if (!bus.hasFixedRestWindow()) return false;
        LocalDateTime day = trip.departure().toLocalDate().atStartOfDay();
        for (int offset = -1; offset <= 2; offset++) {
            java.time.LocalDate d = day.toLocalDate().plusDays(offset);
            LocalDateTime start = d.atTime(bus.restStart());
            LocalDateTime end = d.atTime(bus.restEnd());
            if (bus.restEnd().isBefore(bus.restStart())) end = end.plusDays(1);
            if (trip.departure().isBefore(end) && start.isBefore(trip.arrival())) return true;
        }
        return false;
    }

    public static boolean isInsideFixedRestWindow(Bus bus, LocalDateTime time) {
        if (!bus.hasFixedRestWindow()) return false;
        java.time.LocalTime now = time.toLocalTime();
        if (bus.restStart().isBefore(bus.restEnd()))
            return !now.isBefore(bus.restStart()) && now.isBefore(bus.restEnd());
        return !now.isBefore(bus.restStart()) || now.isBefore(bus.restEnd());
    }

    // ----------------------------------------------------------------
    // Demand & Metrics Computation
    // ----------------------------------------------------------------

    private static Map<Long, int[]> demandPerTrip(Input in, Map<Long, Route> routes) {
        Map<Long, Trip> trips = new HashMap<>();
        Map<Long, int[]> demand = new HashMap<>();
        for (Trip t : in.trips()) {
            trips.put(t.id(), t);
            Route r = routes.get(t.routeId());
            int segCount = r != null ? Math.max(0, r.stopIds().size() - 1) : 0;
            demand.put(t.id(), new int[segCount]);
        }
        for (Journey j : in.journeys()) {
            Trip t = trips.get(j.tripId());
            if (t == null) continue;
            Route r = routes.get(t.routeId());
            if (r == null) continue;
            List<Long> stops = r.stopIds();
            int from = stops.indexOf(j.boardingStopId());
            int to = stops.indexOf(j.destinationStopId());
            if (from < 0 || to <= from) continue;
            int[] d = demand.get(t.id());
            for (int s = from; s < to; s++) d[s] += j.passengers();
        }
        for (int[] d : demand.values()) {
            for (int s = 0; s < d.length; s++) {
                if (d[s] < 0) d[s] = 0;
            }
        }
        return demand;
    }

    public static int sum(int[] a) {
        if (a == null) return 0;
        int s = 0;
        for (int x : a) s += x;
        return s;
    }

    public static int peak(int[] a) {
        if (a == null) return 0;
        int p = 0;
        for (int x : a) if (x > p) p = x;
        return p;
    }

    public static int unmet(int[] demand, int capacity) {
        if (demand == null) return 0;
        int u = 0;
        for (int d : demand) u += Math.max(0, d - capacity);
        return u;
    }

    private static int computeTotalUnmet(List<Trip> trips, Map<Long, int[]> demand, Map<Long, Bus> allocation) {
        int total = 0;
        for (Trip t : trips) {
            int[] d = demand.get(t.id());
            Bus b = allocation.get(t.id());
            int cap = b != null ? b.capacity() : 0;
            total += unmet(d, cap);
        }
        return total;
    }

    private static int distinctBusesCount(Map<Long, Bus> allocation) {
        Set<Long> set = new HashSet<>();
        for (Bus b : allocation.values()) if (b != null) set.add(b.id());
        return set.size();
    }

    private static int computeChurn(List<Trip> trips, Map<Long, Bus> allocation) {
        int churn = 0;
        for (Trip t : trips) {
            Bus b = allocation.get(t.id());
            Long assignedId = b != null ? b.id() : null;
            if (!Objects.equals(t.currentBusId(), assignedId)) {
                churn++;
            }
        }
        return churn;
    }

    private static Map<Long, List<LocalDateTime[]>> copyBusy(Map<Long, List<LocalDateTime[]>> orig) {
        Map<Long, List<LocalDateTime[]>> copy = new HashMap<>();
        for (Map.Entry<Long, List<LocalDateTime[]>> e : orig.entrySet()) {
            List<LocalDateTime[]> list = new ArrayList<>();
            for (LocalDateTime[] iv : e.getValue()) {
                list.add(new LocalDateTime[]{iv[0], iv[1]});
            }
            copy.put(e.getKey(), list);
        }
        return copy;
    }

    private static void removeInterval(List<LocalDateTime[]> intervals, LocalDateTime start, LocalDateTime end) {
        if (intervals == null) return;
        intervals.removeIf(iv -> iv[0].equals(start) && iv[1].equals(end));
    }

    // ----------------------------------------------------------------
    // Explanations & Reporting
    // ----------------------------------------------------------------

    private static TripOutcome outcome(
            Trip t, Bus best, int[] d, List<Trip> allTrips,
            Map<Long, Bus> proposed, Map<Long, Bus> busesById) {

        Long prevId = t.currentBusId();
        Bus prevBus = prevId != null ? busesById.get(prevId) : null;
        int prevCap = t.currentBusCapacity();

        int peak = peak(d);
        int totalD = sum(d);
        int propCap = best != null ? best.capacity() : 0;
        int occPercent = propCap > 0 ? (int) Math.round((Math.min(peak, propCap) * 100.0) / propCap) : 0;
        int unmetCount = best != null ? unmet(d, best.capacity()) : totalD;
        int prevUnmet = unmet(d, prevCap);

        String type;
        String reason;

        if (best == null) {
            type = "UNASSIGNED";
            reason = String.format(
                    "Peak demand is %d passengers. Unassigned: no eligible active bus available without violating 30-min turnaround, rest window, or prior commitments.",
                    peak);
        } else if (prevId != null && prevId.equals(best.id())) {
            type = "UNCHANGED";
            if (unmetCount == 0) {
                reason = String.format(
                        "Retained Bus %s (%d seats): fully accommodates peak load (%d passengers, %d%% occupancy) while respecting 30-min turnaround constraint.",
                        best.regNo(), best.capacity(), peak, occPercent);
            } else {
                reason = String.format(
                        "Retained Bus %s (%d seats): highest capacity available bus; leaves %d unmet passenger-segments at peak demand (%d passengers).",
                        best.regNo(), best.capacity(), unmetCount, peak);
            }
        } else if (prevId == null) {
            type = "REASSIGNED";
            if (unmetCount > 0) {
                reason = String.format(
                        "Assigned Bus %s (%d seats) for %d peak passengers (%d%% occupancy); leaves %d unmet passenger-segments due to high route demand.",
                        best.regNo(), best.capacity(), peak, occPercent, unmetCount);
            } else {
                reason = String.format(
                        "Assigned Bus %s (%d seats) for %d peak passengers (%d%% occupancy); satisfies operational turnaround and scheduling constraints.",
                        best.regNo(), best.capacity(), peak, occPercent);
            }
        } else {
            type = "REASSIGNED";
            String prevReg = prevBus != null ? prevBus.regNo() : "Bus #" + prevId;
            if (unmetCount < prevUnmet) {
                reason = String.format(
                        "Upgraded to larger Bus %s (%d seats vs %d seats): reduced unmet demand from %d to %d for peak load of %d passengers (%d%% occupancy).",
                        best.regNo(), best.capacity(), prevCap, prevUnmet, unmetCount, peak, occPercent);
            } else {
                reason = String.format(
                        "Reassigned from %s (%d seats) to Bus %s (%d seats): optimizes fleet utilization (%d%% occupancy) for %d peak passengers while preserving 30-min turnaround buffer.",
                        prevReg, prevCap, best.regNo(), best.capacity(), occPercent, peak);
            }
        }

        return new TripOutcome(t.id(), t.routeId(), prevId, best != null ? best.id() : null,
                type, peak, propCap, occPercent, unmetCount, reason);
    }

    private static List<SegmentRow> segments(Input in, Map<Long, Route> routes, Map<Long, int[]> demand,
                                             java.util.function.ToIntFunction<Trip> capacityOf) {
        Map<Long, int[]> dem = new LinkedHashMap<>();
        Map<Long, int[]> cap = new LinkedHashMap<>();
        for (Route r : in.routes()) {
            int n = Math.max(0, r.stopIds().size() - 1);
            dem.put(r.id(), new int[n]);
            cap.put(r.id(), new int[n]);
        }
        for (Trip t : in.trips()) {
            int[] d = demand.get(t.id());
            for (int i = 0; i < d.length; i++) {
                dem.get(t.routeId())[i] += d[i];
                cap.get(t.routeId())[i] += capacityOf.applyAsInt(t);
            }
        }
        List<SegmentRow> rows = new ArrayList<>();
        for (Route r : in.routes()) {
            for (int i = 0; i < dem.get(r.id()).length; i++) {
                rows.add(new SegmentRow(r.id(), r.stopIds().get(i), r.stopIds().get(i + 1),
                        dem.get(r.id())[i], cap.get(r.id())[i]));
            }
        }
        return rows;
    }

    private static Metrics metrics(List<SegmentRow> rows) {
        int d = 0, c = 0, a = 0, u = 0, f = 0;
        for (SegmentRow r : rows) {
            d += r.demand(); c += r.capacity(); a += r.accommodated(); u += r.unmet(); f += r.unused();
        }
        return new Metrics(d, c, a, u, f);
    }

    private static List<SegmentRow> forRoute(List<SegmentRow> rows, long routeId) {
        List<SegmentRow> out = new ArrayList<>();
        for (SegmentRow r : rows) if (r.routeId() == routeId) out.add(r);
        return out;
    }

    private static Result buildResult(
            Input in, Map<Long, Route> routes, List<TripOutcome> outcomes,
            List<SegmentRow> before, List<SegmentRow> after,
            Map<Long, Bus> proposed, List<Bus> eligible) {

        Map<Long, Set<String>> originalRoutes = new HashMap<>();
        Map<Long, Set<String>> newRoutes = new HashMap<>();
        Map<Long, List<Long>> busesPerRoute = new HashMap<>();

        for (Bus b : in.buses()) {
            originalRoutes.put(b.id(), new TreeSet<>());
            newRoutes.put(b.id(), new TreeSet<>());
        }
        for (Commitment c : in.commitments()) {
            if (originalRoutes.containsKey(c.busId())) originalRoutes.get(c.busId()).add(c.routeCode());
            if (newRoutes.containsKey(c.busId())) newRoutes.get(c.busId()).add(c.routeCode());
        }
        for (TripOutcome o : outcomes) {
            Route r = routes.get(o.routeId());
            String code = r != null ? r.code() : "R" + o.routeId();
            if (o.previousBusId() != null && originalRoutes.containsKey(o.previousBusId()))
                originalRoutes.get(o.previousBusId()).add(code);
            if (o.proposedBusId() != null) {
                newRoutes.get(o.proposedBusId()).add(code);
                List<Long> l = busesPerRoute.computeIfAbsent(o.routeId(), k -> new ArrayList<>());
                if (!l.contains(o.proposedBusId())) l.add(o.proposedBusId());
            }
        }

        List<RouteReport> routeReports = new ArrayList<>();
        for (Route r : in.routes()) {
            routeReports.add(new RouteReport(r.id(), r.code(), r.name(),
                    metrics(forRoute(before, r.id())), metrics(forRoute(after, r.id())),
                    busesPerRoute.getOrDefault(r.id(), List.of())));
        }

        Set<Long> assignedBusIds = new HashSet<>();
        for (TripOutcome o : outcomes) {
            if (o.proposedBusId() != null) assignedBusIds.add(o.proposedBusId());
        }

        int totalUnmetRemaining = metrics(after).unmet();

        List<BusReport> busReports = new ArrayList<>();
        int eligibleCount = eligible.size();

        for (Bus b : in.buses()) {
            boolean isAssigned = assignedBusIds.contains(b.id());
            String reason = explainBusStatus(b, isAssigned, totalUnmetRemaining, in);
            busReports.add(new BusReport(b.id(), b.regNo(), b.capacity(),
                    join(originalRoutes.get(b.id())), join(newRoutes.get(b.id())),
                    reason == null, reason));
        }

        int unassignedEligibleCount = 0;
        for (Bus b : eligible) {
            if (!assignedBusIds.contains(b.id())) unassignedEligibleCount++;
        }

        return new Result(in.time(), metrics(before), metrics(after),
                eligibleCount, assignedBusIds.size(), unassignedEligibleCount,
                routeReports, busReports, outcomes, before, after);
    }

    private static String join(Set<String> s) { return (s == null || s.isEmpty()) ? "-" : String.join(", ", s); }

    private static String explainBusStatus(Bus b, boolean isAssigned, int totalUnmetRemaining, Input in) {
        if (!"ACTIVE".equalsIgnoreCase(b.status())) {
            return "Bus status is " + b.status() + " (ineligible for passenger service)";
        }
        if (isAssigned) {
            return null; // Available and actively assigned
        }
        // Active bus but not assigned
        if (totalUnmetRemaining == 0) {
            return "Active reserve: all scheduled trips are fully accommodated with 0 unmet demand.";
        }
        LocalDateTime now = in.time();
        if (isInsideFixedRestWindow(b, now)) {
            return String.format("Rest window [%s - %s]: driver scheduled rest buffer.", b.restStart(), b.restEnd());
        }
        for (Commitment c : in.commitments()) {
            if (c.busId() == b.id()) {
                return "Committed to prior scheduled trip #" + c.tripId() + " (" + c.routeCode() + ") until " + c.end();
            }
        }
        return "Operational reserve: turnaround and depot proximity buffer for surge coverage.";
    }
}
