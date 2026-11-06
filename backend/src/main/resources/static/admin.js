// VaahanSetu Administrator Portal Logic
const API_BASE = window.VAAHAN_API_URL || (
    (window.location.hostname === "localhost" || window.location.hostname === "127.0.0.1")
        ? "http://localhost:8080/api"
        : `${window.location.origin}/api`
);

const state = {
    token: sessionStorage.getItem('vaahan_auth_token') || '',
    user: JSON.parse(sessionStorage.getItem('vaahan_user') || 'null'),
    activeSession: null,
    simClockMinutes: 600, // Dynamic, synchronized with DB active planning session
    lastAllocationProposal: null,
    trips: [],
    buses: [],
    routes: []
};

function formatMinutesToTime(mins) {
    const h = Math.floor(mins / 60) % 24;
    const m = mins % 60;
    const pad = (n) => String(n).padStart(2, '0');
    return `${pad(h)}:${pad(m)}:00`;
}

// Authentication Helpers
function getAuthHeaders() {
    const headers = { 'Content-Type': 'application/json' };
    if (state.token) {
        headers['Authorization'] = `Bearer ${state.token}`;
    }
    return headers;
}

function checkAuth() {
    const loginSection = document.getElementById('loginSection');
    const adminDashboard = document.getElementById('adminDashboard');
    const logoutBtn = document.getElementById('logoutBtn');

    if (!state.token || !state.user) {
        if (loginSection) loginSection.hidden = false;
        if (adminDashboard) adminDashboard.hidden = true;
        if (logoutBtn) logoutBtn.hidden = true;
        return;
    }

    showDashboard();
}

function showDashboard() {
    const loginSection = document.getElementById('loginSection');
    const adminDashboard = document.getElementById('adminDashboard');
    const logoutBtn = document.getElementById('logoutBtn');

    if (loginSection) loginSection.hidden = true;
    if (adminDashboard) adminDashboard.hidden = false;
    if (logoutBtn) logoutBtn.hidden = false;

    // Update user profile display if present
    const userRoleEl = document.getElementById('userRole');
    const userAvatarEl = document.getElementById('userAvatar');
    if (userRoleEl) userRoleEl.textContent = state.user?.role || 'ADMIN';
    if (userAvatarEl) userAvatarEl.textContent = (state.user?.username || 'A').substring(0, 1).toUpperCase();

    // Setup Navigation Tabs
    initTabs();

    // Initial Data Fetch
    initDashboard();
}

// Login Form Handling
const adminLoginForm = document.getElementById('adminLoginForm');
if (adminLoginForm) {
    adminLoginForm.addEventListener('submit', async (e) => {
        e.preventDefault();
        const username = document.getElementById('adminUsername')?.value?.trim();
        const password = document.getElementById('adminPassword')?.value;
        const feedback = document.getElementById('loginFeedback');
        const submitBtn = document.getElementById('loginSubmitBtn');

        if (!username || !password) {
            if (feedback) {
                feedback.textContent = 'Please enter both username and password.';
                feedback.className = 'feedback-msg text-danger';
            }
            return;
        }

        if (feedback) {
            feedback.textContent = 'Authenticating with server...';
            feedback.className = 'feedback-msg text-info';
        }
        if (submitBtn) submitBtn.disabled = true;

        try {
            const res = await fetch(`${API_BASE}/auth/login`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ username, password })
            });

            const data = await res.json().catch(() => ({}));
            if (!res.ok) {
                throw new Error(data.message || `Login failed (HTTP ${res.status})`);
            }

            const token = data.token || data.accessToken || data.jwt;
            const user = {
                userId: data.userId,
                username: data.username || username,
                fullName: data.fullName,
                role: data.role || 'ADMIN'
            };

            sessionStorage.setItem('vaahan_auth_token', token);
            sessionStorage.setItem('vaahan_user', JSON.stringify(user));
            state.token = token;
            state.user = user;

            if (feedback) feedback.textContent = '';
            showDashboard();
        } catch (err) {
            if (feedback) {
                feedback.textContent = `Login failed: ${err.message}`;
                feedback.className = 'feedback-msg text-danger';
            }
        } finally {
            if (submitBtn) submitBtn.disabled = false;
        }
    });
}

// Setup Logout
const logoutBtn = document.getElementById('logoutBtn');
if (logoutBtn) {
    logoutBtn.addEventListener('click', () => {
        sessionStorage.removeItem('vaahan_auth_token');
        sessionStorage.removeItem('vaahan_user');
        state.token = '';
        state.user = null;
        const loginSection = document.getElementById('loginSection');
        const adminDashboard = document.getElementById('adminDashboard');
        if (loginSection) loginSection.hidden = false;
        if (adminDashboard) adminDashboard.hidden = true;
        logoutBtn.hidden = true;
        const feedback = document.getElementById('loginFeedback');
        if (feedback) {
            feedback.textContent = 'Signed out successfully.';
            feedback.className = 'feedback-msg text-info';
        }
    });
}

function initTabs() {
    const tabBtns = document.querySelectorAll('.tab-btn, .nav-tab');
    const tabContents = document.querySelectorAll('.tab-content');

    tabBtns.forEach(btn => {
        btn.onclick = () => {
            tabBtns.forEach(b => b.classList.remove('active'));
            tabContents.forEach(c => c.classList.remove('active'));

            btn.classList.add('active');
            const target = btn.getAttribute('data-tab');
            const targetSection = document.getElementById(target);
            if (targetSection) targetSection.classList.add('active');
        };
    });
}

// Initial Data Load
async function initDashboard() {
    await fetchActiveSession();
    await Promise.all([
        fetchBuses(),
        fetchRoutes(),
        fetchTrips()
    ]);
    renderSessionConfig();
}

// Fetch Active Session Clock & Details
async function fetchActiveSession() {
    try {
        const res = await fetch(`${API_BASE}/officer/sessions/active`, {
            headers: getAuthHeaders()
        });
        if (res.ok) {
            const session = await res.json();
            state.activeSession = session;
            if (session) {
                if (session.startedAt) {
                    try {
                        const dateStr = session.startedAt.substring(0, 10);
                        const dateFilter = document.getElementById('tripDateFilter');
                        if (dateFilter && !dateFilter.value) {
                            dateFilter.value = dateStr;
                        }
                        const statDateEl = document.getElementById('statServiceDate');
                        if (statDateEl) statDateEl.textContent = dateStr;
                    } catch (e) {
                        console.warn('Could not parse session startedAt:', e);
                    }
                }
            }
        }
    } catch (err) {
        console.warn('Could not sync active session:', err);
    }
}

// Fetch Buses
async function fetchBuses() {
    const tbody = document.querySelector('#busesTable tbody');
    if (tbody) tbody.innerHTML = '<tr><td colspan="5" class="text-center">Loading fleet data...</td></tr>';
    try {
        const res = await fetch(`${API_BASE}/officer/buses`, {
            headers: getAuthHeaders()
        });
        if (!res.ok) throw new Error(`HTTP ${res.status}`);
        const buses = await res.json();
        state.buses = buses;
        const statBusEl = document.getElementById('statBusCount');
        if (statBusEl) statBusEl.textContent = buses.length;

        if (!tbody) return;
        if (buses.length === 0) {
            tbody.innerHTML = '<tr><td colspan="5" class="text-center">No buses registered.</td></tr>';
            return;
        }

        tbody.innerHTML = buses.map(bus => {
            const hasRestTimes = bus.restStart && bus.restEnd;
            const startStr = hasRestTimes ? String(bus.restStart).substring(0, 5) : '';
            const endStr = hasRestTimes ? String(bus.restEnd).substring(0, 5) : '';
            const durStr = bus.restDurationMinutes ? ` (${bus.restDurationMinutes}m)` : '';
            const restWindow = hasRestTimes
                ? `${startStr} – ${endStr}${durStr}`
                : ((bus.restWindowStart && bus.restWindowEnd) ? `${bus.restWindowStart} – ${bus.restWindowEnd}` : 'None assigned');

            const isAvail = bus.available !== false;
            const statusBadge = isAvail
                ? '<span class="status-badge badge-active">Available</span>'
                : `<span class="status-badge badge-resting" title="${bus.availabilityReason || 'Resting / Assigned'}">${bus.availabilityReason ? bus.availabilityReason : 'Unavailable'}</span>`;

            return `
                <tr>
                    <td><strong>#${bus.busId}</strong></td>
                    <td>${bus.registrationNumber || '-'}</td>
                    <td><strong>${bus.capacity}</strong> seats</td>
                    <td>${restWindow}</td>
                    <td>${statusBadge}</td>
                </tr>
            `;
        }).join('');
    } catch (err) {
        if (tbody) tbody.innerHTML = `<tr><td colspan="5" class="text-center text-danger">Error loading buses: ${err.message}</td></tr>`;
    }
}

// Fetch Routes
async function fetchRoutes() {
    try {
        const res = await fetch(`${API_BASE}/officer/routes`, {
            headers: getAuthHeaders()
        });
        if (!res.ok) throw new Error(`HTTP ${res.status}`);
        const routes = await res.json();
        state.routes = routes;
        const statRouteEl = document.getElementById('statRouteCount');
        if (statRouteEl) statRouteEl.textContent = routes.length;
    } catch (err) {
        console.error('Failed to fetch routes:', err);
    }
}

// Fetch Trips
async function fetchTrips() {
    const filterEl = document.getElementById('tripDateFilter');
    let dateInput = filterEl ? filterEl.value : '';
    if (!dateInput && state.activeSession && state.activeSession.startedAt) {
        dateInput = state.activeSession.startedAt.substring(0, 10);
        if (filterEl) filterEl.value = dateInput;
    }
    const statDateEl = document.getElementById('statServiceDate');
    if (statDateEl && dateInput) statDateEl.textContent = dateInput;

    const tbody = document.querySelector('#tripsTable tbody');
    const demandTripSelect = document.getElementById('demandTripSelect');

    if (tbody) tbody.innerHTML = '<tr><td colspan="8" class="text-center">Loading scheduled departures...</td></tr>';

    try {
        const url = dateInput ? `${API_BASE}/officer/trips?date=${encodeURIComponent(dateInput)}` : `${API_BASE}/officer/trips`;
        const res = await fetch(url, {
            headers: getAuthHeaders()
        });
        if (!res.ok) throw new Error(`HTTP ${res.status}`);
        const trips = await res.json();
        state.trips = trips;
        const statTripEl = document.getElementById('statTripCount');
        if (statTripEl) statTripEl.textContent = trips.length;

        // Populate Demand Trip select
        if (demandTripSelect) {
            const previousSelected = demandTripSelect.value;
            demandTripSelect.innerHTML = '<option value="">Select trip</option>' +
                trips.map(t => {
                    const timeLabel = t.departureTime ? (t.departureTime.length > 16 ? t.departureTime.substring(11, 16) : t.departureTime) : 'Dep';
                    return `<option value="${t.tripId}">Trip #${t.tripId} (${t.routeCode || 'Route'} @ ${timeLabel})</option>`;
                }).join('');
            if (previousSelected && trips.some(t => String(t.tripId) === String(previousSelected))) {
                demandTripSelect.value = previousSelected;
            } else if (trips.length > 0) {
                demandTripSelect.selectedIndex = 1;
                loadDemandBreakdown(trips[0].tripId);
            }
        }

        if (!tbody) return;
        if (trips.length === 0) {
            tbody.innerHTML = '<tr><td colspan="8" class="text-center">No trips scheduled for this date.</td></tr>';
            return;
        }

        tbody.innerHTML = trips.map(t => {
            const busReg = t.currentBusRegistration || t.assignedBusNumber;
            const busCap = t.currentBusCapacity || t.busCapacity;
            const assignedBus = busReg ? `Bus #${busReg}` : '<span class="text-muted">Unassigned</span>';
            const cap = busCap ? `${busCap} seats` : '-';
            const depLabel = t.departureTime ? (t.departureTime.length > 16 ? t.departureTime.substring(11, 16) : t.departureTime) : '-';
            const arrLabel = t.expectedArrivalTime ? (t.expectedArrivalTime.length > 16 ? t.expectedArrivalTime.substring(11, 16) : t.expectedArrivalTime) : (t.arrivalTime || '-');
            const peakDemand = t.demand || 0;

            let demandBadge = '';
            if (busCap && busCap > 0) {
                const occ = Math.round((peakDemand / busCap) * 100);
                if (occ > 100) {
                    demandBadge = `<span class="status-badge badge-resting" style="margin-left: 6px;">Overcap (${occ}%)</span>`;
                } else if (occ >= 80) {
                    demandBadge = `<span class="status-badge badge-changed" style="margin-left: 6px;">${occ}%</span>`;
                } else {
                    demandBadge = `<span class="status-badge badge-active" style="margin-left: 6px;">${occ}%</span>`;
                }
            }
            const demandDisplay = `<strong>${peakDemand} pax</strong>${demandBadge}`;

            return `
                <tr>
                    <td><strong>#${t.tripId}</strong></td>
                    <td><span class="route-tag">${t.routeCode || t.routeId}</span></td>
                    <td>${depLabel}</td>
                    <td>${arrLabel}</td>
                    <td>${assignedBus}</td>
                    <td>${cap}</td>
                    <td>${demandDisplay}</td>
                    <td>
                        <button class="action-btn-small" onclick="loadTripDemand(${t.tripId})">View Demand</button>
                    </td>
                </tr>
            `;
        }).join('');
    } catch (err) {
        if (tbody) tbody.innerHTML = `<tr><td colspan="8" class="text-center text-danger">Error loading trips: ${err.message}</td></tr>`;
    }
}

// Load Segment Demand for Trip Modal/Panel
window.loadTripDemand = async function(tripId) {
    const panel = document.getElementById('tripDemandPanel');
    const details = document.getElementById('tripDemandDetails') || document.getElementById('demandDetails');
    if (!details) return;

    if (panel) panel.hidden = false;
    details.innerHTML = '<div class="text-muted">Loading segment demand...</div>';

    // Switch to Trips tab
    document.querySelectorAll('.tab-btn, .nav-tab').forEach(b => b.classList.remove('active'));
    document.querySelectorAll('.tab-content').forEach(c => c.classList.remove('active'));
    const tripsBtn = document.querySelector('[data-tab="tab-trips"]');
    if (tripsBtn) tripsBtn.classList.add('active');
    const tripsSection = document.getElementById('tab-trips');
    if (tripsSection) tripsSection.classList.add('active');

    try {
        const res = await fetch(`${API_BASE}/officer/trips/${tripId}/demand`, {
            headers: getAuthHeaders()
        });
        if (!res.ok) throw new Error(`HTTP ${res.status}`);
        const demand = await res.json();

        if (!demand || demand.length === 0) {
            details.innerHTML = `<p class="text-muted">No segment demand registered for Trip #${tripId}.</p>`;
            return;
        }

        const routeTitle = demand[0]?.routeCode || 'Route';
        details.innerHTML = `<h4>Demand Profile: Trip #${tripId} (${routeTitle})</h4>` +
            demand.map(d => {
                const baseD = d.registeredDemand ?? d.baseDemand ?? 0;
                const simAdj = d.simulatedAdjustment ?? 0;
                const effD = d.effectiveDemand ?? d.adjustedDemand ?? (baseD + simAdj);
                const adjLabel = simAdj !== 0 ? (simAdj > 0 ? `+${simAdj}` : `${simAdj}`) : '0';
                const cap = d.currentCapacity ? `${d.currentCapacity} seats` : (d.currentBus && d.currentBus !== 'UNASSIGNED' ? d.currentBus : 'None');
                return `
                    <div class="segment-card">
                        <div class="segment-name">${d.segmentIndex || '#'}. ${d.startStopName || 'Start'} &rarr; ${d.endStopName || 'End'}</div>
                        <div class="segment-metrics">
                            <span>Base Inquiries/Bookings: <strong>${baseD}</strong></span>
                            <span>Admin Adjustments: <strong>${adjLabel}</strong></span>
                            <span>Effective Demand: <strong>${effD} pax</strong></span>
                            <span>Current Bus Capacity: <strong>${cap}</strong></span>
                        </div>
                    </div>
                `;
            }).join('');

        if (panel) {
            panel.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
        }
    } catch (err) {
        details.innerHTML = `<span class="text-danger">Failed to load demand: ${err.message}</span>`;
    }
};

// Demand Management Controls (Single Source of Truth)
window.loadDemandBreakdown = async function(tripId) {
    const liveContainer = document.getElementById('demandLiveBreakdown');
    if (!liveContainer || !tripId) return;

    liveContainer.innerHTML = '<p class="text-muted">Loading trip demand segments...</p>';
    try {
        const res = await fetch(`${API_BASE}/officer/trips/${tripId}/demand`, {
            headers: getAuthHeaders()
        });
        if (!res.ok) throw new Error(`HTTP ${res.status}`);
        const segments = await res.json();
        if (!segments || segments.length === 0) {
            liveContainer.innerHTML = `<p class="text-muted">No segment demand registered for Trip #${tripId}.</p>`;
            return;
        }

        liveContainer.innerHTML = segments.map(s => {
            const baseD = s.registeredDemand ?? s.baseDemand ?? 0;
            const simAdj = s.simulatedAdjustment ?? 0;
            const effD = s.effectiveDemand ?? s.adjustedDemand ?? (baseD + simAdj);
            const adjLabel = simAdj !== 0 ? (simAdj > 0 ? `+${simAdj}` : `${simAdj}`) : '0';
            const cap = s.currentCapacity ? `${s.currentCapacity} seats` : (s.currentBus || 'None');
            return `
                <div class="segment-card" style="margin-bottom: 8px;">
                    <div class="segment-title">Segment ${s.segmentIndex || '#'}: ${s.startStopName || 'Start'} &rarr; ${s.endStopName || 'End'}</div>
                    <div class="segment-stats">
                        <span>Bookings / Inquiries: <strong>${baseD}</strong></span>
                        <span>Admin Adjustments: <strong>${adjLabel}</strong></span>
                        <span>Total Segment Demand: <strong>${effD} pax</strong></span>
                        <span>Current Bus Capacity: <strong>${cap}</strong></span>
                    </div>
                </div>
            `;
        }).join('');
    } catch (err) {
        liveContainer.innerHTML = `<p class="text-danger">Failed to load breakdown: ${err.message}</p>`;
    }
};

const demandTripSelect = document.getElementById('demandTripSelect');
if (demandTripSelect) {
    demandTripSelect.addEventListener('change', (e) => {
        const tripId = e.target.value;
        if (tripId) {
            loadDemandBreakdown(tripId);
        }
    });
}

document.querySelectorAll('.quick-delta').forEach(btn => {
    btn.addEventListener('click', () => {
        const delta = btn.getAttribute('data-delta');
        const input = document.getElementById('demandDelta');
        if (input) input.value = delta;
    });
});

const applyDemandAdjustmentBtn = document.getElementById('applyDemandAdjustmentBtn');
if (applyDemandAdjustmentBtn) {
    applyDemandAdjustmentBtn.addEventListener('click', async () => {
        const tripId = document.getElementById('demandTripSelect')?.value;
        const delta = parseInt(document.getElementById('demandDelta')?.value, 10);
        const feedback = document.getElementById('demandAdjustmentFeedback');

        if (!tripId) {
            if (feedback) {
                feedback.textContent = 'Please select a target trip first.';
                feedback.className = 'feedback-msg text-danger';
            }
            return;
        }
        if (isNaN(delta) || delta === 0) {
            if (feedback) {
                feedback.textContent = 'Please enter a non-zero adjustment delta.';
                feedback.className = 'feedback-msg text-danger';
            }
            return;
        }

        if (feedback) {
            feedback.textContent = 'Saving adjustment to common demand repository...';
            feedback.className = 'feedback-msg text-info';
        }

        try {
            const res = await fetch(`${API_BASE}/admin/demand/adjust`, {
                method: 'POST',
                headers: getAuthHeaders(),
                body: JSON.stringify({
                    tripId: parseInt(tripId, 10),
                    demandDelta: delta
                })
            });

            if (!res.ok) {
                const errData = await res.json().catch(() => ({}));
                throw new Error(errData.message || `HTTP ${res.status}`);
            }

            if (feedback) {
                feedback.textContent = `Successfully saved ${delta > 0 ? '+' : ''}${delta} demand adjustment to Trip #${tripId}. Synchronized across system!`;
                feedback.className = 'feedback-msg text-success';
            }

            // Refresh trips, breakdown, and buses immediately
            await fetchTrips();
            loadDemandBreakdown(tripId);
            const panel = document.getElementById('tripDemandPanel');
            if (panel && !panel.hidden) {
                window.loadTripDemand(tripId);
            }
        } catch (err) {
            if (feedback) {
                feedback.textContent = `Failed to apply adjustment: ${err.message}`;
                feedback.className = 'feedback-msg text-danger';
            }
        }
    });
}

// Allocation Engine Controls
const runAllocationBtn = document.getElementById('runAllocationBtn');
if (runAllocationBtn) {
    runAllocationBtn.addEventListener('click', async () => {
        const loading = document.getElementById('allocationLoading');
        const panel = document.getElementById('allocationResultsPanel');
        const tbody = document.querySelector('#allocationTable tbody');
        const feedback = document.getElementById('approvalFeedback');
        if (feedback) feedback.textContent = '';

        if (loading) loading.hidden = false;
        if (panel) panel.hidden = true;

        try {
            const dateInput = document.getElementById('tripDateFilter')?.value
                || (state.activeSession?.startedAt ? state.activeSession.startedAt.substring(0, 10) : '');
            const sessionId = state.activeSession?.sessionId || 1;

            const res = await fetch(`${API_BASE}/officer/allocations`, {
                method: 'POST',
                headers: getAuthHeaders(),
                body: JSON.stringify({
                    sessionId: sessionId,
                    serviceDate: dateInput
                })
            });

            if (!res.ok) {
                const errData = await res.json().catch(() => ({}));
                throw new Error(errData.message || `HTTP ${res.status}`);
            }
            const proposal = await res.json();
            state.lastAllocationProposal = proposal;

            if (loading) loading.hidden = true;
            if (panel) panel.hidden = false;

            // Section 1: Demand Analysis Cards
            const da = proposal.demandAnalysis || {};
            const badge = document.getElementById('allocStatusBadge');
            if (badge) {
                badge.textContent = proposal.status || 'PROPOSED';
                badge.className = 'metric-value badge-proposed';
            }
            const tripsCount = document.getElementById('allocTripsCount');
            if (tripsCount) tripsCount.textContent = da.tripsConsidered ?? da.totalTripsEvaluated ?? proposal.assignments?.length ?? 0;
            const totalDemand = document.getElementById('allocTotalDemand');
            if (totalDemand) totalDemand.textContent = `${da.totalDemand ?? da.totalPeakDemand ?? 0} pax`;
            const baselineUnmet = document.getElementById('allocBaselineUnmet');
            if (baselineUnmet) baselineUnmet.textContent = `${proposal.baselineUnmetDemand ?? da.baselineUnmetDemand ?? 0} pax`;
            const proposedUnmet = document.getElementById('allocProposedUnmet');
            if (proposedUnmet) proposedUnmet.textContent = `${proposal.proposedUnmetDemand ?? da.proposedUnmetDemand ?? 0} pax`;
            const accommodated = document.getElementById('allocAccommodated');
            if (accommodated) accommodated.textContent = `${da.accommodatedDemand ?? 0} pax`;

            // Section 2: Fleet Availability Cards
            const fa = proposal.fleetAvailability || {};
            const fleetTotal = document.getElementById('fleetTotalCount');
            if (fleetTotal) fleetTotal.textContent = fa.totalFleet ?? state.buses.length;
            const fleetAvail = document.getElementById('fleetAvailableCount');
            const availCount = fa.busesEligible ?? fa.availableBuses ?? 0;
            const assignCount = fa.busesAssigned ?? 0;
            if (fleetAvail) fleetAvail.textContent = `${availCount} (${assignCount} assigned)`;
            const fleetResting = document.getElementById('fleetRestingCount');
            const unassignedCount = fa.busesUnassigned ?? 0;
            if (fleetResting) fleetResting.textContent = `${unassignedCount} idle/reserve`;
            const fleetMaint = document.getElementById('fleetMaintenanceCount');
            if (fleetMaint) fleetMaint.textContent = `${fa.ineligibleBuses ?? 0} inactive`;

            // Section 3: Proposed Bus Assignments Table with Plain-English Explanations
            if (tbody) {
                if (!proposal.assignments || proposal.assignments.length === 0) {
                    tbody.innerHTML = '<tr><td colspan="7" class="text-center">No assignments generated.</td></tr>';
                } else {
                    const formatTimePart = (val) => {
                        if (!val) return '-';
                        const s = String(val).trim();
                        if (s.includes('T')) return s.split('T')[1].substring(0, 5);
                        if (s.includes(' ')) return s.split(' ')[1].substring(0, 5);
                        if (s.length >= 5) return s.substring(0, 5);
                        return s;
                    };

                    tbody.innerHTML = proposal.assignments.map(a => {
                        const isChanged = a.previousBusId !== a.proposedBusId;
                        const changeTag = isChanged 
                            ? '<span class="status-badge badge-changed" style="margin-left: 6px;">Reassigned</span>'
                            : '<span class="status-badge badge-same" style="margin-left: 6px;">Retained</span>';

                        const prevReg = a.previousBusRegistration || (a.previousBusId ? 'Bus #' + a.previousBusId : 'None');
                        const propReg = a.proposedBusRegistration || (a.proposedBusId ? 'Bus #' + a.proposedBusId : 'Unassigned');

                        const prevCap = a.previousCapacity ?? a.baselineCapacity ?? 0;
                        const propCap = a.proposedCapacity ?? 0;
                        const peakD = a.peakDemand != null ? a.peakDemand : (a.demand ?? 0);

                        const occPct = a.occupancyPercent != null ? a.occupancyPercent : (propCap > 0 ? Math.round((Math.min(peakD, propCap) / propCap) * 100) : 0);
                        let occBadge = '';
                        if (propCap === 0) {
                            occBadge = '<span class="status-badge badge-resting">0% (Unassigned)</span>';
                        } else if (occPct > 100) {
                            occBadge = `<span class="status-badge badge-resting">${occPct}% (Over)</span>`;
                        } else if (occPct >= 80) {
                            occBadge = `<span class="status-badge badge-changed">${occPct}%</span>`;
                        } else {
                            occBadge = `<span class="status-badge badge-active">${occPct}%</span>`;
                        }

                        const depLabel = formatTimePart(a.departureTime);
                        const arrLabel = formatTimePart(a.arrivalTime);
                        const explanationText = a.reason || a.explanation || a.outcome || 'Assigned based on passenger demand and fleet availability.';

                        const prevCapStr = prevCap > 0 ? ` (${prevCap}s)` : '';
                        const propCapStr = propCap > 0 ? ` (${propCap}s)` : '';

                        return `
                            <tr>
                                <td>
                                    <strong>#${a.tripId}</strong>
                                    <span class="route-tag" style="margin-left: 4px;">${a.routeCode || ''}</span>
                                    ${changeTag}
                                </td>
                                <td>${depLabel} &rarr; ${arrLabel}</td>
                                <td><strong>${peakD} pax</strong></td>
                                <td>${prevReg}${prevCapStr}</td>
                                <td><strong>${propReg}${propCapStr}</strong></td>
                                <td>${occBadge}</td>
                                <td>
                                    <div style="font-size: 12px; line-height: 1.45; color: #1e293b; max-width: 380px;">
                                        ${explanationText}
                                    </div>
                                </td>
                            </tr>
                        `;
                    }).join('');
                }
            }

            // Section 4: Operational State Comparison
            const compText = document.getElementById('comparisonSummaryText');
            if (compText) {
                const bUnmet = proposal.baselineUnmetDemand ?? 0;
                const pUnmet = proposal.proposedUnmetDemand ?? 0;
                const diff = bUnmet - pUnmet;
                const pct = bUnmet > 0 ? Math.round((diff / bUnmet) * 100) : 100;
                compText.innerHTML = `
                    <strong>Operational Impact:</strong> Baseline unmet demand reduced from 
                    <strong class="text-danger">${bUnmet} pax</strong> to 
                    <strong class="text-success">${pUnmet} pax</strong> 
                    (${pct}% unmet demand eliminated). Right-sized fleet allocation eliminates bottlenecks while strictly respecting 30-min turnarounds and driver rest windows.
                `;
            }
        } catch (err) {
            if (loading) loading.hidden = true;
            alert(`Allocation computation failed: ${err.message}`);
        }
    });
}

const approveAllocationBtn = document.getElementById('approveAllocationBtn');
if (approveAllocationBtn) {
    approveAllocationBtn.addEventListener('click', async () => {
        if (!state.lastAllocationProposal || !state.lastAllocationProposal.allocationRunId) {
            alert('No active allocation proposal to approve. Please run allocation first.');
            return;
        }

        const feedback = document.getElementById('approvalFeedback');
        if (feedback) {
            feedback.textContent = 'Persisting approved allocation to database...';
            feedback.className = 'feedback-msg text-info';
        }

        try {
            const res = await fetch(`${API_BASE}/officer/allocations/approve`, {
                method: 'POST',
                headers: getAuthHeaders(),
                body: JSON.stringify({
                    allocationRunId: state.lastAllocationProposal.allocationRunId
                })
            });

            if (!res.ok) {
                const errData = await res.json().catch(() => ({}));
                throw new Error(errData.message || `HTTP ${res.status}`);
            }
            const result = await res.json();

            if (feedback) {
                feedback.textContent = `Allocation #${state.lastAllocationProposal.allocationRunId} successfully APPROVED and persisted into operational schedule!`;
                feedback.className = 'feedback-msg text-success';
            }
            const badge = document.getElementById('allocStatusBadge');
            if (badge) {
                badge.textContent = 'APPROVED';
                badge.className = 'metric-value badge-approved';
            }

            // Refresh trips and buses so approved assignments reflect immediately
            fetchTrips();
            fetchBuses();
        } catch (err) {
            if (feedback) {
                feedback.textContent = `Approval failed: ${err.message}`;
                feedback.className = 'feedback-msg text-danger';
            }
        }
    });
}

// Button triggers
const refreshBusesBtn = document.getElementById('refreshBusesBtn');
if (refreshBusesBtn) refreshBusesBtn.addEventListener('click', fetchBuses);

const refreshTripsBtn = document.getElementById('refreshTripsBtn');
if (refreshTripsBtn) refreshTripsBtn.addEventListener('click', fetchTrips);

// Session Configuration (SRS F4 / U2)
function renderSessionConfig() {
    const routesContainer = document.getElementById('sessionRoutesList');
    const busesContainer = document.getElementById('sessionBusesList');

    const selectedRoutes = state.activeSession?.selectedRouteIds || [];
    const selectedBuses = state.activeSession?.selectedBusIds || [];

    if (routesContainer && state.routes) {
        if (state.routes.length === 0) {
            routesContainer.innerHTML = '<p class="text-muted">No routes available.</p>';
        } else {
            routesContainer.innerHTML = state.routes.map(r => {
                const isChecked = selectedRoutes.length === 0 || selectedRoutes.includes(r.routeId);
                const rCode = r.routeCode || r.routeNumber || ('Route #' + r.routeId);
                const rOrigin = r.originName || r.origin || 'Origin';
                const rDest = r.destinationName || r.destination || 'Destination';
                return `
                <div class="checkbox-item" style="padding: 6px 0;">
                    <label style="display: flex; align-items: center; gap: 8px; cursor: pointer;">
                        <input type="checkbox" class="session-route-check" value="${r.routeId}" ${isChecked ? 'checked' : ''}>
                        <span><strong>${rCode}</strong>: ${rOrigin} &rarr; ${rDest}</span>
                    </label>
                </div>
            `;
            }).join('');
        }
    }

    if (busesContainer && state.buses) {
        if (state.buses.length === 0) {
            busesContainer.innerHTML = '<p class="text-muted">No buses available.</p>';
        } else {
            busesContainer.innerHTML = state.buses.map(b => {
                const isChecked = selectedBuses.length === 0 || selectedBuses.includes(b.busId);
                const bReg = b.registrationNumber || ('Bus #' + b.busId);
                return `
                <div class="checkbox-item" style="padding: 6px 0;">
                    <label style="display: flex; align-items: center; gap: 8px; cursor: pointer;">
                        <input type="checkbox" class="session-bus-check" value="${b.busId}" ${isChecked ? 'checked' : ''}>
                        <span><strong>${bReg}</strong> (${b.capacity} seats, ${b.status || 'ACTIVE'})</span>
                    </label>
                </div>
            `;
            }).join('');
        }
    }
}

const saveSessionConfigBtn = document.getElementById('saveSessionConfigBtn');
if (saveSessionConfigBtn) {
    saveSessionConfigBtn.addEventListener('click', async () => {
        const feedback = document.getElementById('sessionConfigFeedback');
        if (feedback) {
            feedback.textContent = 'Saving session configuration...';
            feedback.className = 'feedback-msg text-info';
        }

        const selectedRoutes = Array.from(document.querySelectorAll('.session-route-check:checked'))
            .map(cb => parseInt(cb.value, 10));
        const selectedBuses = Array.from(document.querySelectorAll('.session-bus-check:checked'))
            .map(cb => parseInt(cb.value, 10));

        if (selectedRoutes.length === 0 || selectedBuses.length === 0) {
            if (feedback) {
                feedback.textContent = 'Please select at least one route and one bus for the session.';
                feedback.className = 'feedback-msg text-danger';
            }
            return;
        }

        const sessionId = state.activeSession?.sessionId || 1;

        try {
            const res = await fetch(`${API_BASE}/admin/sessions/${sessionId}/selection`, {
                method: 'PUT',
                headers: getAuthHeaders(),
                body: JSON.stringify({
                    routeIds: selectedRoutes,
                    busIds: selectedBuses
                })
            });

            if (!res.ok) throw new Error(`HTTP ${res.status}`);
            if (feedback) {
                feedback.textContent = `Session configuration saved (${selectedRoutes.length} routes, ${selectedBuses.length} buses active). Synchronizing departures...`;
                feedback.className = 'feedback-msg text-success';
            }

            // Immediately refresh active session, trips, and buses so unselected routes (e.g. Route 2) vanish immediately!
            await fetchActiveSession();
            await Promise.all([fetchTrips(), fetchBuses()]);

            if (feedback) {
                feedback.textContent = `Session configuration saved (${selectedRoutes.length} routes, ${selectedBuses.length} buses active). Departures and fleet updated!`;
                feedback.className = 'feedback-msg text-success';
            }
        } catch (err) {
            if (feedback) {
                feedback.textContent = `Failed to save session configuration: ${err.message}`;
                feedback.className = 'feedback-msg text-danger';
            }
        }
    });
}

const refreshSessionConfigBtn = document.getElementById('refreshSessionConfigBtn');
if (refreshSessionConfigBtn) {
    refreshSessionConfigBtn.addEventListener('click', async () => {
        await Promise.all([fetchRoutes(), fetchBuses()]);
        renderSessionConfig();
        const feedback = document.getElementById('sessionConfigFeedback');
        if (feedback) {
            feedback.textContent = 'Session data refreshed.';
            feedback.className = 'feedback-msg text-info';
        }
    });
}

// Initialize on page load
checkAuth();
