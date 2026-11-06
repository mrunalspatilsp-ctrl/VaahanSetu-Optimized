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
    // If the frontend is on Render (e.g. vaahansetu-optimized-portal.onrender.com),
    // target the corresponding backend web service (e.g. vaahansetu-optimized-api.onrender.com)
    if (window.location.hostname.endsWith('.onrender.com')) {
        let apiHost = window.location.hostname.replace('-portal', '-api');
        if (!apiHost.includes('-api')) {
            apiHost = window.location.hostname.includes('optimized')
                ? 'vaahansetu-optimized-api.onrender.com'
                : 'vaahansetu-api.onrender.com';
        }
        window.VAAHAN_API_URL = `https://${apiHost}/api`;
        console.log('[VaahanSetu] Auto-detected Render backend API URL:', window.VAAHAN_API_URL);
        return;
    }

    // 5. Default production backend
    window.VAAHAN_API_URL = window.location.hostname.includes('optimized')
        ? 'https://vaahansetu-optimized-api.onrender.com/api'
        : 'https://vaahansetu-api.onrender.com/api';
})();
