package com.focuslock.mdm

/**
 * Identifiers, storage keys and seed data.
 *
 * What is deliberately NOT here any more: policy. The allow list, the kill list
 * and the WhatsApp phrases used to live in this file and were read directly by
 * the enforcement loop, which meant the app's behaviour was a constant the user
 * could not touch. They are now migration seeds only, copied once into the
 * user-owned stores by [Migration] and never consulted again. Live policy comes
 * from [AppRules], [KeywordRules], [RuleStore] and [CapabilityRegistry].
 */
object Constants {

    const val OWN_PACKAGE = BuildConfig.APPLICATION_ID

    // ─────────────────────────────────────────────────────────────
    //  LEGACY SEEDS - read exactly once, by Migration
    // ─────────────────────────────────────────────────────────────

    /**
     * The old hardcoded allow list. Becomes "open freely" for each entry.
     *
     * A set, so repeats would collapse anyway - and the ones that used to be in
     * here twice ("com.whatsapp"/"com.whatsapp.w4b", plus three of the safety
     * guardrails) are listed once. The literal `com.focuslock.mdm` entry is
     * gone: [OWN_PACKAGE] above already covers it, and a hardcoded copy is the
     * one line in this file that would silently mean the wrong package in the
     * demo build, whose application id is `com.focuslock.mdm.demo`.
     *
     * The overlap with [LEGACY_KILL_LIST] is resolved in the user's favour by
     * [Migration]: anything on this list is dropped from the kill list before a
     * single rule is written.
     */
    val LEGACY_WHITELIST: Set<String> = setOf(
        OWN_PACKAGE,

        // ── Communication ──────────────────────────────────────
        "com.whatsapp",
        "com.whatsapp.w4b",
        "com.android.phone",
        "com.google.android.dialer",
        "com.android.server.telecom",
        "com.google.android.contacts",
        "com.android.providers.contacts",
        "com.google.android.apps.messaging",

        // ── Camera & Gallery ───────────────────────────────────
        "com.android.camera",
        "com.miui.gallery",
        "com.google.android.apps.photos",

        // ── Islamic Apps ───────────────────────────────────────
        // --- SAFETY GUARDRAILS (DO NOT REMOVE) ---
        "com.google.android.inputmethod.latin", // Gboard
        "com.facemoji.keyboard.xiaomi",         // Xiaomi Keyboard
        "com.google.android.gms",               // Google Play Services

        // --- YOUR SOVEREIGN SPRINT ARSENAL ---
        "advanced.scientific.calculator.calc991.plus",
        "ch.protonvpn.android",
        "com.android.soundrecorder",
        "com.andron.crosswords2",
        "com.ayah",
        "com.blink22.fajr",
        "com.chess",
        "com.dragonnest.drawnote",
        "com.duokan.phone.remotecontroller",
        "com.duolingo",
        "com.emeint.android.myservices",
        "com.fiverr.fiverr",
        "com.fyxtech.muslim",
        "com.gameloft.android.ANMP.GloftDOHM",
        "com.google.android.apps.bard",
        "com.google.android.apps.classroom",
        "com.google.android.keep",
        "com.gymstreaklabs.GymLevels",
        "com.harmonynetwork.singsharp",
        "com.hybrid.stopwatch",
        "com.ludia.jurassicworld",
        "com.microsoft.office.outlook",
        "com.mmmoussa.iqra",
        "com.oceanwing.soundcore",
        "com.openai.chatgpt",
        "com.paypal.android.p2pmobile",
        "com.simonandschuster.pimsleur.unified.android",
        "com.ticktick.task",
        "com.truecaller",
        "com.udemy.android",
        "com.upwork.android.apps.main",
        "com.xiaomi.scanner",
        "com.zytoona.lostwords2",
        "com.zytoona.wordscrush",
        "devs.sasikanth.pinnit2",
        "droom.sleepIfUCan",
        "homeworkout.homeworkouts.noequipment",
        "jawline.exercises.slim.face.yoga",
        "musicplayer.musicapps.music.mp3player",
        "net.countrymania.morse",
        "net.froemling.bombsquad",
        "no.mobitroll.kahoot.android",
        "org.codeaurora.snapcam",
        "org.hamotunnelplus.xyz",
        "org.khanacademy.android",
        "org.lichess.mobileapp",
        "us.zoom.videomeetings",

        // ── Xiaomi Home & System UI ────────────────────────────
        "com.miui.home",
        "com.android.systemui",
        "com.google.android.inputmethod.latin", // The Keyboard
        "com.facemoji.keyboard.xiaomi", // Xiaomi/Facemoji Keyboard (back-up for your device)
        "com.google.android.gms", // Google Play Services (vital core)
        "authenticator.two.factor.authentication.otp",
        "com.vedasapps.flashcards",
        "com.miui.aod",
        "com.adobe.reader",
        "com.ichi2.anki"
    )

    /**
     * The old hardcoded kill list. Becomes "blocked" for each entry.
     *
     * `com.google.android.apps.bard` is deliberately not repeated here: it is on
     * [LEGACY_WHITELIST], the allowlist wins in [Migration], and a package
     * listed on both sides is a contradiction waiting to be read the wrong way
     * by whoever changes the precedence later.
     */
    val LEGACY_KILL_LIST: List<String> = listOf(
        "com.android.vending",
        "com.android.chrome",
        "com.google.android.youtube",
        "com.miui.browser",
        "com.mi.globalbrowser",
        "com.xiaomi.mipicks",
        "com.miui.videoplayer",
        "com.miui.player",
        "com.reddit.frontpage",
        "com.facebook.katana",
        "com.facebook.orca",
        "com.google.android.apps.youtube.music",
        "com.pinterest",
        "com.discord",
        "com.einnovation.temu",
        "com.amazon.mShop.android.shopping",
        "com.microsoft.copilot",
        "com.ubercab",
        "com.talabat",
        "org.mozilla.firefox",
        "com.brave.browser",
        "com.opera.browser",
        "org.torproject.torbrowser",
    )


    // ─────────────────────────────────────────────────────────────
    //  WEB LINK SEED
    //
    //  Copied into AllowlistStore on first run and editable from there.
    //  The safe browser only loads URLs whose host matches an entry the user
    //  currently has in their own list.
    // ─────────────────────────────────────────────────────────────
    data class WebLink(val title: String, val url: String, val category: String = "")

    val WEB_LINKS: List<WebLink> = listOf(

        // ── Search ───────────────────────────────────────────
        WebLink("Google Search", "https://www.google.com",      "Search"),

        // ── AI & Research ──────────────────────────────────────
        WebLink("ChatGPT",       "https://chatgpt.com",           "AI"),
        WebLink("ChatGPT (alt)", "https://chat.openai.com",       "AI"),
        WebLink("Perplexity",    "https://perplexity.ai",         "AI"),
        WebLink("DeepSeek",      "https://chat.deepseek.com",     "AI"),
        WebLink("You.com",       "https://you.com",               "AI"),
        WebLink("WolframAlpha",  "https://wolframalpha.com",      "AI"),

        // ── Dev Tools & Docs ───────────────────────────────────
        WebLink("GitHub",        "https://github.com",            "Dev"),
        WebLink("GitHub Edu",    "https://education.github.com",  "Dev"),
        WebLink("Bitbucket",     "https://bitbucket.org",         "Dev"),
        WebLink("GitKraken",     "https://gitkraken.com",         "Dev"),
        WebLink("VS Code Web",   "https://vscode.dev",            "Dev"),
        WebLink("Replit",        "https://replit.com",            "Dev"),
        WebLink("CodePen",       "https://codepen.io",            "Dev"),
        WebLink("JSFiddle",      "https://jsfiddle.net",          "Dev"),
        WebLink("Postman",       "https://postman.com",           "Dev"),
        WebLink("RapidAPI",      "https://rapidapi.com",          "Dev"),
        WebLink("Regex101",      "https://regex101.com",          "Dev"),
        WebLink("CanIUse",       "https://caniuse.com",           "Dev"),
        WebLink("DevDocs",       "https://devdocs.io",            "Dev"),
        WebLink("DevHints",      "https://devhints.io",           "Dev"),
        WebLink("OverAPI",       "https://overapi.com",           "Dev"),

        // ── Official Docs ──────────────────────────────────────
        WebLink("MDN",           "https://developer.mozilla.org", "Docs"),
        WebLink("React Docs",    "https://react.dev",             "Docs"),
        WebLink("Python Docs",   "https://docs.python.org",       "Docs"),
        WebLink("Node.js",       "https://nodejs.org",            "Docs"),
        WebLink("AWS Docs",      "https://docs.aws.amazon.com",   "Docs"),
        WebLink("Docker Docs",   "https://docs.docker.com",       "Docs"),
        WebLink("Google Dev",    "https://developers.google.com", "Docs"),
        WebLink("CSS-Tricks",    "https://css-tricks.com",        "Docs"),
        WebLink("W3Schools",     "https://w3schools.com",         "Docs"),
        WebLink("GeeksForGeeks", "https://geeksforgeeks.org",     "Docs"),
        WebLink("Stack Overflow","https://stackoverflow.com",     "Docs"),
        WebLink("Stack Exchange","https://stackexchange.com",     "Docs"),

        // ── Cloud & Infrastructure ─────────────────────────────
        WebLink("Firebase",      "https://console.firebase.google.com", "Cloud"),
        WebLink("Azure Portal",  "https://portal.azure.com",     "Cloud"),
        WebLink("Azure Docs",    "https://azure.microsoft.com",  "Cloud"),
        WebLink("DigitalOcean",  "https://cloud.digitalocean.com","Cloud"),
        WebLink("MongoDB",       "https://account.mongodb.com",  "Cloud"),
        WebLink("Railway",       "https://railway.com",          "Cloud"),
        WebLink("Heroku",        "https://heroku.com",           "Cloud"),
        WebLink("Aternos",       "https://aternos.org",          "Cloud"),
        WebLink("Stripe",        "https://stripe.com",           "Cloud"),

        // ── Learning ───────────────────────────────────────────
        WebLink("Khan Academy",  "https://khanacademy.org",      "Learn"),
        WebLink("freeCodeCamp",  "https://freecodecamp.org",     "Learn"),
        WebLink("The Odin Project","https://theodinproject.com", "Learn"),
        WebLink("Codecademy",    "https://codecademy.com",       "Learn"),
        WebLink("Codedex",       "https://codedex.io",           "Learn"),
        WebLink("Scrimba",       "https://scrimba.com",          "Learn"),
        WebLink("Frontend Masters","https://frontendmasters.com","Learn"),
        WebLink("Educative",     "https://educative.io",         "Learn"),
        WebLink("edX",           "https://edx.org",              "Learn"),
        WebLink("Coursera",      "https://coursera.com",         "Learn"),
        WebLink("DataCamp",      "https://datacamp.com",         "Learn"),
        WebLink("Udemy",         "https://udemy.com",            "Learn"),
        WebLink("Almentor",      "https://almentor.net",         "Learn"),
        WebLink("Project Euler", "https://projecteuler.net",     "Learn"),
        WebLink("Codeforces",    "https://codeforces.com",       "Learn"),
        WebLink("Typing Club",   "https://typingclub.com",       "Learn"),
        WebLink("Typing.com",    "https://typing.com",           "Learn"),

        // ── Cybersecurity ──────────────────────────────────────
        WebLink("TryHackMe",     "https://tryhackme.com",        "Security"),
        WebLink("HackTheBox",    "https://hackthebox.com",       "Security"),
        WebLink("OverTheWire",   "https://overthewire.org",      "Security"),
        WebLink("Kali Linux",    "https://kali.org",             "Security"),
        WebLink("SecurityTrails","https://securitytrails.com",   "Security"),
        WebLink("Wappalyzer",    "https://wappalyzer.com",       "Security"),

        // ── Hack Club ──────────────────────────────────────────
        WebLink("Hack Club",     "https://hackclub.com",         "HackClub"),
        WebLink("Hack Club Dev", "https://hackclub.dev",         "HackClub"),
        WebLink("Hack Club IO",  "https://hackclub.io",          "HackClub"),
        WebLink("Hack Club Org", "https://hackclub.org",         "HackClub"),
        WebLink("Hackatime",     "https://hackatime.hackclub.com","HackClub"),

        // ── Work / Freelance ──────────────────────────────────
        WebLink("Upwork",        "https://upwork.com",           "Work"),
        WebLink("Fiverr",        "https://fiverr.com",           "Work"),
        WebLink("Freelancer",    "https://freelancer.com",       "Work"),
        WebLink("Toptal",        "https://toptal.com",           "Work"),
        WebLink("Topcoder",      "https://topcoder.com",         "Work"),
        WebLink("PeoplePerHour", "https://peopleperhour.com",    "Work"),
        WebLink("Guru",          "https://guru.com",             "Work"),
        WebLink("Flexiple",      "https://flexiple.com",         "Work"),
        WebLink("Gun.io",        "https://gun.io",               "Work"),
        WebLink("Arc.dev",       "https://arc.dev",              "Work"),
        WebLink("RemoteOK",      "https://remoteok.com",         "Work"),
        WebLink("Lemon.io",      "https://lemon.io",             "Work"),
        WebLink("Kolabtree",     "https://kolabtree.com",        "Work"),
        WebLink("Outlier AI",    "https://app.outlier.ai",       "Work"),
        WebLink("LinkedIn",      "https://linkedin.com",         "Work"),
        WebLink("Clockify",      "https://clockify.me",          "Work"),
        WebLink("Invoicely",     "https://invoicely.com",        "Work"),
        WebLink("Payoneer",      "https://payoneer.com",         "Work"),
        WebLink("PayPal",        "https://paypal.com",           "Work"),
        WebLink("Shopify",       "https://shopify.com",          "Work"),

        // ── Google Workspace ───────────────────────────────────
        WebLink("Gmail",         "https://mail.google.com",      "Google"),
        WebLink("Drive",         "https://drive.google.com",     "Google"),
        WebLink("Docs",          "https://docs.google.com",      "Google"),
        WebLink("Meet",          "https://meet.google.com",      "Google"),
        WebLink("Classroom",     "https://classroom.google.com", "Google"),
        WebLink("Keep",          "https://keep.google.com",      "Google"),
        WebLink("Forms",         "https://forms.google.com",     "Google"),
        WebLink("Maps",          "https://maps.google.com",      "Google"),
        WebLink("Scholar",       "https://scholar.google.com",   "Google"),
        WebLink("Translate",     "https://translate.google.com", "Google"),
        WebLink("Google APIs",   "https://apis.google.com",      "Google"),

        // ── Microsoft ──────────────────────────────────────────
        WebLink("Outlook",       "https://outlook.office.com",   "Microsoft"),
        WebLink("MS Account",    "https://account.microsoft.com","Microsoft"),
        WebLink("MS Forms",      "https://forms.office.com",     "Microsoft"),
        WebLink("DeepL",         "https://deepl.com",            "Microsoft"),

        // ── Design & Assets ────────────────────────────────────
        WebLink("Figma",         "https://figma.com",            "Design"),
        WebLink("Canva",         "https://canva.com",            "Design"),
        WebLink("Dribbble",      "https://dribbble.com",         "Design"),
        WebLink("Miro",          "https://miro.com",             "Design"),
        WebLink("MindMeister",   "https://mindmeister.com",      "Design"),
        WebLink("Stormboard",    "https://stormboard.com",       "Design"),
        WebLink("Flaticon",      "https://flaticon.com",         "Design"),
        WebLink("FontAwesome",   "https://fontawesome.com",      "Design"),
        WebLink("FontSpace",     "https://fontspace.com",        "Design"),
        WebLink("Coolors",       "https://coolors.co",           "Design"),
        WebLink("UI Gradients",  "https://uigradients.com",      "Design"),
        WebLink("Remove BG",     "https://remove.bg",            "Design"),
        WebLink("TinyPNG",       "https://tinypng.com",          "Design"),
        WebLink("iLovePDF",      "https://ilovepdf.com",         "Design"),

        // ── Productivity ───────────────────────────────────────
        WebLink("Notion",        "https://notion.so",            "Productivity"),
        WebLink("Anki",          "https://apps.ankiweb.net",     "Productivity"),
        WebLink("Pomodoro",      "https://pomofocus.io",         "Productivity"),
        WebLink("Spotify",       "https://open.spotify.com",     "Productivity"),
        WebLink("ResearchGate",  "https://researchgate.net",     "Productivity"),
        WebLink("Zoom",          "https://us.zoom.videomeetings","Productivity"),
        WebLink("Cloudflare DNS","https://1.1.1.1",              "Productivity"),
        WebLink("Godot Engine",  "https://godotengine.org",      "Productivity"),

        // ── Domain & Hosting ──────────────────────────────────
        WebLink("Namecheap",     "https://namecheap.com",        "Domain"),
        WebLink("Name.com",      "https://name.com",             "Domain"),
        WebLink("NC.me",         "https://nc.me",                "Domain"),

        // ── Auth & Accounts ────────────────────────────────────
        WebLink("Google Account","https://accounts.google.com",  "Auth"),
        WebLink("Amazon",        "https://amazon.com",           "Auth"),

        // ── Localhost / Dev Server ─────────────────────────────
        // These only work when your dev server is running via USB
        WebLink("Localhost",     "http://localhost",             "Local"),
        WebLink("Local :3000",   "http://127.0.0.1:3000",       "Local"),
        WebLink("Local :5000",   "http://127.0.0.1:5000",       "Local"),
        WebLink("Local :5500",   "http://127.0.0.1:5500",       "Local"),
        WebLink("Local :7860",   "http://127.0.0.1:7860",       "Local"),
        WebLink("Local :8501",   "http://127.0.0.1:8501",       "Local"),

        // ── Chess & Games ──────────────────────────────────────
        WebLink("Chess.com",     "https://chess.com",            "Games"),
        WebLink("Lichess",       "https://lichess.org",          "Games"),

        // ── Misc Tools ─────────────────────────────────────────
        WebLink("Aurocore",      "https://aurocore.me",          "Misc"),
        WebLink("Aurocore API",  "https://api.aurocore.me",      "Misc"),
        WebLink("Join Vital",    "https://join-vital.com",       "Misc"),
        WebLink("TestMail",      "https://testmail.app",         "Misc"),
        WebLink("NewPipe",       "https://newpipe.net",          "Misc"),
        WebLink("Gemini", "https://gemini.google.com", "AI"),
        WebLink("Line of Action", "https://line-of-action.com", "Art"),
        WebLink("Quickposes",     "https://quickposes.com",     "Art"),
        WebLink("Proko",          "https://proko.com",          "Art"),
        WebLink("SketchDaily",    "http://reference.sketchdaily.net", "Art"),
        WebLink("Blender Docs",    "https://docs.blender.org",   "3D"),
        WebLink("Blender Artists", "https://blenderartists.org", "3D"),
        WebLink("CG Cookie",       "https://cgcookie.com",       "3D"),
    )

    // ─────────────────────────────────────────────────────────────
    //  TIME
    // ─────────────────────────────────────────────────────────────

    const val LOCK_DURATION_DAYS = 90L

    // ─────────────────────────────────────────────────────────────
    //  STORAGE KEYS
    // ─────────────────────────────────────────────────────────────

    const val PREFS_MAIN       = "focuslock_main"
    const val KEY_INSTALL_TIME = "install_time"
    const val KEY_SECURITY_BASELINE_READY = "security_baseline_ready"

    const val KEY_KIOSK_ACTIVE      = "kiosk_active"
    const val KEY_KIOSK_START_MS    = "kiosk_start_ms"
    const val KEY_KIOSK_DURATION_MS = "kiosk_duration_ms"
    const val KEY_ADB_DISABLED      = "adb_disabled"
    const val KEY_SETTINGS_ALLOW_UNTIL_MS = "settings_allow_until_ms"

    const val KEY_APP_ALLOWLIST        = "app_allowlist"
    const val KEY_WEB_ALLOWLIST        = "web_allowlist"
    const val KEY_WEB_TEXT_ONLY        = "web_text_only"

    const val KEY_UI_THEME             = "ui_theme"
    const val KEY_UI_FONT              = "ui_font"
    const val KEY_UI_DENSITY           = "ui_density"
    const val KEY_UI_WALLPAPER         = "ui_wallpaper"
    const val KEY_UI_ACCENT            = "ui_accent"
    const val KEY_UI_BACKGROUND        = "ui_background"
    const val KEY_UI_CARD_RADIUS_DP    = "ui_card_radius_dp"
    const val KEY_UI_TEXT_SCALE        = "ui_text_scale"
    const val KEY_UI_REDUCED_MOTION    = "ui_reduced_motion"
    const val KEY_UI_HIGH_CONTRAST     = "ui_high_contrast"
    const val KEY_UI_SHOW_KIOSK        = "ui_show_kiosk"
    const val KEY_UI_SHOW_QUICK        = "ui_show_quick"
    const val KEY_UI_SHOW_ALLOWED_APPS = "ui_show_allowed_apps"
    const val KEY_UI_SHOW_WEB_BUTTON   = "ui_show_web_button"
    const val KEY_UI_SHOW_VIDEO        = "ui_show_video"
    const val KEY_UI_SHOW_EDIT_BUTTONS = "ui_show_edit_buttons"
    const val KEY_UI_SHOW_SCHEDULE     = "ui_show_schedule"
    const val KEY_UI_SHOW_STATS        = "ui_show_stats"

    const val KEY_SCHEDULE_JSON = "schedule_json"
    const val KEY_SCHEDULE_ALWAYS_ALLOWED_APPS = "schedule_always_allowed_apps"
    const val KEY_PLAN_TEXT     = "plan_text"

    const val KEY_ONBOARDING_DONE = "onboarding_done"
}
