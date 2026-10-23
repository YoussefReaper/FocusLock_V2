# Safe YouTube Player with AI Person Silhouette Blur & Offline Downloader

A distraction-free, algorithmic-free YouTube environment integrated directly into FocusLock's **Safe Tools** (`LibraryTab` & `MainActivity`), enforcing strict channel/playlist allowlists, complete Shorts blocking, real-time on-device human silhouette blurring (preserving tutorials, slides, and background while blurring people), and an offline video downloader.

---

## User Review & Critical Decisions

> [!IMPORTANT]
> The following specifications synthesize your confirmed answers from Phase 1:
> - **Content Filtering Strategy**: Allowed videos are governed strictly by user-approved channels, playlists, and safe search queries. YouTube Home feed, Trending, Shorts, and algorithmic recommendation rabbit holes are completely stripped.
> - **Modesty & Visual Protection**: Auto-blur specifically targets the **silhouette of people** (not the full video frame), so coding tutorials, whiteboards, presentation slides, animations, and non-human video content remain completely sharp and legible while people are blurred.
> - **Commercial Tier Structure**:
>   - **Free Tier**: Safe YouTube Player with channel/playlist allowlist, distraction-free player, safe search, and zero Shorts/comments.
>   - **FocusLock Pro**: On-device AI Person Silhouette Auto-Blur + Offline Video Downloader.

---

## 1. Overview & Core Concept

### What It Does
FocusLock replaces the infinite dopamine loop of the native YouTube app with a focused, utility-first **Safe YouTube** surface:
1. **Zero Algorithm & Anti-Rabbit Hole**: Users can only watch videos from channels or playlists they explicitly approve, or through safe search. Home recommendations, "Up Next" autoplay rabbit holes, Shorts carousels, and comments are blocked at both the network request and DOM injection layers.
2. **On-Device Human Silhouette Blur ("Gaze Guard")**: Rather than blurring the entire video (which renders tutorials, coding walkthroughs, or lectures unwatchable), an on-device computer vision segmentation model creates a real-time mask specifically over human bodies and faces. The educational or background content stays crisp while human figures are blurred.
3. **Offline Safe Video Downloader**: Allows users to download permitted educational videos or audio lectures directly to local storage to study completely offline without any internet connection.
4. **Complete Shorts Eradication**: Both in the native environment (`ContentGuardService` Accessibility rule) and inside the Safe YouTube player, `/shorts/` navigation is immediately terminated and redirected.

### Target Audience & Persona
- Students and professionals who need YouTube for lectures, tutorials, documentation, and lectures without being hijacked by algorithmic recommendations, addictive Shorts, or modesty concerns.
- Consumers seeking an ethical, respectful browsing tool that filters unwanted visual distractions and prevents gaze fatigue.

---

## 2. User Experience & Visual Design

### Key User Flows

#### A. Safe YouTube Discovery & Channel Management (`SafeYouTubeActivity`)
- User opens **Safe Tools** from the Focus dashboard or taps the **Safe YouTube** card in `LibraryTab`.
- **Allowed Channels & Playlists Bar**: Clean horizontal chip row showing subscribed/approved educational channels (e.g., *MIT OpenCourseWare*, *freeCodeCamp*, *3Blue1Brown*, or user's custom channels).
- **Curated Search Bar**: Tapping search allows finding videos only within allowed channels or safe educational topics.
- **Add Channel / Playlist Dialog**: Simple URL or handle input (`@channelname` or playlist link) to add a channel to the family/user allowlist.

#### B. Distraction-Free Playback (`SafeVideoPlayerActivity` & WebView Shield)
- **Zero Distractions**: Only the selected video player and its description/chapters are rendered. No recommended videos sidebar, no comments section, no infinite scroll.
- **Silhouette Blur Controls**:
  - Live indicator: `Silhouette Blur Active (Pro)`.
  - Blur intensity slider (Soft Blur / Medium / Heavy Silhouette Mask).
  - Mode toggle: *Silhouette Blur* vs. *Audio/Lecture Only* vs. *Standard*.
- **Offline Download Button (Pro)**: Tapping "Download for Offline" extracts the video/audio stream and saves it into FocusLock's safe offline library with background download progress.

#### C. Modesty Silhouette Blur Visual Feedback
- Offscreen segmentation model extracts the person mask at 15–20 fps.
- SVG/Canvas filter applies a gaussian blur specifically within the silhouette boundaries:
  - Slide text, code editors, and graphics outside the silhouette remain 100% crisp.
  - People's figures and faces are smoothly masked and blurred in real time.
- Thumbnails in search and channel lists automatically have human silhouettes blurred before rendering.

---

## 3. Key Product Decisions & Trade-Offs

### Decision 1: Dedicated Safe Web Player vs. Modifying Official YouTube App
- **Chosen Approach**: Build a custom dedicated Safe YouTube player (`SafeYouTubeActivity`) inside FocusLock using Android `WebView` with injected JS/CSS filtering and computer vision silhouette masking, while blocking the official YouTube app in `AppRules`.
- **Why**: Android's secure `SurfaceView` and DRM hardware decoders strictly prohibit third-party apps from reading or modifying pixel frames of another app. Running YouTube inside FocusLock's own sandboxed player gives 100% control over URL requests, DOM elements (stripping Shorts/comments/recommendations), and frame pixel access for silhouette blurring.

### Decision 2: Silhouette Segmentation vs. Full Frame Blurring
- **Chosen Approach**: Use lightweight on-device person segmentation (MediaPipe / BodyPix WebAssembly/WebGL) to compute a binary person mask, composited via Canvas with CSS backdrop blur or offscreen destination-in mask.
- **Why**: Full-frame blur ruins programming tutorials, slide decks, math walkthroughs, and nature documentaries. Silhouette masking preserves 95% of the educational screen area while achieving the user's modesty requirements.

### Decision 3: Offline Video Downloader Architecture
- **Chosen Approach**: Use an in-app client-side stream resolver that fetches direct media streams (`.mp4` / `.m4a`) to app-scoped storage (`getExternalFilesDir()`) with Android `DownloadManager` support.
- **Why**: Keeps downloaded content 100% offline and distraction-free, allowing users to study during hard lockdown sessions without needing any web browsing enabled.

---

## 4. Technical Architecture & Data Strategy

### System Component Diagram

```
┌────────────────────────────────────────────────────────────────────────┐
│                        FocusLock App Ecosystem                         │
│                                                                        │
│  ┌───────────────────────┐         ┌────────────────────────────────┐  │
│  │   Focus Dashboard     │         │       LibraryTab (Safe Tools)  │  │
│  │   Quick-Access Card   │────────>│   • Safe YouTube Launcher      │  │
│  └───────────────────────┘         │   • Offline Downloads Manager  │  │
│                                    └───────────────┬────────────────┘  │
│                                                    │                   │
│                                                    ▼                   │
│  ┌──────────────────────────────────────────────────────────────────┐  │
│  │              SafeYouTubeActivity (WebView Engine)                │  │
│  │                                                                  │  │
│  │  ┌───────────────────────┐           ┌────────────────────────┐  │  │
│  │  │   SafeTubeStore       │           │ Request Interceptor    │  │  │
│  │  │   • Allowed Channels  │           │ • Blocks /shorts/*     │  │  │
│  │  │   • Allowed Playlists │           │ • Blocks ads/trackers  │  │  │
│  │  │   • Safe Search Terms │           │ • Blocks recommendations│  │
│  │  └───────────┬───────────┘           └───────────┬────────────┘  │  │
│  │              │                                   │               │  │
│  │              ▼                                   ▼               │  │
│  │  ┌────────────────────────────────────────────────────────────┐  │  │
│  │  │                Injected JavaScript Bridge                  │  │  │
│  │  │  • DOM Stripper (Removes Home, Shorts, Comments, Next)     │  │  │
│  │  │  • Real-Time Silhouette Segmentation Engine (BodyPix/TFJS) │  │  │
│  │  │  • Canvas Blur Overlay for Person Silhouettes              │  │  │
│  │  └────────────────────────────────────────────────────────────┘  │  │
│  └──────────────────────────────────┬───────────────────────────────┘  │
│                                     │ (Pro Feature)                    │
│                                     ▼                                  │
│  ┌──────────────────────────────────────────────────────────────────┐  │
│  │            YouTubeDownloader & Offline Library                   │  │
│  │  • Stream Extractor -> App-Scoped Storage (.mp4 / .m4a)          │  │
│  │  • Local Media Player for Offline Study Sessions                 │  │
│  └──────────────────────────────────────────────────────────────────┘  │
└────────────────────────────────────────────────────────────────────────┘
```

### Data Storage & Model Schema

- **`SafeTubeStore.kt`**:
  - `allowedChannels: List<SafeChannel>` (`id`, `title`, `handle`, `avatarUrl`)
  - `allowedPlaylists: List<SafePlaylist>` (`id`, `title`, `channelTitle`)
  - `offlineDownloads: List<DownloadedVideo>` (`id`, `title`, `filePath`, `durationMs`, `fileSizeBytes`)
  - `silhouetteBlurEnabled: Boolean` (Pro)
  - `blurIntensity: Int` (1–10)
  - `shortsBlocked: Boolean` (default `true`)

### Implementation Steps

1. **`SafeTubeStore.kt` & Capability Definition**:
   - Add `Capabilities.SAFE_YOUTUBE` and `Capabilities.YOUTUBE_MODESTY_BLUR`.
   - Store allowed channels, playlists, search filters, and downloaded videos in persistent local JSON.
2. **`SafeYouTubeActivity.kt`**:
   - Modern, distraction-free player interface with channel picker, playlist queue, and search.
   - Robust `WebViewClient` interceptor blocking all `/shorts/`, recommendation APIs, and tracking URLs.
3. **Silhouette Blur Script (`assets/safetube/silhouette_blur.js`)**:
   - Extracts frames from `<video>` and thumbnails.
   - Calculates human body segmentation mask and renders canvas blur over detected silhouettes.
4. **Offline Downloader (`YouTubeDownloader.kt`)**:
   - Stream extractor with background progress notification, saving files to safe local storage.
5. **UI & Navigation Integration**:
   - Add Safe YouTube card to `LibraryTab` and Focus tab Safe Tools dialog.
   - Add FocusLock Pro gate for Silhouette Auto-Blur and Offline Video Downloader via `DemoMode.requirePro`.
6. **Verification**:
   - Build and verify with `compile_applet`.
   - Update port 3000 preview server with an interactive demo of Safe YouTube, silhouette blur toggle, and download flow.
