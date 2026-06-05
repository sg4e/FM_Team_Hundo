(function () {
    const CARD_IMAGE_BASE_URL = 'https://hundo.maika.moe/nginx_managed/cards';

    function readPositiveInt(params, name, fallback) {
        const parsed = parseInt(params.get(name) || '', 10);
        return Number.isFinite(parsed) && parsed > 0 ? parsed : fallback;
    }

    function cardImageUrl(cardId) {
        return `${CARD_IMAGE_BASE_URL}/${String(cardId).padStart(3, '0')}.PNG`;
    }

    function svgPlaceholder(width, height, fill, stroke, label) {
        const safeLabel = encodeURIComponent(label || 'CARD');
        return `data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='${width}' height='${height}' viewBox='0 0 ${width} ${height}'%3E%3Crect width='${width}' height='${height}' rx='5' fill='${encodeURIComponent(fill)}' stroke='${encodeURIComponent(stroke)}'/%3E%3Ctext x='50%25' y='50%25' dominant-baseline='middle' text-anchor='middle' fill='${encodeURIComponent(stroke)}' font-family='Arial' font-size='9'%3E${safeLabel}%3C/text%3E%3C/svg%3E`;
    }

    function createCardImage(options) {
        const img = document.createElement('img');
        img.src = options.src;
        img.alt = options.alt;
        img.className = options.className || 'card-image';
        img.addEventListener('error', () => {
            img.src = options.fallbackSrc;
        }, { once: true });
        return img;
    }

    async function create(options) {
        const params = new URLSearchParams(window.location.search);
        const teamId = readPositiveInt(params, 'teamId', 0);
        const limit = readPositiveInt(params, 'limit', 5);
        const direction = params.get('direction') || options.defaultDirection || 'top';
        const list = document.getElementById(options.listId || 'acquisitionList');
        const acquisitions = [];
        const cardMap = {};

        if (!list) {
            console.error('Acquisition widget list element not found.');
            return;
        }

        function addAcquisition(acq) {
            const cardName = cardMap[acq.cardId] || `Unknown Card (${acq.cardId})`;
            const context = {
                cardName,
                imageUrl: cardImageUrl(acq.cardId),
                direction,
                placeholder: (fill, stroke, label) => svgPlaceholder(48, 67, fill, stroke, label),
                createCardImage,
            };
            const item = options.renderItem(acq, context);

            if (direction === 'top') {
                list.prepend(item);
                acquisitions.unshift(acq);
            } else {
                list.appendChild(item);
                acquisitions.push(acq);
            }

            if (acquisitions.length > limit) {
                if (direction === 'top') {
                    acquisitions.pop();
                    list.lastElementChild?.remove();
                } else {
                    acquisitions.shift();
                    list.firstElementChild?.remove();
                }
            }
        }

        function connectWebSocket() {
            const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
            const ws = new WebSocket(`${protocol}//${window.location.host}/firehose/team`);

            ws.onopen = () => console.log('WebSocket connected');
            ws.onclose = () => {
                console.log('WebSocket closed, reconnecting in 2s...');
                setTimeout(connectWebSocket, 2000);
            };
            ws.onerror = (error) => console.error('WebSocket error:', error);
            ws.onmessage = (event) => {
                try {
                    const payload = JSON.parse(event.data);
                    if (payload.teamId !== teamId || !payload.newAcquisitions) return;
                    payload.newAcquisitions.forEach(addAcquisition);
                } catch (error) {
                    console.error('Failed to parse WS message:', error);
                }
            };
        }

        try {
            const response = await fetch('cardinfo.json');
            const data = await response.json();
            data.forEach(item => {
                cardMap[item.cardId] = item.cardName;
            });
            connectWebSocket();
        } catch (error) {
            console.error('Failed to load cardinfo.json:', error);
        }
    }

    window.FmAcquisitionWidget = { create };
}());
