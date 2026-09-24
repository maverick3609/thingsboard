// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.service.inferrix.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import org.thingsboard.common.util.JacksonUtil;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * What the platform will forward to an Inferrix gateway, and nothing else.
 *
 * <p>This list is the entire security boundary for the gateway configuration plane. That is not a
 * figure of speech: the gateway authenticates every {@code /rest/**} request, but applies
 * <em>authorization</em> to only a minority of its endpoints — {@code @PreAuthorize("isAdmin()")}
 * where it appears at all, with the rest relying on service-layer permission checks that several
 * resources simply do not perform. So an endpoint reachable through this proxy is, in practice,
 * reachable by any Cortex user who passes our own gate. There is no second line behind it.
 *
 * <p>Three categories are kept out deliberately:
 *
 * <ul>
 *   <li><b>Credential surfaces.</b> {@code /v2/auth/**} mints and invalidates tokens, including the
 *       one Cortex itself holds. {@code /v2/api-tokens/**} is the same hazard on a newer surface:
 *       proxying it would let a browser mint a gateway credential that Cortex cannot see and that
 *       outlives its access control, or revoke the credential Cortex is using.</li>
 *   <li><b>Host-level access.</b> Script evaluation is remote code execution; the file store serves
 *       an arbitrary sub-path under a store name; the certificate services sign arbitrary CSRs.
 *       None of these become acceptable because the tenant owns the device. One route reaches the
 *       host through its <em>body</em> rather than its path, and so cannot be excluded by being
 *       left off this list — see {@link #bodyIsAllowed}, the one exception to "verb and path
 *       only".</li>
 *   <li><b>Live device control.</b> Mesh, thermostat, OTA, ToF and the Modbus/OPC ad-hoc tools drive
 *       real plant or real bus traffic. This feature configures a gateway; it does not operate
 *       one.</li>
 * </ul>
 *
 * <p>Matching a fixed pattern per route means no user-supplied path ever reaches the device — a
 * traversal attempt simply fails to match. The full derivation, and the reason each excluded route
 * is excluded, is in the spec (§2.4.2).
 */
public final class InferrixGatewayRoutes {

    /**
     * The gateway's REST dispatcher is mapped at {@code /rest/*}, so a resource declaring
     * {@code @RequestMapping("/v2/data-source")} is served at {@code /rest/v2/data-source}.
     *
     * <p>Callers match on the resource path and let the client prepend this. A path that already
     * carries the prefix therefore matches nothing and fails closed, rather than being silently
     * double-prefixed.
     */
    public static final String BASE = "/rest";

    /**
     * An identifier must begin with a non-dot.
     *
     * <p>A dot is legal <em>inside</em> a real xid, so the obvious {@code [A-Za-z0-9_.-]+} also
     * matches {@code ..} — which the gateway would then normalise away, turning an allowlisted
     * route into a traversal one segment up. Requiring the first character to be something other
     * than a dot costs nothing (no real identifier starts with one) and closes that outright.
     */
    private static final String XID = "[A-Za-z0-9_-][A-Za-z0-9_.-]{0,63}";

    /** A numeric surrogate key. */
    private static final String ID = "[0-9]{1,10}";

    /** An HTTP verb: ASCII letters only, so case folding cannot widen it. */
    private static final Pattern ASCII_METHOD = Pattern.compile("[A-Za-z]{3,7}");

    /** A model or event type name. Narrower than an xid: no dots, since none carry them. */
    private static final String TYPE = "[A-Za-z0-9_-]{1,64}";

    private static final List<Route> ROUTES = List.of(
            // --- Identity and health -------------------------------------------------------
            route("/v2/about", "GET"),
            route("/v2/model-schemas", "GET"),
            route("/v2/stack-monitor", "GET"),
            route("/v2/stack-monitor/" + ID, "GET"),
            route("/v2/server/network-interfaces", "GET"),
            route("/v2/server/languages", "GET"),
            // Reads only, deliberately. A PUT here takes an arbitrary settings key, and the
            // gateway's SystemSettingsService.save() never calls SystemSettingsDao.validate() --
            // so the single-key write skips the only validation that exists, and updateSettings
            // has no key allowlist of its own. That makes it a write primitive over the whole
            // configuration keyspace: httpClientProxyServer and httpClientUseProxy route every
            // outbound call the gateway makes through a host of the caller's choosing;
            // emailSmtpHost redirects alarm mail; databaseBackupFileLocation picks a host path to
            // write backups to (its existence check lives in the validate() that is skipped);
            // databaseSchemaVersion is unreadable but writable, and corrupts upgrade state.
            //
            // save() does call ensureAdminRole, and our service account is deliberately non-admin
            // (spec 2.9), so these would 403 today. That is defence in depth we are choosing not
            // to depend on: this list is meant to be the boundary by itself, and the day someone
            // configures an admin token the whole keyspace would open with no other signal.
            //
            // G6 may reopen writes behind an explicit key allowlist -- never a pattern.
            route("/v2/system-setting", "GET"),
            route("/v2/system-setting/license-key", "GET"),
            route("/v2/system-setting/" + XID, "GET"),

            // --- Data sources and points ---------------------------------------------------
            route("/v2/data-source", "GET", "POST"),
            route("/v2/data-source/" + XID, "GET", "PUT", "PATCH", "DELETE"),
            route("/v2/data-source/enable-disable/" + XID, "PATCH"),
            route("/v2/data-source/copy/" + XID, "PUT"),
            route("/v2/data-source/default-event-types/" + TYPE, "GET"),
            route("/v2/data-source-types", "GET"),
            route("/v2/data-point", "GET", "POST"),
            route("/v2/data-point/" + XID, "GET", "PUT", "PATCH", "DELETE"),
            route("/v2/data-point/enable-disable/" + XID, "PATCH"),
            // Reads only. A PUT here writes a live value to real plant; that is a G5 decision to be
            // taken explicitly, not inherited from the controller, whose point write was allowed
            // under a gate this proxy does not reproduce.
            route("/v2/point-value/latest/" + XID, "GET"),
            route("/v2/point-value/between/" + XID, "GET"),

            // --- Publishers ----------------------------------------------------------------
            route("/v2/publisher", "GET", "POST"),
            route("/v2/publisher/" + XID, "GET", "PUT", "PATCH", "DELETE"),
            route("/v2/publisher/enable-disable/" + XID, "PATCH"),
            route("/v2/publisher/copy/" + XID, "PUT"),
            route("/v2/publisher-types", "GET"),
            route("/v2/published-points", "GET", "POST"),
            route("/v2/published-points/" + XID, "GET", "PUT", "PATCH", "DELETE"),
            route("/v2/published-points/by-id/" + ID, "GET"),
            route("/v2/published-points/enable-disable/" + XID, "PUT"),
            route("/v2/published-points/bulk", "GET", "POST"),
            route("/v2/published-points/bulk/" + ID, "GET", "PUT", "DELETE"),

            // --- Events, detectors, handlers, alerts ---------------------------------------
            route("/v2/event-detector", "GET", "POST"),
            route("/v2/event-detector/" + XID, "GET", "PUT", "PATCH", "DELETE"),
            route("/v2/event-detector/bulk", "GET", "POST"),
            route("/v2/event-detector/bulk/" + ID, "GET", "PUT", "DELETE"),
            route("/v2/event-detector-type/" + TYPE, "GET"),
            route("/v2/event-handler", "GET", "POST"),
            route("/v2/event-handler/" + XID, "GET", "PUT", "PATCH", "DELETE"),
            route("/v2/event-handler/validate", "POST"),
            route("/v2/event-handler-types", "GET"),
            route("/v2/alert-list", "GET", "POST"),
            route("/v2/alert-list/" + XID, "GET", "PUT", "PATCH", "DELETE"),
            route("/v2/events", "GET"),
            route("/v2/events/active", "GET"),
            route("/v2/events/active-summary", "GET"),
            route("/v2/events/unacknowledged-summary", "GET"),
            route("/v2/events/" + ID, "GET"),
            route("/v2/events/acknowledge", "POST"),
            route("/v2/events/acknowledge/" + ID, "PUT"),
            route("/v2/events/counts", "POST"),
            route("/v2/events/data-point-summaries", "POST"),
            route("/v2/events/query/events-by-source-type", "POST"),
            route("/v2/event-types", "GET"),
            route("/v2/event-types/" + TYPE, "GET"),
            route("/v2/event-types/" + TYPE + "/" + TYPE, "GET"),
            route("/v2/event-types/" + TYPE + "/" + TYPE + "/" + XID, "GET"),

            // --- Schedules -----------------------------------------------------------------
            route("/v2/schedules", "GET", "POST"),
            route("/v2/schedules/" + XID, "GET", "PUT", "DELETE"),
            route("/v2/schedules/enable-disable/" + XID, "PUT"),
            route("/v2/schedule-rule-sets", "GET", "POST"),
            route("/v2/schedule-rule-sets/" + XID, "GET", "PUT", "DELETE"),

            // --- BACnet local devices ------------------------------------------------------
            // Not a per-module carve-out. A BACnet data source's localDeviceConfig is declared a
            // String but used as a lookup key into this CRUD, so the schema-driven form needs a
            // picker backed by these rows or BACnet data sources cannot be created at all.
            route("/v2/bacnet/local-devices", "GET", "POST"),
            route("/v2/bacnet/local-devices/" + ID, "GET", "PUT", "DELETE"),
            route("/v2/bacnet/object-types", "GET"),
            route("/v2/bacnet/object-properties/" + TYPE, "GET"),

            // --- Async job polling ---------------------------------------------------------
            // Required, not convenient: long stack operations answer 201 + Location and are polled
            // here. Written as exact paths rather than a /v2/system-actions prefix, because
            // /v2/system-actions/db-utils/{name} resolves a raw backup filename on the host.
            route("/v2/system-actions/status/" + XID, "GET"),
            route("/v2/system-actions/cancel/" + XID, "DELETE"),

            // --- Platform link -------------------------------------------------------------
            // The five reads accept the gateway-configuration permission since stack ask A10
            // (2026-09-23): a service account can now see whether the gateway believes it is
            // connected to this platform, which is exactly what to look at when it is not. The
            // writes, and device-profile/sync, remain isAdmin() on the gateway.
            //
            // Cortex's own gate is unchanged and is a separate question: this whole family stays
            // in ADMIN_FAMILIES below, so a Cortex customer user is refused it here regardless of
            // what the gateway would allow. It names the ThingsBoard URL and the tenant-admin
            // username the gateway connects with.
            //
            // Reads and broker settings only. POST /server-details writes the ThingsBoard
            // tenant-admin credential that stack recommendation R1 exists to remove, and the
            // device-profile writes create platform entities with it -- Cortex is the last place
            // that should make either easier to reach.
            //
            // POST /mqtt-configuration is the broker settings save, and PUT is only its on/off
            // switch. Both are here because where the broker is reached is a property of the
            // network rather than of the gateway -- the same reason a gateway's own management
            // address is editable. A moved or renumbered platform leaves a gateway dialling a host
            // that no longer answers, and without this the only repair is a hand-typed call on the
            // gateway itself. The body carries no platform credential: the ThingsBoard tenant-admin
            // login lives on /server-details, which stays excluded.
            route("/v2/platform-integration/mqtt-configuration", "GET", "POST", "PUT"),
            route("/v2/platform-integration/server-details", "GET"),
            route("/v2/platform-integration/device-profile", "GET"),
            route("/v2/platform-integration/device-profile/sync", "GET"),
            route("/v2/platform-integration/unprovisioned", "GET"),
            route("/v2/platform-integration/provisioned", "GET"),
            route("/v2/platform-integration/provisioned/" + ID, "DELETE"),
            route("/v2/platform-integration/provision/" + XID + "/" + ID, "PUT"));

    private InferrixGatewayRoutes() {
    }

    /**
     * GETs that write. The stack serves exactly one, and the verb hides it:
     * {@code /v2/platform-integration/device-profile/sync} calls
     * {@code deviceProfileService.syncAndSaveDeviceProfile()}.
     */
    private static final Set<String> WRITING_GETS =
            Set.of("/v2/platform-integration/device-profile/sync");

    /**
     * Route families a customer user must not read.
     *
     * <p>Matched by prefix <em>deliberately</em>, and in the opposite spirit to the route patterns
     * themselves. A route pattern must be exact, because a loose one would let an unintended path
     * reach the device. This list is the reverse: a loose prefix only ever demands <em>more</em>
     * authority, so swallowing a route someone adds later is the failure we want. Without that,
     * adding a sensitive GET and forgetting to list it here would silently make it
     * customer-reachable — the same bug this list exists to fix, relocated one file over.
     *
     * <p>What is in them: {@code system-setting} is the gateway's whole configuration keyspace —
     * the outbound proxy host, the SMTP host, the backup path, the licence key — and
     * {@code SystemSettingsResource} carries no {@code @PreAuthorize} at all, enforcement being
     * {@code ensureAdminRole} one layer down. {@code platform-integration} holds the link to this
     * platform, including the ThingsBoard URL and the tenant-admin username it connects with.
     * {@code /v2/server} reports network interfaces. None of it is a customer user's business, and
     * all of it is reconnaissance.
     */
    private static final List<String> ADMIN_FAMILIES = List.of(
            "/v2/system-setting",
            "/v2/platform-integration",
            "/v2/server");

    /** Event-handler routes that carry a handler model in their body. */
    private static final Pattern EVENT_HANDLER_WRITE =
            Pattern.compile("/v2/event-handler(/validate|/" + XID + ")?");

    /**
     * The verbs that send one. {@code DELETE} is not among them, and that is the point: it carries
     * no body to judge, and removing a process handler only ever reduces what the gateway can run.
     */
    private static final Set<String> BODY_VERBS = Set.of("POST", "PUT", "PATCH");

    /**
     * The handler whose configuration is a command line, named by Jackson's discriminator.
     *
     * <p>{@code ProcessEventHandlerVO.activeProcessCommand} is handed to
     * {@code Runtime.getRuntime().exec} by {@code ProcessWorkItem}, so creating one is remote code
     * execution on the gateway host.
     */
    private static final String COMMAND_HANDLER_TYPE = "PROCESS_HANDLER";

    /**
     * Whether this request's body may be forwarded.
     *
     * <p>The only body this list inspects, and it exists because one allowlisted route grew a
     * payload that reaches past configuration into the host — which is precisely what the excluded
     * families above are excluded for.
     *
     * <p>Until 2026-09-23 the gateway refused a non-administrator asking for any event handler, and
     * the platform's service account is deliberately not an administrator, so
     * {@code POST /v2/event-handler} could not do this. Stack fix D16 widened handler creation to
     * the gateway-configuration permission — the credential the platform holds for every site — and
     * the stack's own release notes say what came with it: "this permission now carries the ability
     * to run commands on the gateway host. It is no longer meaningfully less than administrator."
     *
     * <p>The route cannot simply be dropped: email, SMS and set-point handlers are the feature and
     * they share it. So the discriminator is read instead. It is <em>required</em> rather than
     * merely checked — a body with no {@code handlerType} cannot be shown to be one of the three
     * that are allowed, and a {@code PATCH} of one field into an existing process handler is
     * exactly that shape. Cortex's own UI always sends the whole model, so nothing it does is
     * refused here.
     *
     * <p>{@code DELETE} is untouched. It carries nothing to judge, and removing a process handler
     * only reduces what the gateway can be made to run — so a rule that refused it would protect
     * the command rather than the host.
     *
     * <p>Jackson on both sides, so what this reads and what the gateway deserialises cannot
     * disagree about a duplicate key or a JSON-escaped character. The match is exact
     * because the gateway's own is: {@code process_handler}, {@code Process_Handler} and
     * {@code "PROCESS_HANDLER "} were each answered 400 "Failed to read request" when probed
     * (2026-09-23), so no spelling this lets past is a spelling the gateway will build. Anything
     * that does not parse as a JSON object is refused: a body that cannot be read cannot be shown
     * to be safe.
     *
     * @param body the raw request body, exactly as it would be forwarded
     */
    public static boolean bodyIsAllowed(String method, String path, String body) {
        if (method == null || path == null) {
            return false;
        }
        if (!ASCII_METHOD.matcher(method).matches()
                || !BODY_VERBS.contains(method.toUpperCase(Locale.ROOT))
                || !EVENT_HANDLER_WRITE.matcher(path).matches()) {
            return true;
        }
        JsonNode json;
        try {
            json = JacksonUtil.toJsonNode(body);
        } catch (IllegalArgumentException malformed) {
            // Unparseable is refused, not raised: the body is forwarded opaquely, so nothing has
            // parsed it before now, and letting the exception out would turn a bad payload into a
            // 500 where the caller is owed "this cannot be forwarded".
            return false;
        }
        if (json == null || !json.isObject()) {
            return false;
        }
        JsonNode type = json.get("handlerType");
        return type != null && type.isTextual() && !COMMAND_HANDLER_TYPE.equals(type.asText());
    }

    /**
     * Whether forwarding this changes something on the gateway.
     *
     * <p>Decides which {@code Operation} the caller needs on the <em>device</em>. Kept separate
     * from {@link #requiresTenantAdmin} on purpose: "may this user do it" and "is this a write"
     * are different questions, and collapsing them would make reading the gateway's settings
     * demand device WRITE.
     */
    public static boolean changesGatewayState(String method, String path) {
        if (method == null || path == null) {
            return true;
        }
        if (!"GET".equals(method.toUpperCase(Locale.ROOT))) {
            return true;
        }
        return WRITING_GETS.contains(path);
    }

    /**
     * Whether only a tenant administrator may make this call.
     *
     * <p>Every write, plus the administrator-read families above. Deliberately not "is this a
     * GET": some allowlisted GETs write and others are administrator reads on the gateway, and the
     * verb says nothing about either.
     */
    public static boolean requiresTenantAdmin(String method, String path) {
        if (changesGatewayState(method, path)) {
            return true;
        }
        for (String family : ADMIN_FAMILIES) {
            if (path.equals(family) || path.startsWith(family + "/")) {
                return true;
            }
        }
        return false;
    }

    /**
     * @param method the HTTP verb; case-insensitive, ASCII letters only
     * @param path   the gateway resource path, without the {@link #BASE} prefix and without a query
     *               string. This is <em>enforced, not assumed</em>: a path carrying {@code ?},
     *               {@code #} or {@code %} is refused outright rather than trusted to have been
     *               split by the caller. The two statements used to disagree — the javadoc said
     *               callers separated the query off, while a test asserted this method rejected
     *               one — and a contract that only one half believes is the kind that quietly stops
     *               holding.
     */
    public static boolean isAllowed(String method, String path) {
        if (method == null || path == null) {
            return false;
        }
        // Reject the three characters that change what a path *means* rather than what it says.
        //
        // '%' because we match the raw path: an encoded separator would slip past every pattern
        // here and be decoded by the gateway afterwards. Refusing it outright beats decoding first
        // and inheriting the double-decoding bugs that come with that.
        //
        // '?' and '#' because they end the path and begin something else. The contract is that a
        // caller has already separated the query string off; this is what makes that contract
        // enforced rather than merely documented. Without it these are refused only incidentally,
        // as characters absent from every pattern -- true today, and silently untrue the moment
        // someone adds a route with a wider class.
        for (char reserved : new char[]{'%', '?', '#'}) {
            if (path.indexOf(reserved) >= 0) {
                return false;
            }
        }
        // An HTTP verb is ASCII letters and nothing else. Without this, case folding accepts
        // characters that are not: U+017F (long s) upper-cases to 'S', so "po<U+017F>t" becomes
        // "POST" and is allowed. The client folds identically today so nothing desynchronises --
        // but "two components agree by coincidence" is a property that survives exactly until one
        // of them is changed, and a verb has no business containing non-ASCII in the first place.
        if (!ASCII_METHOD.matcher(method).matches()) {
            return false;
        }
        // Locale.ROOT so the fold cannot vary with the server's default locale (Turkish dotted and
        // dotless i being the usual way that bites).
        String upper = method.toUpperCase(Locale.ROOT);
        for (Route route : ROUTES) {
            if (route.methods().contains(upper) && route.pattern().matcher(path).matches()) {
                return true;
            }
        }
        return false;
    }

    /**
     * The compiled route patterns, for the invariant test in {@code InferrixGatewayRoutesTest}.
     *
     * <p>Package-private on purpose: asserting properties over the whole table catches a future
     * route that quietly widens the character set, which no amount of example strings will.
     */
    static List<Pattern> patterns() {
        return ROUTES.stream().map(Route::pattern).toList();
    }

    private static Route route(String pattern, String... methods) {
        return new Route(Pattern.compile(pattern), Set.of(methods));
    }

    private record Route(Pattern pattern, Set<String> methods) {
    }

}
