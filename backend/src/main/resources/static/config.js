// VaahanSetu Frontend API Resolver
(function () {
    // 1. Check if URL contains ?api=... query parameter (e.g. ?api=https://vaahansetu-api.onrender.com/api)
    const urlParams = new URLSearchParams(window.location.search);
    const queryApi = urlParams.get('api');
    if (queryApi) {
        const cleanApi = queryApi.replace(/\/+$/, '');
        localStorage.setItem('VAAHAN_API_URL', cleanApi);
        console.log('[VaahanSetu] Set API URL from query parameter:', cleanApi);
    }

    // 2. Check localStorage override
    const savedApi = localStorage.getItem('VAAHAN_API_URL');
    if (savedApi) {
        window.VAAHAN_API_URL = savedApi;
        console.log('[VaahanSetu] Using API URL from localStorage:', window.VAAHAN_API_URL);
        return;
    }

    // 3. Localhost development
    if (window.location.hostname === 'localhost' || window.location.hostname === '127.0.0.1') {
        window.VAAHAN_API_URL = 'http://localhost:8080/api';
        return;
    }

    // 4. Render cloud hosting auto-detection
    if (window.location.hostname.endsWith('.onrender.com')) {
        // If served from the unified web service (or any non-portal domain), use same origin
        if (!window.location.hostname.includes('-portal')) {
            window.VAAHAN_API_URL = `${window.location.origin}/api`;
            console.log('[VaahanSetu] Unified Render host detected, using same-origin API:', window.VAAHAN_API_URL);
            return;
        }
        // If served from a separate static portal, connect to backend API / app
        let apiHost = window.location.hostname.replace('-portal', '-app');
        window.VAAHAN_API_URL = `https://${apiHost}/api`;
        console.log('[VaahanSetu] Auto-detected Render backend API URL:', window.VAAHAN_API_URL);
        return;
    }

    // 5. Default production backend
    window.VAAHAN_API_URL = window.location.hostname.includes('optimized')
        ? 'https://vaahansetu-optimized-app.onrender.com/api'
        : 'https://vaahansetu-app.onrender.com/api';
})();
