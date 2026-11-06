"use strict";

const API_BASE_URL = window.VAAHAN_API_URL || (
    (window.location.hostname === "localhost" || window.location.hostname === "127.0.0.1")
        ? "http://localhost:8080/api"
        : `${window.location.origin}/api`
);

const journeyForm = document.getElementById("journeyForm");
const travelDate = document.getElementById("travelDate");
const routeSelect = document.getElementById("route");
const tripSelect = document.getElementById("tripSelect");
const boardingSelect = document.getElementById("boarding");
const destinationSelect = document.getElementById("destination");
const message = document.getElementById("message");

const routeDetails = document.getElementById("routeDetails");
const routeCode = document.getElementById("routeCode");
const routeOrigin = document.getElementById("routeOrigin");
const routeDestination = document.getElementById("routeDestination");
const routeDuration = document.getElementById("routeDuration");

const decreasePassengers = document.getElementById("decreasePassengers");
const increasePassengers = document.getElementById("increasePassengers");
const passengerCountValue = document.getElementById("passengerCountValue");

const busDetails = document.getElementById("busDetails");
const busCount = document.getElementById("busCount");
const busNumbers = document.getElementById("busNumbers");

const confirmationCard = document.getElementById("confirmationCard");
const waitingCard = document.getElementById("waitingCard");
const demandList = document.getElementById("demandList");

const confirmJourneyId = document.getElementById("confirmJourneyId");
const confirmRoute = document.getElementById("confirmRoute");
const confirmTrip = document.getElementById("confirmTrip");
const confirmBoarding = document.getElementById("confirmBoarding");
const confirmDestination = document.getElementById("confirmDestination");
const confirmPassengers = document.getElementById("confirmPassengers");
const confirmStatus = document.getElementById("confirmStatus");

const boardJourneyBtn = document.getElementById("boardJourneyBtn");
const completeJourneyBtn = document.getElementById("completeJourneyBtn");
const cancelJourneyBtn = document.getElementById("cancelJourneyBtn");
const actionFeedback = document.getElementById("actionFeedback");

let passengerCount = 1;
let routes = [];
let routeTrips = [];

let busPollingTimer = null;
let currentBusRouteId = null;

let statusPollingTimer = null;
let activeJourneyId = null;

// ======================================
// PASSENGER COUNT
// ======================================

function updatePassengerCount() {
    passengerCountValue.textContent = passengerCount;
    passengerCountValue.setAttribute(
        "aria-label",
        `${passengerCount} passengers, minimum 1 and maximum 10`
    );
}

increasePassengers.addEventListener("click", function () {
    if (passengerCount < 10) {
        passengerCount++;
        updatePassengerCount();
    }
});

decreasePassengers.addEventListener("click", function () {
    if (passengerCount > 1) {
        passengerCount--;
        updatePassengerCount();
    }
});

// ======================================
// DROPDOWN FUNCTION
// ======================================

function populateDropdown(selectElement, items, placeholder) {
    selectElement.replaceChildren();

    const firstOption = document.createElement("option");
    firstOption.value = "";
    firstOption.textContent = placeholder;
    selectElement.appendChild(firstOption);

    items.forEach(function (item) {
        const option = document.createElement("option");
        option.value = item.value;
        option.textContent = item.label;
        selectElement.appendChild(option);
    });
}

// ======================================
// LOAD ROUTES
// ======================================

async function loadRoutes() {
    try {
        message.textContent = "Loading routes...";

        const response = await fetch(
            `${API_BASE_URL}/routes`,
            { cache: "no-store" }
        );

        if (!response.ok) {
            throw new Error("Could not load routes.");
        }

        routes = await response.json();

        if (!Array.isArray(routes) || routes.length === 0) {
            message.textContent = "No routes available at the moment.";
            populateDropdown(routeSelect, [], "No routes available");
            return;
        }

        const routeItems = routes.map(function (route) {
            return {
                value: route.routeId,
                label: `${route.routeCode || route.routeName} (${route.originName} → ${route.destinationName})`
            };
        });

        populateDropdown(routeSelect, routeItems, "Choose a route");
        message.textContent = "";

    } catch (error) {
        console.error("Route loading error:", error);
        message.textContent = "Unable to connect to backend service. Please check connection.";
        populateDropdown(routeSelect, [], "Could not load routes");
    }
}

// ======================================
// LOAD SCHEDULED TRIPS FOR ROUTE
// ======================================

async function loadScheduledTrips(routeId) {
    try {
        const dateParam = travelDate && travelDate.value ? `?date=${encodeURIComponent(travelDate.value)}` : "";
        const response = await fetch(
            `${API_BASE_URL}/routes/${routeId}/trips${dateParam}`,
            { cache: "no-store" }
        );

        if (!response.ok) {
            throw new Error("Could not load trips.");
        }

        routeTrips = await response.json();

        if (!Array.isArray(routeTrips) || routeTrips.length === 0) {
            populateDropdown(tripSelect, [], "No scheduled departures today");
            return;
        }

        const tripItems = routeTrips.map(function (trip) {
            const dep = trip.departureTimeFormatted || (trip.departureTime ? trip.departureTime.substring(11, 16) : "Dep");
            const arr = trip.arrivalTimeFormatted || (trip.expectedArrivalTime ? trip.expectedArrivalTime.substring(11, 16) : "Arr");
            const busInfo = trip.registrationNumber && trip.registrationNumber !== "UNASSIGNED"
                ? ` [Bus: ${trip.registrationNumber} (${trip.capacity} seats)]`
                : " [Pending allocation]";
            return {
                value: trip.tripId,
                label: `Departure ${dep} - Arrival ${arr}${busInfo}`
            };
        });

        populateDropdown(tripSelect, tripItems, "Choose a scheduled departure");
        if (tripItems.length > 0) {
            tripSelect.selectedIndex = 1;
        }

    } catch (error) {
        console.error("Trip loading error:", error);
        populateDropdown(tripSelect, [], "Could not load trips");
    }
}

// ======================================
// LOAD PASSENGER DEMAND
// ======================================

async function loadPassengerDemand() {
    try {
        const dateParam = travelDate && travelDate.value ? `?date=${encodeURIComponent(travelDate.value)}` : "";
        const response = await fetch(
            `${API_BASE_URL}/passenger-journeys/demand${dateParam}`,
            { cache: "no-store" }
        );

        if (!response.ok) {
            throw new Error("Could not load passenger demand.");
        }

        const demandData = await response.json();
        demandList.replaceChildren();

        if (!Array.isArray(demandData) || demandData.length === 0) {
            demandList.hidden = true;
            return;
        }

        demandList.hidden = false;

        demandData.forEach(function (item) {
            const route = routes.find(function (r) {
                return Number(r.routeId) === Number(item.routeId);
            });

            const routeTitle = (route && (route.routeCode || route.routeName))
                ? `${route.routeCode || route.routeName} (${route.originName} → ${route.destinationName})`
                : (item.routeCode ? `${item.routeCode} (${item.originName} → ${item.destinationName})` : `Route ID: ${item.routeId}`);

            const total = item.totalDemand != null ? item.totalDemand : item.passengerCount;
            if (total === 0 && !route) return;

            const demandCard = document.createElement("div");
            demandCard.className = "demand-item";

            const title = document.createElement("h3");
            title.textContent = routeTitle;

            const count = document.createElement("p");
            count.className = "passenger-count";
            
            const breakdown = [];
            if (item.confirmedCount) breakdown.push(`${item.confirmedCount} booked`);
            if (item.searchCount) breakdown.push(`${item.searchCount} active inquiries`);
            if (item.adjustmentCount) breakdown.push(`${item.adjustmentCount > 0 ? '+' : ''}${item.adjustmentCount} admin adj`);
            const sub = breakdown.length > 0 ? ` [${breakdown.join(', ')}]` : '';
            count.textContent = `${total} passenger(s) registered & traveling${sub}`;

            demandCard.appendChild(title);
            demandCard.appendChild(count);
            demandList.appendChild(demandCard);
        });

    } catch (error) {
        console.error("Demand loading error:", error);
        demandList.hidden = true;
    }
}

// Record search intent into common demand
async function logSearchIntent() {
    if (!routeSelect.value || !boardingSelect.value || !destinationSelect.value) return;
    try {
        await fetch(`${API_BASE_URL}/passenger-journeys/intent`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                routeId: Number(routeSelect.value),
                tripId: tripSelect.value ? Number(tripSelect.value) : null,
                boardingStopId: Number(boardingSelect.value),
                destinationStopId: Number(destinationSelect.value),
                passengerCount: passengerCount
            })
        });
        loadPassengerDemand();
    } catch (e) {
        console.warn('Search intent logging:', e);
    }
}

// ======================================
// LOAD STOPS
// ======================================

async function loadStops(routeId) {
    try {
        message.textContent = "Loading route stops...";

        const response = await fetch(
            `${API_BASE_URL}/routes/${routeId}/stops`,
            { cache: "no-store" }
        );

        if (!response.ok) {
            throw new Error("Could not load stops.");
        }

        const stops = await response.json();

        const selectedRoute = routes.find(function (route) {
            return String(route.routeId) === String(routeId);
        });

        if (selectedRoute) {
            selectedRoute.stops = stops;
        }

        if (!Array.isArray(stops) || stops.length === 0) {
            message.textContent = "No stops found for this route.";
            populateDropdown(boardingSelect, [], "No stops available");
            populateDropdown(destinationSelect, [], "No destination available");
            return;
        }

        const boardingItems = stops.map(function (stop) {
            return {
                value: stop.stopId,
                label: stop.stopName
            };
        });

        populateDropdown(boardingSelect, boardingItems, "Choose boarding stop");
        populateDropdown(destinationSelect, [], "Choose destination");
        message.textContent = "";

    } catch (error) {
        console.error("Stop loading error:", error);
        message.textContent = "Could not load stops for this route.";
        populateDropdown(boardingSelect, [], "Could not load stops");
        populateDropdown(destinationSelect, [], "Could not load destination");
    }
}

// ======================================
// LOAD ALLOCATED BUSES
// ======================================

async function loadAllocatedBuses(routeId) {
    try {
        const response = await fetch(
            `${API_BASE_URL}/routes/${routeId}/buses`,
            {
                method: "GET",
                cache: "no-store"
            }
        );

        if (!response.ok) {
            throw new Error("Could not load allocated buses.");
        }

        const buses = await response.json();

        if (
            String(routeSelect.value) !== String(routeId) ||
            currentBusRouteId !== String(routeId)
        ) {
            return;
        }

        busDetails.hidden = false;

        if (!Array.isArray(buses) || buses.length === 0) {
            busCount.textContent = "No buses currently assigned to route.";
            busNumbers.textContent = "";
            return;
        }

        busCount.textContent = `${buses.length} bus(es) allocated`;
        busNumbers.textContent = buses.map(function (bus) {
            return `Registration Number: ${bus.registrationNumber}\nActual Capacity: ${bus.capacity}`;
        }).join("\n\n");

    } catch (error) {
        console.error("Bus loading error:", error);
    }
}

function stopBusPolling() {
    if (busPollingTimer !== null) {
        clearTimeout(busPollingTimer);
        busPollingTimer = null;
    }
    currentBusRouteId = null;
}

function startBusPolling(routeId) {
    stopBusPolling();
    currentBusRouteId = String(routeId);
    busDetails.hidden = false;
    busCount.textContent = "Checking bus availability...";
    busNumbers.textContent = "";

    async function refreshBuses() {
        if (currentBusRouteId !== String(routeId)) return;
        await loadAllocatedBuses(routeId);
        if (currentBusRouteId === String(routeId)) {
            busPollingTimer = setTimeout(refreshBuses, 6000);
        }
    }
    refreshBuses();
}

// ======================================
// ROUTE CHANGE EVENT
// ======================================

routeSelect.addEventListener("change", async function () {
    message.textContent = "";
    actionFeedback.textContent = "";
    confirmationCard.hidden = true;
    waitingCard.hidden = true;

    loadPassengerDemand();

    if (!routeSelect.value) {
        stopBusPolling();
        routeDetails.hidden = true;
        busDetails.hidden = true;
        busCount.textContent = "";
        busNumbers.textContent = "";
        populateDropdown(tripSelect, [], "Choose a scheduled departure");
        populateDropdown(boardingSelect, [], "Choose boarding stop");
        populateDropdown(destinationSelect, [], "Choose destination");
        return;
    }

    const selectedRoute = routes.find(function (route) {
        return String(route.routeId) === routeSelect.value;
    });

    if (!selectedRoute) {
        message.textContent = "Selected route not found.";
        return;
    }

    routeCode.textContent = selectedRoute.routeCode || selectedRoute.routeName || "-";
    routeOrigin.textContent = selectedRoute.originName || "-";
    routeDestination.textContent = selectedRoute.destinationName || "-";
    routeDuration.textContent = selectedRoute.estimatedDurationMinutes
        ? selectedRoute.estimatedDurationMinutes + " minutes"
        : "Not available";
    routeDetails.hidden = false;

    startBusPolling(routeSelect.value);
    await loadScheduledTrips(routeSelect.value);
    await loadStops(routeSelect.value);
});

// ======================================
// BOARDING STOP CHANGE
// ======================================

boardingSelect.addEventListener("change", function () {
    populateDropdown(destinationSelect, [], "Choose destination");

    if (!boardingSelect.value || !routeSelect.value) return;

    const selectedRoute = routes.find(function (route) {
        return String(route.routeId) === routeSelect.value;
    });

    if (!selectedRoute || !Array.isArray(selectedRoute.stops)) return;

    const boardingIndex = selectedRoute.stops.findIndex(function (stop) {
        return String(stop.stopId) === boardingSelect.value;
    });

    if (boardingIndex === -1) return;

    const laterStops = selectedRoute.stops
        .slice(boardingIndex + 1)
        .map(function (stop) {
            return {
                value: stop.stopId,
                label: stop.stopName
            };
        });

    populateDropdown(destinationSelect, laterStops, "Choose destination");
});

destinationSelect.addEventListener("change", function () {
    if (destinationSelect.value) {
        logSearchIntent();
    }
});

// ======================================
// CHECK JOURNEY STATUS
// ======================================

async function checkJourneyStatus(journeyId) {
    try {
        const response = await fetch(
            `${API_BASE_URL}/passenger-journeys/${journeyId}`,
            {
                method: "GET",
                cache: "no-store"
            }
        );

        if (!response.ok) throw new Error("Could not check journey status.");

        const journey = await response.json();
        if (String(activeJourneyId) !== String(journeyId)) return;

        updateStatusBadge(journey.journeyStatus);

        if (journey.journeyStatus === "REGISTERED") {
            statusPollingTimer = setTimeout(function () {
                checkJourneyStatus(journeyId);
            }, 4000);
        } else {
            activeJourneyId = null;
            statusPollingTimer = null;
        }

    } catch (error) {
        console.error("Could not check journey status:", error);
    }
}

function updateStatusBadge(status) {
    confirmStatus.textContent = status;
    if (status === "REGISTERED") {
        confirmStatus.style.background = "#fef3c7";
        confirmStatus.style.color = "#92400e";
        boardJourneyBtn.disabled = false;
        completeJourneyBtn.disabled = true;
        cancelJourneyBtn.disabled = false;
    } else if (status === "BOARDED") {
        confirmStatus.style.background = "#dbeafe";
        confirmStatus.style.color = "#1e40af";
        boardJourneyBtn.disabled = true;
        completeJourneyBtn.disabled = false;
        cancelJourneyBtn.disabled = true;
    } else if (status === "COMPLETED") {
        confirmStatus.style.background = "#dcfce7";
        confirmStatus.style.color = "#166534";
        boardJourneyBtn.disabled = true;
        completeJourneyBtn.disabled = true;
        cancelJourneyBtn.disabled = true;
    } else if (status === "CANCELLED") {
        confirmStatus.style.background = "#fee2e2";
        confirmStatus.style.color = "#991b1b";
        boardJourneyBtn.disabled = true;
        completeJourneyBtn.disabled = true;
        cancelJourneyBtn.disabled = true;
    }
}

function startStatusPolling(journeyId) {
    if (statusPollingTimer !== null) {
        clearTimeout(statusPollingTimer);
    }
    activeJourneyId = String(journeyId);
    statusPollingTimer = null;
    checkJourneyStatus(journeyId);
}

// ======================================
// JOURNEY ACTION BUTTONS (BOARD / COMPLETE / CANCEL)
// ======================================

async function updateJourneyAction(newStatus) {
    if (!confirmJourneyId.textContent || confirmJourneyId.textContent === "-") return;
    const jId = confirmJourneyId.textContent;

    actionFeedback.textContent = `Updating journey status to ${newStatus}...`;
    try {
        const resp = await fetch(`${API_BASE_URL}/passenger-journeys/${jId}/status`, {
            method: "PATCH",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ status: newStatus })
        });
        const res = await resp.json();
        if (!resp.ok) {
            throw new Error(res.message || "Failed to update status.");
        }
        updateStatusBadge(newStatus);
        actionFeedback.textContent = `Status successfully updated: ${newStatus}`;
        if (newStatus === "CANCELLED" || newStatus === "COMPLETED") {
            if (statusPollingTimer !== null) {
                clearTimeout(statusPollingTimer);
                statusPollingTimer = null;
            }
            activeJourneyId = null;
        }
        await loadPassengerDemand();
    } catch (e) {
        actionFeedback.textContent = e.message || "Status update failed.";
    }
}

boardJourneyBtn.addEventListener("click", () => updateJourneyAction("BOARDED"));
completeJourneyBtn.addEventListener("click", () => updateJourneyAction("COMPLETED"));
cancelJourneyBtn.addEventListener("click", () => updateJourneyAction("CANCELLED"));

// ======================================
// JOURNEY FORM SUBMISSION
// ======================================

journeyForm.addEventListener("submit", async function (event) {
    event.preventDefault();

    const submitButton = journeyForm.querySelector('button[type="submit"]');
    if (submitButton.disabled) return;

    confirmationCard.hidden = true;
    waitingCard.hidden = true;
    actionFeedback.textContent = "";

    if (!routeSelect.value || !boardingSelect.value || !destinationSelect.value) {
        message.textContent = "Please select a route, boarding stop and destination.";
        return;
    }

    const selectedRoute = routes.find(function (route) {
        return String(route.routeId) === routeSelect.value;
    });

    if (!selectedRoute || !Array.isArray(selectedRoute.stops)) {
        message.textContent = "Please select a valid route.";
        return;
    }

    const boardingIndex = selectedRoute.stops.findIndex(function (stop) {
        return String(stop.stopId) === boardingSelect.value;
    });

    const destinationIndex = selectedRoute.stops.findIndex(function (stop) {
        return String(stop.stopId) === destinationSelect.value;
    });

    if (boardingIndex < 0 || destinationIndex <= boardingIndex) {
        message.textContent = "Choose a destination after your boarding stop.";
        return;
    }

    const journeyData = {
        routeId: Number(routeSelect.value),
        boardingStopId: Number(boardingSelect.value),
        destinationStopId: Number(destinationSelect.value),
        passengerCount: passengerCount
    };

    if (tripSelect.value) {
        journeyData.tripId = Number(tripSelect.value);
    }

    const boardingName = boardingSelect.options[boardingSelect.selectedIndex].textContent;
    const destinationName = destinationSelect.options[destinationSelect.selectedIndex].textContent;
    const tripName = tripSelect.selectedIndex > 0 ? tripSelect.options[tripSelect.selectedIndex].textContent : "Earliest Available";

    submitButton.disabled = true;
    submitButton.textContent = "Submitting...";

    try {
        message.textContent = "Submitting your journey...";

        const response = await fetch(`${API_BASE_URL}/passenger-journeys`, {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify(journeyData)
        });

        const result = await response.json();

        if (!response.ok) {
            throw new Error(result.message || "Journey submission failed.");
        }

        message.textContent = `Journey registered successfully! Journey ID: ${result.journeyId}.`;

        confirmJourneyId.textContent = result.journeyId;
        confirmRoute.textContent = `${selectedRoute.routeCode || selectedRoute.routeName} - ${selectedRoute.originName} → ${selectedRoute.destinationName}`;
        confirmTrip.textContent = tripName;
        confirmBoarding.textContent = boardingName;
        confirmDestination.textContent = destinationName;
        confirmPassengers.textContent = passengerCount;
        updateStatusBadge(result.journeyStatus || "REGISTERED");

        waitingCard.hidden = true;
        confirmationCard.hidden = false;

        // Reset and clear booking form for clean UX
        resetBookingForm();

        await loadPassengerDemand();
        startStatusPolling(result.journeyId);

    } catch (error) {
        console.error("Journey submission error:", error);
        message.textContent = "";
        if (error.message === "No buses available at the moment. Please wait...") {
            waitingCard.hidden = false;
        } else {
            message.textContent = error.message || "Could not submit your journey.";
        }
    } finally {
        submitButton.disabled = false;
        submitButton.textContent = "Confirm Journey";
    }
});

// ======================================
// FORM CLEARANCE & RESET HELPER
// ======================================

function resetBookingForm() {
    journeyForm.reset();
    routeSelect.value = "";
    populateDropdown(tripSelect, [], "Choose a scheduled departure");
    populateDropdown(boardingSelect, [], "Choose boarding stop");
    populateDropdown(destinationSelect, [], "Choose destination");
    routeDetails.hidden = true;
    busDetails.hidden = true;
    busCount.textContent = "";
    busNumbers.textContent = "";
    passengerCount = 1;
    updatePassengerCount();
    stopBusPolling();
}

const newBookingBtn = document.getElementById("newBookingBtn");
if (newBookingBtn) {
    newBookingBtn.addEventListener("click", function () {
        confirmationCard.hidden = true;
        waitingCard.hidden = true;
        message.textContent = "";
        actionFeedback.textContent = "";
        resetBookingForm();
    });
}

// Initialize travel date to today if available
if (travelDate && !travelDate.value) {
    travelDate.value = new Date().toISOString().substring(0, 10);
}

if (travelDate) {
    travelDate.addEventListener("change", async function () {
        if (routeSelect.value) {
            await loadScheduledTrips(routeSelect.value);
        }
        await loadPassengerDemand();
    });
}

// ======================================
// INITIAL PAGE LOAD
// ======================================

updatePassengerCount();
loadRoutes();
loadPassengerDemand();
