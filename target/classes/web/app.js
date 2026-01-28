// Hitori Anti-Lag Dashboard JavaScript

let authToken = localStorage.getItem('antilag-token') || '';
let ws = null;
let performanceChart = null;
let entitiesChart = null;
const metricsHistory = [];
const MAX_HISTORY = 60;

// Initialize on load
document.addEventListener('DOMContentLoaded', () => {
    if (authToken) {
        document.getElementById('auth-modal').classList.add('hidden');
        connect();
    }

    initCharts();
});

// Connect with auth token
function connect() {
    const tokenInput = document.getElementById('auth-token');
    if (tokenInput.value) {
        authToken = tokenInput.value;
        localStorage.setItem('antilag-token', authToken);
    }

    if (!authToken) {
        alert('Please enter an auth token');
        return;
    }

    document.getElementById('auth-modal').classList.add('hidden');
    connectWebSocket();
    refreshAll();
}

// WebSocket connection
function connectWebSocket() {
    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    const wsUrl = `${protocol}//${window.location.host}/ws/metrics?token=${authToken}`;

    ws = new WebSocket(wsUrl);

    ws.onopen = () => {
        console.log('WebSocket connected');
        document.getElementById('connection-status').textContent = 'Connected';
        document.getElementById('connection-status').className = 'status-badge connected';
    };

    ws.onmessage = (event) => {
        try {
            const message = JSON.parse(event.data);
            if (message.type === 'metrics') {
                updateMetrics(message.data);
            }
        } catch (e) {
            console.error('Failed to parse message:', e);
        }
    };

    ws.onclose = () => {
        console.log('WebSocket disconnected');
        document.getElementById('connection-status').textContent = 'Disconnected';
        document.getElementById('connection-status').className = 'status-badge disconnected';

        // Reconnect after 5 seconds
        setTimeout(connectWebSocket, 5000);
    };

    ws.onerror = (error) => {
        console.error('WebSocket error:', error);
    };
}

// Update metrics display
function updateMetrics(data) {
    // Update stats
    document.getElementById('tps').textContent = data.tps.toFixed(1);
    document.getElementById('mspt').textContent = data.mspt.toFixed(1);
    document.getElementById('entities').textContent = data.entityCount.toLocaleString();
    document.getElementById('chunks').textContent = data.loadedChunks.toLocaleString();
    document.getElementById('players').textContent = data.playerCount;
    document.getElementById('memory').textContent = `${data.usedMemoryMb}/${data.maxMemoryMb}`;

    // Update bars
    document.getElementById('tps-bar').style.width = `${(data.tps / 20) * 100}%`;
    document.getElementById('mspt-bar').style.width = `${Math.min((data.mspt / 50) * 100, 100)}%`;
    document.getElementById('memory-bar').style.width = `${(data.usedMemoryMb / data.maxMemoryMb) * 100}%`;

    // Update timestamp
    const now = new Date();
    document.getElementById('last-update').textContent = now.toLocaleTimeString();

    // Add to history
    metricsHistory.push({
        time: now,
        tps: data.tps,
        mspt: data.mspt,
        entities: data.entityCount,
        chunks: data.loadedChunks
    });

    if (metricsHistory.length > MAX_HISTORY) {
        metricsHistory.shift();
    }

    updateCharts();
}

// Initialize charts
function initCharts() {
    const chartOptions = {
        responsive: true,
        maintainAspectRatio: false,
        animation: { duration: 0 },
        scales: {
            x: {
                display: false
            },
            y: {
                beginAtZero: false,
                grid: {
                    color: 'rgba(255, 255, 255, 0.1)'
                },
                ticks: {
                    color: '#a0a0a0'
                }
            }
        },
        plugins: {
            legend: {
                labels: {
                    color: '#e8e8e8'
                }
            }
        }
    };

    // Performance chart (TPS & MSPT)
    const perfCtx = document.getElementById('performance-chart').getContext('2d');
    performanceChart = new Chart(perfCtx, {
        type: 'line',
        data: {
            labels: [],
            datasets: [
                {
                    label: 'TPS',
                    data: [],
                    borderColor: '#06d6a0',
                    backgroundColor: 'rgba(6, 214, 160, 0.1)',
                    fill: true,
                    tension: 0.3
                },
                {
                    label: 'MSPT',
                    data: [],
                    borderColor: '#4361ee',
                    backgroundColor: 'rgba(67, 97, 238, 0.1)',
                    fill: true,
                    tension: 0.3,
                    yAxisID: 'y1'
                }
            ]
        },
        options: {
            ...chartOptions,
            scales: {
                ...chartOptions.scales,
                y1: {
                    position: 'right',
                    beginAtZero: false,
                    grid: { display: false },
                    ticks: { color: '#a0a0a0' }
                }
            }
        }
    });

    // Entities chart
    const entCtx = document.getElementById('entities-chart').getContext('2d');
    entitiesChart = new Chart(entCtx, {
        type: 'line',
        data: {
            labels: [],
            datasets: [
                {
                    label: 'Entities',
                    data: [],
                    borderColor: '#f5576c',
                    backgroundColor: 'rgba(245, 87, 108, 0.1)',
                    fill: true,
                    tension: 0.3
                },
                {
                    label: 'Chunks',
                    data: [],
                    borderColor: '#7209b7',
                    backgroundColor: 'rgba(114, 9, 183, 0.1)',
                    fill: true,
                    tension: 0.3,
                    yAxisID: 'y1'
                }
            ]
        },
        options: {
            ...chartOptions,
            scales: {
                ...chartOptions.scales,
                y1: {
                    position: 'right',
                    beginAtZero: false,
                    grid: { display: false },
                    ticks: { color: '#a0a0a0' }
                }
            }
        }
    });
}

// Update charts with new data
function updateCharts() {
    const labels = metricsHistory.map(m => m.time.toLocaleTimeString());

    performanceChart.data.labels = labels;
    performanceChart.data.datasets[0].data = metricsHistory.map(m => m.tps);
    performanceChart.data.datasets[1].data = metricsHistory.map(m => m.mspt);
    performanceChart.update('none');

    entitiesChart.data.labels = labels;
    entitiesChart.data.datasets[0].data = metricsHistory.map(m => m.entities);
    entitiesChart.data.datasets[1].data = metricsHistory.map(m => m.chunks);
    entitiesChart.update('none');
}

// API helpers
async function apiGet(endpoint) {
    const response = await fetch(`/api/${endpoint}?token=${authToken}`);
    if (!response.ok) {
        throw new Error(`API error: ${response.status}`);
    }
    return response.json();
}

async function apiPost(endpoint) {
    const response = await fetch(`/api/${endpoint}?token=${authToken}`, {
        method: 'POST'
    });
    if (!response.ok) {
        throw new Error(`API error: ${response.status}`);
    }
    return response.json();
}

// Refresh functions
async function refreshAll() {
    refreshStatus();
    refreshHotspots();
    refreshDetections();
    refreshTrend();
}

async function refreshStatus() {
    try {
        const data = await apiGet('status');
        updateMetrics(data.metrics);

        const modeEl = document.getElementById('protection-mode');
        modeEl.textContent = data.protectionMode;
        modeEl.className = `protection-mode ${data.protectionMode}`;
    } catch (e) {
        console.error('Failed to refresh status:', e);
    }
}

async function refreshHotspots() {
    const container = document.getElementById('hotspots-list');

    try {
        const data = await apiGet('hotspots');

        if (data.count === 0) {
            container.innerHTML = '<div class="empty-state">✅ No hotspots detected</div>';
            return;
        }

        container.innerHTML = data.data.map(h => `
            <div class="hotspot-item ${h.level}">
                <div class="hotspot-title">${h.worldName}: ${h.chunkX}, ${h.chunkZ}</div>
                <div class="hotspot-details">
                    Score: ${h.score.toFixed(1)} | Entities: ${h.entityCount} | 
                    <a href="#" onclick="clearChunk('${h.worldName}', ${h.chunkX}, ${h.chunkZ}); return false;">Clear</a>
                </div>
            </div>
        `).join('');
    } catch (e) {
        container.innerHTML = '<div class="loading">Failed to load hotspots</div>';
        console.error('Failed to refresh hotspots:', e);
    }
}

async function refreshDetections() {
    const container = document.getElementById('detections-list');

    try {
        const data = await apiGet('detections');

        if (data.count === 0) {
            container.innerHTML = '<div class="empty-state">✅ No lag machines detected</div>';
            return;
        }

        container.innerHTML = data.data.map(d => `
            <div class="detection-item">
                <div class="hotspot-title">${d.typeName}</div>
                <div class="hotspot-details">${d.details}</div>
            </div>
        `).join('');
    } catch (e) {
        container.innerHTML = '<div class="loading">Failed to load detections</div>';
        console.error('Failed to refresh detections:', e);
    }
}

async function refreshTrend() {
    const container = document.getElementById('trend-info');

    try {
        const data = await apiGet('trend');

        if (!data.hasData) {
            container.innerHTML = '<div class="empty-state">📊 Collecting baseline data...</div>';
            return;
        }

        const trendIcon = data.msptRateOfChange > 0 ? '📈' :
            (data.msptRateOfChange < 0 ? '📉' : '➡️');

        container.innerHTML = `
            <div style="padding: 8px;">
                <p><strong>Current MSPT:</strong> ${data.currentMspt.toFixed(1)}ms</p>
                <p><strong>Predicted (60s):</strong> ${data.predictedMspt.toFixed(1)}ms</p>
                <p><strong>Trend:</strong> ${trendIcon} ${data.msptRateOfChange.toFixed(3)} ms/sec</p>
                <p><strong>Severity:</strong> ${data.severity}</p>
            </div>
        `;
    } catch (e) {
        container.innerHTML = '<div class="loading">Failed to load trend</div>';
        console.error('Failed to refresh trend:', e);
    }
}

// Action functions
async function triggerScan() {
    try {
        await apiPost('action/scan');
        alert('Chunk scan started!');
        setTimeout(refreshHotspots, 3000);
    } catch (e) {
        alert('Failed to trigger scan');
    }
}

async function triggerCull(count) {
    if (!confirm(`Are you sure you want to cull ${count} entities?`)) {
        return;
    }

    try {
        await apiPost(`action/cull?count=${count}`);
        alert(`Cull initiated for ${count} entities`);
        setTimeout(refreshStatus, 1000);
    } catch (e) {
        alert('Failed to trigger cull');
    }
}

async function clearChunk(world, x, z) {
    if (!confirm(`Clear all entities in chunk ${world}:${x},${z}?`)) {
        return;
    }

    try {
        await apiPost(`action/clear-chunk?world=${world}&x=${x}&z=${z}`);
        alert('Chunk clear initiated');
        setTimeout(refreshHotspots, 1000);
    } catch (e) {
        alert('Failed to clear chunk');
    }
}

// Auto-refresh panels every 30 seconds
setInterval(() => {
    refreshHotspots();
    refreshDetections();
    refreshTrend();
}, 30000);
