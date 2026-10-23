const http = require('http');
const fs = require('fs');
const path = require('path');

const PORT = 3000;
const APK_PATHS = [
  path.join('/app/applet/.build-outputs/app-debug.apk'),
  path.join('/app/applet/app/build/outputs/apk/debug/app-debug.apk')
];

function findApk() {
  for (const p of APK_PATHS) {
    if (fs.existsSync(p)) return p;
  }
  return null;
}

const HTML = `<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1.0" />
  <title>FocusLock — Android App Preview & APK Download</title>
  <style>
    :root {
      --bg: #0e1116;
      --surface: #171b22;
      --surface-alt: #1f2530;
      --divider: #28303e;
      --text: #f0f4f8;
      --muted: #94a3b8;
      --accent: #38bdf8;
      --accent-soft: rgba(56, 189, 248, 0.14);
      --success: #34d399;
      --warning: #fbbf24;
    }
    * { box-sizing: border-box; margin: 0; padding: 0; }
    body {
      font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
      background: var(--bg);
      color: var(--text);
      min-height: 100vh;
      display: flex;
      flex-direction: column;
      align-items: center;
      padding: 20px 16px;
    }
    .top-bar {
      width: 100%;
      max-width: 900px;
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      justify-content: space-between;
      gap: 12px;
      margin-bottom: 18px;
      padding: 14px 18px;
      background: var(--surface);
      border: 1px solid var(--divider);
      border-radius: 14px;
    }
    .brand { display: flex; align-items: center; gap: 10px; }
    .brand h1 { font-size: 18px; font-weight: 700; }
    .badge {
      font-size: 11px;
      font-weight: 600;
      padding: 4px 10px;
      border-radius: 999px;
      background: var(--accent-soft);
      color: var(--accent);
    }
    .badge.success { background: rgba(52, 211, 153, 0.15); color: var(--success); }
    .download-btn {
      display: inline-flex;
      align-items: center;
      gap: 8px;
      background: var(--accent);
      color: #09131c;
      font-weight: 700;
      font-size: 13px;
      text-decoration: none;
      padding: 10px 16px;
      border-radius: 10px;
      border: none;
      cursor: pointer;
    }
    .phone-frame {
      width: 100%;
      max-width: 400px;
      min-height: 700px;
      background: var(--bg);
      border: 2px solid var(--divider);
      border-radius: 28px;
      display: flex;
      flex-direction: column;
      overflow: hidden;
      box-shadow: 0 20px 50px rgba(0,0,0,0.45);
    }
    .screen {
      flex: 1;
      padding: 20px 16px;
      overflow-y: auto;
      display: flex;
      flex-direction: column;
      gap: 12px;
    }
    .subtitle { font-size: 13px; color: var(--muted); line-height: 1.45; }
    .title { font-size: 22px; font-weight: 700; margin-top: 2px; }
    .card {
      background: var(--surface);
      border: 1px solid var(--divider);
      border-radius: 16px;
      padding: 15px;
    }
    .card.clickable { cursor: pointer; transition: border-color 0.15s; }
    .card.clickable:hover { border-color: var(--accent); }
    .card.selected { background: var(--surface-alt); border: 2px solid var(--accent); }
    .grid-2 { display: grid; grid-template-columns: 1fr 1fr; gap: 9px; }
    .dots { display: flex; gap: 4px; margin-bottom: 8px; }
    .dot { width: 7px; height: 7px; border-radius: 4px; background: var(--divider); }
    .dot.on { background: var(--accent); }
    .row { display: flex; align-items: center; justify-content: space-between; gap: 10px; }
    .row-title { font-size: 14px; font-weight: 600; }
    .seg-row { display: grid; grid-template-columns: repeat(5, 1fr); gap: 6px; }
    .seg {
      height: 40px;
      border-radius: 10px;
      border: 1px solid var(--divider);
      background: transparent;
      color: var(--muted);
      font-weight: 600;
      font-size: 12px;
      cursor: pointer;
    }
    .seg.active { background: var(--surface-alt); color: var(--text); border-color: var(--accent); }
    .primary-btn {
      width: 100%;
      padding: 13px;
      border-radius: 12px;
      border: none;
      background: var(--accent);
      color: #08121a;
      font-weight: 700;
      font-size: 14px;
      cursor: pointer;
    }
    .section-label {
      font-size: 11px;
      font-weight: 700;
      text-transform: uppercase;
      letter-spacing: 0.06em;
      color: var(--muted);
      margin-top: 6px;
    }
    .list-row {
      display: flex;
      align-items: center;
      justify-content: space-between;
      padding: 10px 0;
      cursor: pointer;
    }
    .list-row + .list-row { border-top: 1px solid var(--divider); }
    .nav-bar {
      display: grid;
      grid-template-columns: repeat(3, 1fr);
      background: var(--surface);
      border-top: 1px solid var(--divider);
      padding: 8px 0 10px;
    }
    .nav-item {
      display: flex;
      flex-direction: column;
      align-items: center;
      gap: 4px;
      color: var(--muted);
      font-size: 11.5px;
      cursor: pointer;
      background: none;
      border: none;
    }
    .nav-item.active { color: var(--accent); font-weight: 700; }
    .nav-pill {
      padding: 4px 18px;
      border-radius: 15px;
    }
    .nav-item.active .nav-pill { background: var(--accent-soft); }
    .modal-backdrop {
      position: fixed;
      inset: 0;
      background: rgba(0,0,0,0.7);
      display: none;
      align-items: center;
      justify-content: center;
      padding: 16px;
      z-index: 50;
    }
    .modal-backdrop.open { display: flex; }
    .modal {
      background: var(--surface);
      border: 1px solid var(--divider);
      border-radius: 18px;
      max-width: 360px;
      width: 100%;
      padding: 20px;
      display: flex;
      flex-direction: column;
      gap: 12px;
    }
    .modal-actions { display: flex; gap: 8px; justify-content: flex-end; margin-top: 6px; }
    .ghost-btn {
      padding: 9px 14px;
      border-radius: 10px;
      border: 1px solid var(--divider);
      background: transparent;
      color: var(--text);
      font-size: 13px;
      cursor: pointer;
    }
  </style>
</head>
<body>
  <div class="top-bar">
    <div class="brand">
      <h1>FocusLock Android (v1.16)</h1>
      <span class="badge success">APK Compiled</span>
      <span class="badge" id="tierBadge">Free Tier</span>
    </div>
    <div style="display:flex; gap:8px;">
      <button class="ghost-btn" onclick="openOnboardingModal()">Preview 3-Step Onboarding</button>
      <a class="download-btn" href="/download/app-debug.apk" download="FocusLock-debug.apk">Download APK</a>
    </div>
  </div>

  <div class="phone-frame">
    <div class="screen" id="screenContent"></div>
    <div class="nav-bar">
      <button class="nav-item active" id="nav-focus" onclick="selectTab('focus')">
        <div class="nav-pill">●</div>
        <span>Focus</span>
      </button>
      <button class="nav-item" id="nav-rules" onclick="selectTab('rules')">
        <div class="nav-pill">◆</div>
        <span>Rules</span>
      </button>
      <button class="nav-item" id="nav-you" onclick="selectTab('you')">
        <div class="nav-pill">▲</div>
        <span>You</span>
      </button>
    </div>
  </div>

  <div class="modal-backdrop" id="modalBackdrop">
    <div class="modal" id="modalBox"></div>
  </div>

  <script>
    let state = {
      tab: 'focus',
      isPro: false,
      selectedMode: 'Block',
      duration: '2h',
      sessionActive: false,
      a11yGranted: false
    };

    const modes = [
      { id: 'Gentle', strength: 1, pro: false, desc: 'Reminds you first; you can still open the app.' },
      { id: 'Block', strength: 2, pro: false, desc: 'Closes blocked apps immediately; allows early exit.' },
      { id: 'Sanctuary', strength: 3, pro: true, desc: 'No early exit until the timer finishes.' },
      { id: 'Kiosk', strength: 4, pro: true, desc: 'Pins the phone to allowed essentials only.' }
    ];

    function selectTab(tab) {
      state.tab = tab;
      ['focus', 'rules', 'you'].forEach(t => {
        const el = document.getElementById('nav-' + t);
        if (el) el.classList.toggle('active', t === tab || ((tab === 'tasks' || tab === 'library') && t === 'focus'));
      });
      render();
    }

    function render() {
      document.getElementById('tierBadge').textContent = state.isPro ? 'Pro Unlocked' : 'Free Tier';
      const host = document.getElementById('screenContent');
      if (state.tab === 'focus') {
        const currentMode = modes.find(m => m.id === state.selectedMode) || modes[1];
        host.innerHTML = \`
          <div>
            <div class="subtitle">Wednesday evening · Automatic rules active</div>
            <div class="title">\${state.sessionActive ? state.selectedMode + ' session running' : 'Nothing running'}</div>
          </div>
          \${state.sessionActive ? \`
            <div class="card" style="text-align:center; padding:24px 16px;">
              <div class="badge success" style="display:inline-block; margin-bottom:12px;">Running</div>
              <div style="font-size:36px; font-weight:800; margin:8px 0;">\${state.duration}</div>
              <div class="subtitle">Remaining in \${state.selectedMode} mode</div>
              <button class="ghost-btn" style="margin-top:16px; width:100%;" onclick="state.sessionActive=false; render();">End session</button>
            </div>
          \` : \`
            <div class="grid-2">
              \${modes.map(m => {
                const locked = m.pro && !state.isPro;
                const sel = m.id === state.selectedMode && !locked;
                return \`
                  <div class="card clickable \${sel ? 'selected' : ''}" style="\${locked ? 'opacity:0.78;' : ''}" onclick="pickMode('\${m.id}', \${locked})">
                    <div class="row">
                      <div class="dots">
                        \${[1,2,3,4].map(i => \`<div class="dot \${i <= m.strength ? 'on' : ''}"></div>\`).join('')}
                      </div>
                      \${locked ? '<span class="badge">Pro</span>' : ''}
                    </div>
                    <div class="row-title">\${m.id}</div>
                  </div>
                \`;
              }).join('')}
            </div>
            <div class="subtitle">\${currentMode.id}: \${currentMode.desc}</div>
            <div class="seg-row">
              \${['25m','1h','2h','4h','Set'].map(d => \`
                <button class="seg \${state.duration === d ? 'active' : ''}" onclick="state.duration='\${d}'; render();">\${d}</button>
              \`).join('')}
            </div>
            <button class="primary-btn" onclick="state.sessionActive=true; render();">Start \${state.selectedMode} · \${state.duration}</button>
          \`}
          <div class="card">
            <div class="row" style="margin-bottom:8px;">
              <span class="section-label" style="margin:0;">Always-On Automatic Rules</span>
              <span class="badge success">Enforcing 24/7</span>
            </div>
            <div class="list-row" onclick="selectTab('rules')">
              <div>
                <div class="row-title">Workday Deep Focus Schedule</div>
                <div class="subtitle">09:00 – 17:00 · Enforces automatically without a session</div>
              </div>
              <span class="badge">Active</span>
            </div>
            <div class="list-row" onclick="selectTab('rules')">
              <div>
                <div class="row-title">Daily App Limits</div>
                <div class="subtitle">3 apps capped daily · Enforces automatically</div>
              </div>
              <span style="color:var(--muted)">›</span>
            </div>
          </div>
          <div class="section-label">Tasks & safe tools</div>
          <div class="card">
            <div class="list-row" onclick="selectTab('tasks')">
              <div>
                <div class="row-title">Tasks & Earn Mode</div>
                <div class="subtitle">Track focus tasks, checklist items, and earned screen time</div>
              </div>
              <span style="color:var(--muted)">›</span>
            </div>
            <div class="list-row" onclick="selectTab('library')">
              <div>
                <div class="row-title">Safe Tools & Allowed Apps</div>
                <div class="subtitle">Allowlisted browser, text-only Wikipedia search, and essential apps</div>
              </div>
              <span style="color:var(--muted)">›</span>
            </div>
          </div>
        \`;
      } else if (state.tab === 'tasks') {
        host.innerHTML = \`
          <button class="ghost-btn" style="align-self:flex-start;" onclick="selectTab('focus')">← Back to Focus</button>
          <div class="title">Tasks & Earn Mode</div>
          <div class="subtitle">Complete tasks and ambient timers to earn screen time.</div>
          <div class="card">
            <div class="row-title">Earn Mode Time Bank</div>
            <div class="subtitle" style="margin:6px 0 12px;">Requires FocusLock Pro to bank minutes toward budgeted apps.</div>
            <button class="primary-btn" onclick="requirePro('Earn Mode')">\${state.isPro ? 'Earn Mode Enabled ✓' : 'Unlock Earn Mode (Pro)'}</button>
          </div>
        \`;
      } else if (state.tab === 'library') {
        host.innerHTML = \`
          <button class="ghost-btn" style="align-self:flex-start;" onclick="selectTab('focus')">← Back to Focus</button>
          <div class="title">Safe Tools & Allowed Apps</div>
          <div class="subtitle">Distraction-free tools available even during a lock.</div>
          <div class="card" style="border-color:rgba(56,189,248,0.35);">
            <div class="row">
              <div class="row-title" style="display:flex; align-items:center; gap:6px;">
                <span>▶ Safe YouTube</span>
                <span class="badge success">Zero Shorts</span>
              </div>
              <span class="badge">6 Channels</span>
            </div>
            <div class="subtitle" style="margin:6px 0 10px;">Watch lectures & tutorials without Shorts, algorithmic recommendations, comments, or visual modesty distractions.</div>
            <div style="display:flex; flex-wrap:wrap; gap:6px; margin-bottom:12px;">
              <span class="badge">Khan Academy</span>
              <span class="badge">freeCodeCamp</span>
              <span class="badge">MIT OCW</span>
              <span class="badge">3Blue1Brown</span>
            </div>
            <button class="primary-btn" onclick="openSafeYouTubeModal()">Open Safe YouTube Player</button>
          </div>
          <div class="card">
            <div class="list-row">
              <div>
                <div class="row-title">Safe Allowlist Browser</div>
                <div class="subtitle">Opens only domains on your allowlist</div>
              </div>
              <span class="badge">Ready</span>
            </div>
            <div class="list-row">
              <div>
                <div class="row-title">Text-Only Wikipedia Search</div>
                <div class="subtitle">Look up facts without images or infinite feeds</div>
              </div>
              <span class="badge">Ready</span>
            </div>
          </div>
        \`;
      } else if (state.tab === 'rules') {
        const proBadge = state.isPro ? 'Configured' : 'Pro';
        host.innerHTML = \`
          <div>
            <div class="title">Rules</div>
            <div class="subtitle">Schedules, Bedtime, Limits, and Places enforce automatically.</div>
          </div>
          <div class="grid-2">
            <div class="card clickable"><div class="row"><span class="badge">6 blocked</span></div><div class="row-title" style="margin-top:8px;">Apps</div></div>
            <div class="card clickable"><div class="row"><span class="badge">2 windows</span></div><div class="row-title" style="margin-top:8px;">Schedules</div></div>
            <div class="card clickable" onclick="requirePro('Bedtime Mode')"><div class="row"><span class="badge">\${state.isPro ? '22:00–06:00' : 'Pro'}</span></div><div class="row-title" style="margin-top:8px;">Bedtime</div></div>
            <div class="card clickable"><div class="row"><span class="badge">3 apps</span></div><div class="row-title" style="margin-top:8px;">Daily limits</div></div>
            <div class="card clickable"><div class="row"><span class="badge">12 sites</span></div><div class="row-title" style="margin-top:8px;">Websites</div></div>
            <div class="card clickable" onclick="openA11yDisclosure()"><div class="row"><span class="badge">\${state.a11yGranted ? '20 active' : 'Needs A11y'}</span></div><div class="row-title" style="margin-top:8px;">Keyword guard</div></div>
            <div class="card clickable" onclick="requirePro('Place & Wi-Fi Rules')"><div class="row"><span class="badge">\${proBadge}</span></div><div class="row-title" style="margin-top:8px;">Places</div></div>
            <div class="card clickable" onclick="requirePro('Custom Rules')"><div class="row"><span class="badge">\${proBadge}</span></div><div class="row-title" style="margin-top:8px;">Custom rules</div></div>
          </div>
          <div class="card">
            <div class="list-row">
              <div>
                <div class="row-title">Always-allowed apps</div>
                <div class="subtitle">Phone, Messages, Maps · Never blocked</div>
              </div>
              <span style="color:var(--muted)">›</span>
            </div>
            <div class="list-row">
              <div>
                <div class="row-title">Profiles & Backup</div>
                <div class="subtitle">Export or import your rules JSON</div>
              </div>
              <span style="color:var(--muted)">›</span>
            </div>
          </div>
        \`;
      } else if (state.tab === 'you') {
        host.innerHTML = \`
          <div>
            <div class="title">You</div>
            <div class="subtitle">Plan status, stats, appearance, and advanced engine switches.</div>
          </div>
          <div class="card">
            <div class="row">
              <div class="row-title">\${state.isPro ? 'FocusLock Pro is active' : 'FocusLock Free Tier'}</div>
              <span class="badge \${state.isPro ? 'success' : ''}">\${state.isPro ? 'Pro' : 'Free tier'}</span>
            </div>
            <div class="subtitle" style="margin-top:6px;">
              \${state.isPro
                ? 'All session strengths (Sanctuary & Kiosk), Bedtime dimming, Place & Wi-Fi rules, Earn Mode, and Custom rules are unlocked.'
                : 'Unlimited Gentle & Block sessions, App Blocking, Schedules, and Daily Limits are free forever.'}
            </div>
            \${!state.isPro ? '<button class="primary-btn" style="margin-top:12px;" onclick="requirePro()">Unlock FocusLock Pro</button>' : ''}
          </div>
          <div class="section-label">Setup & Help (Advanced)</div>
          <div class="card">
            <div class="list-row">
              <div>
                <div class="row-title">What FocusLock may do (Capabilities)</div>
                <div class="subtitle">Advanced 34-switch engine registry (hidden from Rules tab)</div>
              </div>
              <span style="color:var(--muted)">›</span>
            </div>
            <div class="list-row" onclick="openA11yDisclosure()">
              <div>
                <div class="row-title">Permissions & Prominent Disclosure</div>
                <div class="subtitle">Google Play–compliant Accessibility disclosure</div>
              </div>
              <span style="color:var(--muted)">›</span>
            </div>
          </div>
        \`;
      }
    }

    function pickMode(id, locked) {
      if (locked) {
        requirePro(id + ' Mode');
      } else {
        state.selectedMode = id;
        render();
      }
    }

    function requirePro(featureName) {
      if (state.isPro) return;
      const box = document.getElementById('modalBox');
      box.innerHTML = \`
        <div class="title" style="font-size:18px;">Unlock FocusLock Pro</div>
        <div class="subtitle">\${featureName ? featureName + ' is part of FocusLock Pro.<br>' : ''}One-time unlock — no subscriptions, no accounts, 100% offline.</div>
        <div class="subtitle" style="color:var(--text); line-height:1.6;">
          • Sanctuary & Kiosk unbreakable focus modes<br>
          • Automatic Bedtime screen dimming & sleep guard<br>
          • Automatic Wi-Fi & GPS Place rules<br>
          • YouTube Silhouette Modesty Blur & Downloader<br>
          • Earn Mode tasks & time-bank rewards
        </div>
        <div class="modal-actions">
          <button class="ghost-btn" onclick="closeModal()">Not now</button>
          <button class="download-btn" onclick="state.isPro=true; closeModal(); render();">Unlock Pro Now</button>
        </div>
      \`;
      document.getElementById('modalBackdrop').classList.add('open');
    }

    let safeTubeState = {
      silhouetteBlur: false,
      activeChannel: 'Khan Academy',
      downloadedCount: 1,
      channels: ['Khan Academy', 'freeCodeCamp', 'MIT OCW', '3Blue1Brown', 'CrashCourse', 'Stanford Online']
    };

    function toggleSilhouetteBlur() {
      if (!state.isPro && !safeTubeState.silhouetteBlur) {
        requirePro('YouTube Silhouette Modesty Blur');
        return;
      }
      safeTubeState.silhouetteBlur = !safeTubeState.silhouetteBlur;
      openSafeYouTubeModal();
    }

    function testDownload() {
      if (!state.isPro) {
        requirePro('Offline YouTube Downloader');
        return;
      }
      safeTubeState.downloadedCount++;
      alert('Downloaded "Linear Algebra Lecture 1" to local offline storage! Available without internet.');
      openSafeYouTubeModal();
    }

    function openSafeYouTubeModal() {
      const box = document.getElementById('modalBox');
      const isBlurred = safeTubeState.silhouetteBlur;
      box.innerHTML = \`
        <div class="row" style="margin-bottom:8px;">
          <div>
            <div class="title" style="font-size:18px;">Safe YouTube Player</div>
            <div class="subtitle" style="margin:2px 0 0;">Distraction-free sandbox · Zero Shorts · No rabbit holes</div>
          </div>
          <span class="badge success">Strict Mode</span>
        </div>

        <!-- Video Player Frame Simulation -->
        <div style="position:relative; width:100%; aspect-ratio:16/9; background:#000; border-radius:12px; overflow:hidden; border:1px solid var(--divider); margin-bottom:12px; display:flex; flex-direction:column; justify-content:flex-end;">
          <!-- Simulated whiteboard presentation background -->
          <div style="position:absolute; inset:0; background:linear-gradient(135deg, #111827 0%, #1e293b 100%); display:flex; flex-direction:column; padding:14px;">
            <div style="font-family:monospace; color:#38bdf8; font-size:11px; margin-bottom:6px;">// Discrete Mathematics & Logic</div>
            <div style="color:#f8fafc; font-size:14px; font-weight:700;">Theorem: P ∧ (Q ∨ R) ≡ (P ∧ Q) ∨ (P ∧ R)</div>
            <div style="color:#94a3b8; font-size:11px; margin-top:4px;">Truth table & distribution property visualization</div>
          </div>

          <!-- Human figure SVG silhouette -->
          <div style="position:absolute; right:20px; bottom:0; width:90px; height:120px; transition:filter 0.3s ease; \${isBlurred ? 'filter: blur(14px) contrast(150%); opacity:0.85;' : ''}">
            <svg viewBox="0 0 100 140" fill="#334155" style="width:100%; height:100%;">
              <circle cx="50" cy="30" r="18" fill="#475569" />
              <path d="M15,140 C15,85 30,70 50,70 C70,70 85,85 85,140 Z" fill="#475569" />
            </svg>
          </div>

          \${isBlurred ? \`
            <div style="position:absolute; top:8px; right:8px; background:rgba(52,211,153,0.9); color:#000; font-size:10px; font-weight:700; padding:3px 8px; border-radius:6px;">
              Silhouette Blurred (Slides Clear)
            </div>
          \` : ''}

          <div style="position:relative; z-index:2; padding:8px 12px; background:rgba(0,0,0,0.65); backdrop-filter:blur(4px); display:flex; justify-content:space-between; align-items:center;">
            <div style="font-size:11px; color:#fff; font-weight:600;">\${safeTubeState.activeChannel} · Lecture 04</div>
            <button class="ghost-btn" style="padding:4px 8px; font-size:11px;" onclick="testDownload()">
              📥 \${state.isPro ? 'Download (Offline)' : 'Download (Pro)'}
            </button>
          </div>
        </div>

        <!-- Privacy & Modesty Silhouette Blur Control -->
        <div class="card" style="margin-bottom:12px; padding:12px;">
          <div class="row">
            <div>
              <div class="row-title" style="font-size:13px;">Silhouette Modesty Auto-Blur</div>
              <div class="subtitle" style="font-size:11px;">Blurs human figures; whiteboards & slides stay sharp</div>
            </div>
            <button class="ghost-btn" style="font-weight:700; color:\${isBlurred ? 'var(--success)' : 'var(--muted)'};" onclick="toggleSilhouetteBlur()">
              \${isBlurred ? 'Active ON ✓' : (state.isPro ? 'Turn ON' : 'Turn ON (Pro)')}
            </button>
          </div>
        </div>

        <!-- Approved channels list -->
        <div class="section-label" style="margin-bottom:6px;">Approved Channels (Strict Allowlist)</div>
        <div style="display:flex; flex-wrap:wrap; gap:6px; margin-bottom:12px;">
          \${safeTubeState.channels.map(ch => \`
            <button class="seg \${safeTubeState.activeChannel === ch ? 'active' : ''}" style="font-size:11px; padding:5px 9px;" onclick="safeTubeState.activeChannel='\${ch}'; openSafeYouTubeModal();">
              \${ch}
            </button>
          \`).join('')}
        </div>

        <div class="modal-actions">
          <button class="download-btn" onclick="closeModal()">Done</button>
        </div>
      \`;
      document.getElementById('modalBackdrop').classList.add('open');
    }

    function openA11yDisclosure() {
      const box = document.getElementById('modalBox');
      box.innerHTML = \`
        <div class="title" style="font-size:18px;">Accessibility Service Disclosure</div>
        <div class="subtitle" style="color:var(--text); line-height:1.55;">
          FocusLock uses the Android AccessibilityService API solely to read on-screen text and window titles so it can detect when you enter distracting surfaces you chose to block (such as Shorts, Reels, WhatsApp Channels, blocked websites, or your own keywords) and step back out.<br><br>
          • All screen text is checked locally in memory and is never stored, logged, or transmitted off your device.<br>
          • FocusLock never modifies your settings or taps controls without your permission.
        </div>
        <div class="modal-actions">
          <button class="ghost-btn" onclick="closeModal()">Not now</button>
          <button class="download-btn" onclick="state.a11yGranted=true; closeModal(); render();">Agree & Open Settings</button>
        </div>
      \`;
      document.getElementById('modalBackdrop').classList.add('open');
    }

    function openOnboardingModal() {
      const box = document.getElementById('modalBox');
      box.innerHTML = \`
        <div class="title" style="font-size:18px;">Streamlined 3-Step Onboarding</div>
        <div class="subtitle" style="color:var(--text); line-height:1.6;">
          <strong>Step 1:</strong> Pick Distractions (Shorts, Social, Games, Browsing) & Essential Apps<br>
          <strong>Step 2:</strong> Grant Core Permissions inline (Usage Access + Display Over Other Apps)<br>
          <strong>Step 3:</strong> Review & Apply Setup
        </div>
        <div class="modal-actions">
          <button class="download-btn" onclick="closeModal()">Got it</button>
        </div>
      \`;
      document.getElementById('modalBackdrop').classList.add('open');
    }

    function closeModal() {
      document.getElementById('modalBackdrop').classList.remove('open');
    }

    render();
  </script>
</body>
</html>`;

const server = http.createServer((req, res) => {
  if (req.url === '/download/app-debug.apk') {
    const apk = findApk();
    if (!apk) {
      res.writeHead(404, { 'Content-Type': 'text/plain' });
      res.end('APK not found');
      return;
    }
    const stat = fs.statSync(apk);
    res.writeHead(200, {
      'Content-Type': 'application/vnd.android.package-archive',
      'Content-Disposition': 'attachment; filename="FocusLock-debug.apk"',
      'Content-Length': stat.size
    });
    fs.createReadStream(apk).pipe(res);
    return;
  }

  res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
  res.end(HTML);
});

server.listen(PORT, '0.0.0.0', () => {
  console.log('FocusLock preview server listening on port ' + PORT);
});
