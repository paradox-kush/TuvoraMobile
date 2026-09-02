package com.nuvio.app.features.iptv

import com.nuvio.app.features.addons.HttpStatusException
import com.nuvio.app.features.iptv.stalker.StalkerAuthException
import com.nuvio.app.features.iptv.stalker.StalkerPortalRefusedException
import com.nuvio.app.features.iptv.stalker.StalkerSessionUnavailableException

/**
 * What to tell the viewer when a playlist's category list fails to load.
 *
 * The hub used to render ONE fixed line for every failure — "Couldn't reach this playlist / Check
 * the portal is up and try again." — because [XtreamHubRepository] dropped the throwable on the
 * floor. That message is actively wrong for the two failures we can actually explain:
 *
 *  - a provider's WAF answering 403/429 (the portal is UP; its edge turned this device away), and
 *  - a portal that answered and REFUSED us (unknown MAC, device binding held by another box,
 *    a line already streaming elsewhere) — cases where [StalkerSession] already composed a precise,
 *    remedy-bearing sentence that nobody ever saw.
 *
 * Both sent a real user chasing a portal outage that never happened. NuvioTV never had the bug
 * (`XtreamHubViewModel` surfaces `e.message`), so this restores parity as well as sense.
 *
 * Pure and type-driven on purpose: it classifies by exception TYPE, never by parsing a localized
 * message, and it holds no reference to string resources so it tests without Compose or a portal.
 * Types we did not author fall through to [Kind.UNREACHABLE] — a raw `UnknownHostException:
 * tv.gplay.biz` is noise to a viewer, and the generic copy is honest about it.
 *
 * Every failure also carries a short [Failure.detail] breadcrumb. Support runs on screenshots: a
 * card that says only "couldn't reach this playlist" is unactionable in a Discord thread, whereas
 * `HTTP 403 · tv.gplay.biz` names the failure and the provider in one line the viewer can send.
 */
object IptvLoadFailurePolicy {

    enum class Kind {
        /** Nothing specific known: DNS, timeout, connection refused, an open breaker. */
        UNREACHABLE,

        /**
         * The provider's edge refused us outright. The portal itself is healthy, so "check the
         * portal is up" is the one thing the viewer should NOT go do.
         */
        BLOCKED_BY_PROVIDER,

        /** The portal answered and said no. [Failure.portalText] carries the reason and remedy. */
        REFUSED,
    }

    /**
     * [status] is set only for [Kind.BLOCKED_BY_PROVIDER] (the code to show); [portalText] only for
     * [Kind.REFUSED] (our own already-worded explanation, safe to render verbatim). [detail] is the
     * always-present support breadcrumb — terse, technical, and meant to be legible in a screenshot.
     */
    data class Failure(
        val kind: Kind,
        val status: Int? = null,
        val portalText: String? = null,
        val detail: String = UNKNOWN_REASON,
    )

    /** [host] is the playlist's panel origin, appended to [Failure.detail] so support knows which. */
    fun classify(error: Throwable?, host: String? = null): Failure {
        val detail = detailOf(error, host)
        return when {
            error is HttpStatusException && error.status in BLOCKING_STATUSES ->
                Failure(Kind.BLOCKED_BY_PROVIDER, status = error.status, detail = detail)

            // StalkerDeviceConflictException is a subclass — its remedy ("ask the provider to reset
            // the MAC") is the single most useful sentence this whole path can produce.
            error is StalkerPortalRefusedException -> refusal(error, detail)
            error is StalkerAuthException -> refusal(error, detail)
            error is StalkerSessionUnavailableException -> refusal(error, detail)

            else -> Failure(Kind.UNREACHABLE, detail = detail)
        }
    }

    /** A refusal with no message is still a refusal — the card falls back to its own copy. */
    private fun refusal(error: Throwable, detail: String): Failure =
        Failure(Kind.REFUSED, portalText = error.message?.takeIf { it.isNotBlank() }, detail = detail)

    /**
     * The breadcrumb: what went wrong, then who it went wrong with. A status beats a class name
     * (`HTTP 403` tells us more than `HttpStatusException`); otherwise the exception's own type is
     * the most specific honest thing we have. Never the message — those carry the account name and
     * would put a viewer's playlist label into a screenshot they post publicly.
     */
    private fun detailOf(error: Throwable?, host: String?): String {
        val reason = when {
            error is HttpStatusException -> "HTTP ${error.status}"
            error != null -> error::class.simpleName ?: UNKNOWN_REASON
            else -> UNKNOWN_REASON
        }
        return listOfNotNull(reason, host?.takeIf { it.isNotBlank() }).joinToString(" · ")
    }

    private const val UNKNOWN_REASON = "unknown error"

    /**
     * Statuses that mean "the edge turned us away", not "the portal is broken".
     *
     * 403 is Cloudflare's block page (measured live against a real provider: a couple of dozen
     * ordinary MAG requests earned one), 429 is a plain rate limit, 419 is the non-standard code
     * some panels return for the same thing, 451 is a filtering block, and 456 is the body-less
     * anti-bot refusal seen live on a Cloudflare-fronted panel (same family, different vendor code).
     * Deliberately narrow — a 404 means the portal URL is wrong and a 5xx means the origin really is
     * unwell, and dressing either of those up as "you are blocked" would send the viewer nowhere useful.
     */
    private val BLOCKING_STATUSES = setOf(403, 419, 429, 451, 456)
}
