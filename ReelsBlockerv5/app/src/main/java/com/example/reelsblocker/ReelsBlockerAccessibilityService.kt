package com.example.reelsblocker

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * ReelsBlockerAccessibilityService — v5
 *
 * WHY STORIES AND DM/PROFILE REELS KEPT GETTING BLOCKED ACROSS v3 AND v4
 * -------------------------------------------------------------------------
 * Two real bugs, not one. Both survived two straight rewrites because each
 * rewrite kept shuffling *which guessed id strings* lived in which bucket,
 * without touching the two things actually responsible:
 *
 *  BUG A — classifyScreen() checked "is this the Reel/Clips player?"
 *  BEFORE "is this a DM thread?" / "is this a Profile?". A DM thread shows
 *  a shared Reel as an inline video-preview bubble right inside the
 *  message list, and a Profile shows a grid of Reel thumbnails — both of
 *  which can trip the Reel-viewer id/class check while the user is still
 *  looking at the THREAD or the PROFILE GRID, not yet inside the
 *  full-screen player. Once that happens, the DM/Profile screen itself
 *  gets mis-tagged as the Reel viewer, `lastNonReelScreenType` never
 *  becomes DM_THREAD/PROFILE, and by the time the user taps into the real
 *  full-screen Reel, its origin reads as "unknown" — which this file (by
 *  design) blocks. v5 checks DM_THREAD and PROFILE first, specifically
 *  because their defining signals (an active message composer, a profile
 *  header/bio) are far more exclusive to "still looking at the
 *  thread/grid" than the Reel-viewer signals are to "definitely not a
 *  thread or grid".
 *
 *  BUG B — a genuine naming mix-up, not a bad guess. Instagram's Stories
 *  feature predates the Reels (short-video) feature, and internally
 *  Stories was named "Reel" (singular) for years before "Reels" (plural)
 *  shipped as a separate feature. v2 through v4 all treated generic
 *  "reel_viewer"/"ReelViewerFragment"-style identifiers as AMBIGUOUS
 *  between the two and deliberately avoided using them for Story
 *  detection — but that assumption was backwards. Those are Stories'
 *  actual internal names; the short-video Reels feature uses "clips_*"/
 *  "Clips*" naming instead. Every version up to v4 was avoiding the one
 *  signal that would have reliably identified a Story, out of a worry
 *  about a collision that doesn't exist. v5 corrects the whole taxonomy:
 *    • Story   → "reel_viewer*", "ReelViewerFragment", "reel_tag", "stor*"
 *    • Reels   → "clips_*", "Clips*", "reels_viewer*" (plural)
 *  These two sets are now disjoint substrings by construction (see the
 *  comment on REEL_CLASS_HINTS below for why "reelviewer" and
 *  "reelsviewer" can never both match the same string).
 *
 * WHAT ELSE CHANGED IN v5
 * -------------------------
 *  • Every classification decision now logs, at tag `ReelsBlockerA11y`,
 *    which category it landed on (STORY, DM_REEL, PROFILE_REEL, FEED_REEL,
 *    REEL_TAB, or a plain screen-type label) AND which specific id or
 *    className substring actually matched — so a real device run can be
 *    inspected with `adb logcat -s ReelsBlockerA11y` and any remaining
 *    wrong guess shows up directly instead of needing another round-trip.
 *  • All v4 try/catch crash-hardening is unchanged: every node/tree call
 *    still has its own try/catch, and nothing can propagate out of
 *    onAccessibilityEvent.
 *  • The Story gate is still the literal first thing onAccessibilityEvent
 *    does, before the event is even routed by type, and still fails
 *    TOWARD "treat as Story" if classification itself throws.
 *
 * RULES IMPLEMENTED (unchanged intent, every version)
 * ------------------------------------------------------
 *  1. Bottom-nav Reels tab tap             -> BACK, immediately.
 *  2. Reel opened from the main/home feed  -> BACK, immediately.
 *  3. Reel opened from a user profile      -> ALLOWED (first Reel only).
 *  4. Reel opened from Direct Messages     -> ALLOWED (first Reel only).
 *  5. A vertical swipe inside an allowed Reel (trying to reach the NEXT
 *     Reel)                                -> BACK, immediately, session
 *                                              locked down.
 *  6. Stories are 100% exempt — checked first, before anything else, on
 *     every single event, with zero side effects.
 */
class ReelsBlockerAccessibilityService : AccessibilityService() {

    private enum class ScreenType { REEL_VIEWER, DM_THREAD, PROFILE, OTHER }

    companion object {
        private const val TAG = "ReelsBlockerA11y"
        private const val INSTAGRAM_PACKAGE = "com.instagram.android"

        /** Minimum gap between two performGlobalAction(BACK) calls. */
        private const val BACK_ACTION_COOLDOWN_MS = 600L

        /**
         * True while the system is actively bound to this service. Read by
         * BlockerForegroundService's watchdog.
         */
        @Volatile
        var isConnected: Boolean = false
            private set

        // ── STORY signals ──────────────────────────────────────────────
        // Instagram's internal name for a Story has, for years, been
        // "Reel" (singular) — predating the separate short-video "Reels"
        // feature. These ids/classes are Stories' own, not a shared/
        // ambiguous container.
        private val STORY_EXCLUSIVE_IDS = setOf(
            "reel_viewer", "reel_viewer_image_view", "story_feed_holder",
            "reel_tag",
            // Additional Story-only UI elements (reply box, segmented
            // progress bar, tray) kept as redundant signals.
            "story_reply_composer", "reply_composer", "story_progress_bar",
            "story_duration_bar", "story_progress_container", "stories_tray",
            "story_viewer_swipe_container", "story_viewer_reply_box",
            "quick_reaction_emoji_picker", "stories_header",
            "story_viewer_fragment_container"
        )

        // "reelviewer" catches "ReelViewerFragment" (and any casing) without
        // also catching "ReelsViewerFragment" — see REEL_CLASS_HINTS below
        // for why these two never collide.
        private val STORY_CLASS_HINTS = listOf("reelviewer", "stor")

        // ── REEL/CLIPS PLAYER signals (the short-video feature) ────────
        // Instagram's internal naming for THIS feature is "clips_*" /
        // "Clips*" — distinct from the legacy singular "reel_viewer"
        // naming that actually belongs to Stories (see class doc comment).
        private val REEL_VIEWER_IDS = setOf(
            "clips_viewer_view_pager", "clips_swipe_refresh_container",
            "clips_video_container", "clips_fragment_root",
            "clips_caption_text_view", "clips_tab_fragment_container",
            // Plural "reels_viewer" (the new feature) — distinct string
            // from the singular "reel_viewer" (Stories) above.
            "reels_viewer_fragment_root",
            "ad_unit_root", "sponsored_reel_root"
        )

        // "reelsviewer" (plural, the new feature) can never match the same
        // string as "reelviewer" (singular, Stories) above: matching
        // "reelsviewer" requires an 's' between "reel" and "viewer" that
        // "reelviewer" by definition does not have, and vice versa.
        private val REEL_CLASS_HINTS = listOf("clips", "reelsviewer")

        // ── Bottom navigation bar's Reels/Clips tab button ──
        private val REELS_TAB_IDS = setOf(
            "clips_tab", "tab_icon_clips", "reels_tab", "tab_clips",
            "bottom_nav_clips", "bottom_navigation_clips"
        )

        // ── DM thread (conversation view) — ids. Checked BEFORE the Reel
        // viewer (see Bug A in the class doc comment).
        private val DM_THREAD_IDS = setOf(
            "direct_thread_fragment", "direct_thread_recycler_view",
            "thread_view_messages_recycler_view", "row_thread_composer_edittext",
            "direct_thread_toolbar", "direct_text_message_text_view",
            "direct_private_share", "direct_thread"
        )

        private val DM_CLASS_HINTS = listOf("direct")

        // ── Profile page — ids. Also checked BEFORE the Reel viewer.
        private val PROFILE_IDS = setOf(
            "profile_header", "profile_tab_layout", "action_bar_profile_name",
            "profile_coordinator_layout", "user_detail_header",
            "profile_header_bio_text"
        )

        private val PROFILE_CLASS_HINTS = listOf("profile")

        // Only structural tree changes can possibly mean a Reel viewer
        // just appeared or disappeared. A single integer AND against this
        // mask — no timer, no debounce.
        private val STRUCTURAL_CHANGE_MASK: Int = run {
            var mask = AccessibilityEvent.CONTENT_CHANGE_TYPE_SUBTREE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                mask = mask or AccessibilityEvent.CONTENT_CHANGE_TYPE_PANE_APPEARED
                mask = mask or AccessibilityEvent.CONTENT_CHANGE_TYPE_PANE_TITLE
            }
            mask
        }
    }

    // ── Mutable session state ──
    @Volatile private var lastNonReelScreenType: ScreenType = ScreenType.OTHER
    @Volatile private var inReelSession = false
    @Volatile private var reelOriginAllowed = false
    @Volatile private var lastBackActionMs = 0L

    // ─── Lifecycle ─────────────────────────────────────────────────────

    override fun onServiceConnected() {
        super.onServiceConnected()
        try {
            lastNonReelScreenType = ScreenType.OTHER
            inReelSession = false
            reelOriginAllowed = false
            isConnected = true
            Log.i(TAG, "onServiceConnected — fresh state, isConnected=true")
        } catch (e: Exception) {
            Log.e(TAG, "onServiceConnected failed", e)
        }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        isConnected = false
        Log.w(TAG, "onUnbind — isConnected=false")
        return try {
            super.onUnbind(intent)
        } catch (e: Exception) {
            Log.e(TAG, "super.onUnbind failed", e)
            false
        }
    }

    override fun onDestroy() {
        isConnected = false
        Log.w(TAG, "onDestroy — isConnected=false")
        try {
            super.onDestroy()
        } catch (e: Exception) {
            Log.e(TAG, "super.onDestroy failed", e)
        }
    }

    override fun onInterrupt() { /* no-op */ }

    // ─── Top-level event dispatch ───────────────────────────────────────
    //
    // Everything below runs inside ONE outer try/catch. No exception
    // thrown anywhere in this class is allowed to escape this method.

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        try {
            handleEvent(event)
        } catch (e: Exception) {
            Log.e(TAG, "Unhandled exception in onAccessibilityEvent — swallowed to protect the service", e)
        }
    }

    private fun handleEvent(event: AccessibilityEvent) {
        val pkg = try {
            event.packageName?.toString()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read event.packageName", e)
            null
        }
        if (pkg != INSTAGRAM_PACKAGE) return

        val root = safeRoot()

        // ── RULE 6, HIGHEST PRIORITY: Stories are 100% exempt. ──────────
        // Runs before the event is routed by type, for every single
        // event, with zero side effects if it matches.
        if (root != null && isStoryScreen(root)) {
            Log.i(TAG, "Classified: STORY -> bypass, zero side effects (className='${safeClassName(root)}')")
            return
        }

        val eventType = try {
            event.eventType
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read event.eventType", e)
            return
        }

        when (eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ->
                evaluateCurrentScreen(root)

            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                if (isStructuralChange(event)) evaluateCurrentScreen(root)
            }

            AccessibilityEvent.TYPE_VIEW_SCROLLED -> handleScroll(root)

            AccessibilityEvent.TYPE_VIEW_CLICKED -> handleClick(event, root)

            else -> { /* not relevant to any rule */ }
        }
    }

    private fun safeRoot(): AccessibilityNodeInfo? = try {
        rootInActiveWindow
    } catch (e: Exception) {
        Log.w(TAG, "rootInActiveWindow threw", e)
        null
    }

    private fun safeClassName(root: AccessibilityNodeInfo): String = try {
        root.className?.toString() ?: "?"
    } catch (e: Exception) {
        "?"
    }

    private fun isStructuralChange(event: AccessibilityEvent): Boolean = try {
        (event.contentChangeTypes and STRUCTURAL_CHANGE_MASK) != 0
    } catch (e: Exception) {
        Log.w(TAG, "Failed to read event.contentChangeTypes", e)
        false
    }

    // ─── Core evaluation — window-state changes and structural content changes ──

    private fun evaluateCurrentScreen(root: AccessibilityNodeInfo?) {
        try {
            if (root == null) return
            val screenType = classifyScreen(root) ?: return // couldn't classify safely; leave state untouched
            val cls = safeClassName(root)

            when (screenType) {
                ScreenType.REEL_VIEWER -> {
                    val isNewSession = !inReelSession
                    if (isNewSession) {
                        // Brand-new Reel session — decide its origin exactly once.
                        inReelSession = true
                        reelOriginAllowed =
                            lastNonReelScreenType == ScreenType.DM_THREAD ||
                            lastNonReelScreenType == ScreenType.PROFILE
                    }
                    // Ad-Reel swaps, caption changes, like-button animations
                    // all re-fire this with the SAME session still active.
                    // reelOriginAllowed is only ever set above (on a
                    // brand-new session), so an allowed Reel stays allowed
                    // and a blocked one stays blocked straight through them.
                    val label = when {
                        !reelOriginAllowed -> "FEED_REEL"
                        lastNonReelScreenType == ScreenType.DM_THREAD -> "DM_REEL"
                        lastNonReelScreenType == ScreenType.PROFILE -> "PROFILE_REEL"
                        else -> "REEL(unresolved-origin)"
                    }
                    Log.i(TAG, "Classified: $label newSession=$isNewSession allowed=$reelOriginAllowed className='$cls'")
                    if (!reelOriginAllowed) fireBack()
                }

                else -> {
                    // DM_THREAD, PROFILE, or OTHER: a genuine non-Reel screen.
                    Log.i(TAG, "Classified: $screenType (non-reel screen) className='$cls'")
                    lastNonReelScreenType = screenType
                    inReelSession = false
                    reelOriginAllowed = false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "evaluateCurrentScreen failed", e)
        }
    }

    /** The only way to move to the NEXT Reel — this is what rule 5 blocks. */
    private fun handleScroll(root: AccessibilityNodeInfo?) {
        try {
            if (root == null) return

            // Defense in depth: the top-level dispatcher already excluded
            // Stories for this event, but re-check independently.
            if (isStoryScreen(root)) return

            val type = classifyScreen(root) ?: return
            if (type != ScreenType.REEL_VIEWER) return // scrolling somewhere else entirely

            if (!inReelSession) {
                // Inside a Reel viewer but we never got a window-state/
                // content event for it. Fail safe: un-allowed session.
                inReelSession = true
                reelOriginAllowed = false
            }

            if (reelOriginAllowed) {
                Log.i(TAG, "Scroll inside an allowed Reel -> locking session and blocking (next-Reel attempt)")
                reelOriginAllowed = false
            } else {
                Log.i(TAG, "Scroll inside FEED_REEL -> blocking")
            }
            fireBack()
        } catch (e: Exception) {
            Log.e(TAG, "handleScroll failed", e)
        }
    }

    /** Taps — primarily the bottom-nav Reels tab. */
    private fun handleClick(event: AccessibilityEvent, root: AccessibilityNodeInfo?) {
        try {
            val clickedId = try {
                event.source?.viewIdResourceName
            } catch (e: Exception) {
                Log.w(TAG, "Failed to read clicked view id", e)
                null
            }

            if (clickedId != null && containsIdFragment(clickedId, REELS_TAB_IDS)) {
                Log.i(TAG, "Classified: REEL_TAB click (id='$clickedId') -> BACK")
                fireBack()
                return
            }

            // Defensive fallback only when we're not already tracking a
            // session: some Reel entry points (an inline thumbnail in the
            // feed) register only as a click with no window-state change.
            if (!inReelSession) evaluateCurrentScreen(root)
        } catch (e: Exception) {
            Log.e(TAG, "handleClick failed", e)
        }
    }

    // ─── Screen classification ───────────────────────────────────────────

    /**
     * Returns null (meaning "skip this cycle, don't touch any state") if
     * classification itself throws.
     *
     * Priority order is deliberate: DM_THREAD and PROFILE are checked
     * BEFORE REEL_VIEWER, because a DM thread showing an inline Reel-
     * preview bubble, or a Profile showing a grid of Reel thumbnails, can
     * otherwise trip the Reel-viewer signal while the user is still on the
     * thread/grid screen — which previously meant the DM/Profile origin
     * was never recorded. See Bug A in the class doc comment.
     */
    private fun classifyScreen(root: AccessibilityNodeInfo): ScreenType? = try {
        when {
            isDmThreadScreen(root) -> ScreenType.DM_THREAD
            isProfileScreen(root) -> ScreenType.PROFILE
            isReelViewerScreen(root) -> ScreenType.REEL_VIEWER
            else -> ScreenType.OTHER
        }
    } catch (e: Exception) {
        Log.e(TAG, "classifyScreen failed", e)
        null
    }

    /**
     * Fails TOWARD "yes, it's a Story" on any internal error — rule 6 says
     * Stories must never be touched, so an error here must never result in
     * a wrongful BACK. The cost of a false positive is zero (skip one
     * cycle); the cost of a false negative is blocking a real Story.
     */
    private fun isStoryScreen(root: AccessibilityNodeInfo): Boolean = try {
        hasAnyId(root, STORY_EXCLUSIVE_IDS, "STORY") || classNameContainsAny(root, STORY_CLASS_HINTS, "STORY")
    } catch (e: Exception) {
        Log.w(TAG, "isStoryScreen failed — defaulting to 'is a Story' to avoid ever blocking one", e)
        true
    }

    private fun isReelViewerScreen(root: AccessibilityNodeInfo): Boolean = try {
        hasAnyId(root, REEL_VIEWER_IDS, "REEL_VIEWER") || classNameContainsAny(root, REEL_CLASS_HINTS, "REEL_VIEWER")
    } catch (e: Exception) {
        Log.w(TAG, "isReelViewerScreen failed", e)
        false
    }

    private fun isDmThreadScreen(root: AccessibilityNodeInfo): Boolean = try {
        hasAnyId(root, DM_THREAD_IDS, "DM_THREAD") || classNameContainsAny(root, DM_CLASS_HINTS, "DM_THREAD")
    } catch (e: Exception) {
        Log.w(TAG, "isDmThreadScreen failed", e)
        false
    }

    private fun isProfileScreen(root: AccessibilityNodeInfo): Boolean = try {
        hasAnyId(root, PROFILE_IDS, "PROFILE") || classNameContainsAny(root, PROFILE_CLASS_HINTS, "PROFILE")
    } catch (e: Exception) {
        Log.w(TAG, "isProfileScreen failed", e)
        false
    }

    /**
     * Uses the platform's own indexed id lookup instead of a manual
     * recursive tree walk. Each id is looked up in its OWN try/catch: a
     * failure on one id logs a warning and moves on to the next instead of
     * aborting the whole check (this exact pattern is what crashed v3).
     * Logs the specific id that matched, tagged with `category`, so a real
     * device run shows exactly which signal fired.
     */
    private fun hasAnyId(root: AccessibilityNodeInfo, shortIds: Set<String>, category: String): Boolean {
        for (shortId in shortIds) {
            try {
                val fullId = "$INSTAGRAM_PACKAGE:id/$shortId"
                val matches = root.findAccessibilityNodeInfosByViewId(fullId)
                if (matches != null && matches.isNotEmpty()) {
                    Log.d(TAG, "MATCH[$category] viewId='$shortId'")
                    return true
                }
            } catch (e: Exception) {
                Log.w(TAG, "hasAnyId[$category]: lookup failed for id='$shortId'", e)
                // Keep checking the remaining ids.
            }
        }
        return false
    }

    private fun classNameContainsAny(root: AccessibilityNodeInfo, fragments: List<String>, category: String): Boolean {
        return try {
            val cls = root.className?.toString() ?: return false
            val hit = fragments.firstOrNull { cls.contains(it, ignoreCase = true) }
            if (hit != null) {
                Log.d(TAG, "MATCH[$category] className='$cls' matchedFragment='$hit'")
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.w(TAG, "classNameContainsAny[$category] failed", e)
            false
        }
    }

    private fun containsIdFragment(fullId: String, shortIds: Set<String>): Boolean = try {
        val shortName = fullId.substringAfterLast('/')
        shortIds.any { it.equals(shortName, ignoreCase = true) }
    } catch (e: Exception) {
        Log.w(TAG, "containsIdFragment failed", e)
        false
    }

    // ─── Back action ──────────────────────────────────────────────────────

    private fun fireBack() {
        try {
            val now = SystemClock.uptimeMillis()
            if (now - lastBackActionMs < BACK_ACTION_COOLDOWN_MS) return
            lastBackActionMs = now
            performGlobalAction(GLOBAL_ACTION_BACK)
        } catch (e: Exception) {
            Log.e(TAG, "performGlobalAction(GLOBAL_ACTION_BACK) failed", e)
        }
    }
}
