// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.service.inferrix.gateway;

import org.junit.jupiter.api.Test;

import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The allowlist is the whole security boundary for the gateway config plane, so these are the
 * tests that matter most in the feature. The gateway itself has no defence in depth to fall back
 * on: a request that gets past this list reaches a REST API where authorization is applied
 * per-endpoint and unevenly.
 */
class InferrixGatewayRoutesTest {

    // --- What must never be forwarded -------------------------------------------------------

    @Test
    void theAuthRoutesAreNeverForwarded() {
        // Same rule as the controller: anything under /v2/auth can mint or invalidate credentials.
        // The token exchange in particular is how Cortex authenticates; proxying it would let a
        // browser spend Cortex's own client_secret.
        assertFalse(InferrixGatewayRoutes.isAllowed("POST", "/v2/auth/oauth/token"));
        assertFalse(InferrixGatewayRoutes.isAllowed("POST", "/v2/auth/login"));
        assertFalse(InferrixGatewayRoutes.isAllowed("POST", "/v2/auth/logout"));
        assertFalse(InferrixGatewayRoutes.isAllowed("PUT", "/v2/auth/password"));
        assertFalse(InferrixGatewayRoutes.isAllowed("GET", "/v2/auth/user"));
    }

    @Test
    void theApiTokenRoutesAreNeverForwarded() {
        // Stack 5.1.0 added these. Proxying them would let a tenant admin mint a fresh gateway
        // credential through the UI -- one that outlives Cortex's own access control and is
        // invisible to it -- or revoke the token Cortex is holding and lock the platform out.
        // This is the exact failure the controller's /auth exclusion prevents, on a new surface.
        assertFalse(InferrixGatewayRoutes.isAllowed("POST", "/v2/api-tokens"));
        assertFalse(InferrixGatewayRoutes.isAllowed("GET", "/v2/api-tokens"));
        assertFalse(InferrixGatewayRoutes.isAllowed("DELETE", "/v2/api-tokens/abc123"));
    }

    @Test
    void theScriptRoutesAreNotForwardedInV1() {
        // Remote code execution on the gateway host. G6 re-opens these deliberately, behind its own
        // review -- a tenant owning the device is not a reason to hand every user with device WRITE
        // a shell on it.
        assertFalse(InferrixGatewayRoutes.isAllowed("GET", "/v2/script"));
        assertFalse(InferrixGatewayRoutes.isAllowed("POST", "/v2/script"));
        assertFalse(InferrixGatewayRoutes.isAllowed("GET", "/v2/global-scripts"));
        assertFalse(InferrixGatewayRoutes.isAllowed("POST", "/v2/global-scripts"));
    }

    @Test
    void theCertificateServiceIsNotForwarded() {
        // Signs arbitrary CSRs; escalation well beyond configuration. It also lives outside the
        // /v2 tree, which is exactly why a prefix-based rule would have missed it.
        assertFalse(InferrixGatewayRoutes.isAllowed("POST", "/certificate-service"));
        assertFalse(InferrixGatewayRoutes.isAllowed("POST", "/certificate-authority-service/issue"));
    }

    // --- Path handling ----------------------------------------------------------------------

    @Test
    void traversalAndSmugglingAttemptsDoNotMatch() {
        assertFalse(InferrixGatewayRoutes.isAllowed("GET", "/v2/about/../auth/user"));
        assertFalse(InferrixGatewayRoutes.isAllowed("GET", "/v2/../../etc/passwd"));
        assertFalse(InferrixGatewayRoutes.isAllowed("GET", "/v2/aboutx"));
        assertFalse(InferrixGatewayRoutes.isAllowed("GET", "xx/v2/about"));
        assertFalse(InferrixGatewayRoutes.isAllowed("GET", ""));
        assertFalse(InferrixGatewayRoutes.isAllowed(null, "/v2/about"));
        assertFalse(InferrixGatewayRoutes.isAllowed("GET", null));
    }

    @Test
    void anIdentifierCannotBeATraversalSegment() {
        // An xid is pattern-constrained, and "." is legal inside one -- so ".." would match a naive
        // [A-Za-z0-9_.-]+ and normalise away a path segment at the device. The identifier must
        // therefore begin with a non-dot.
        assertFalse(InferrixGatewayRoutes.isAllowed("GET", "/v2/data-source/.."));
        assertFalse(InferrixGatewayRoutes.isAllowed("GET", "/v2/data-source/."));
        assertFalse(InferrixGatewayRoutes.isAllowed("GET", "/v2/data-source/..."));
        assertFalse(InferrixGatewayRoutes.isAllowed("DELETE", "/v2/data-point/.."));
        // ...but an ordinary identifier containing a dot is fine, because real xids carry them.
        assertTrue(InferrixGatewayRoutes.isAllowed("GET", "/v2/data-source/DS.ahu-1_2"));
    }

    @Test
    void percentEncodingIsRejectedOutright() {
        // We match the raw path, so an encoded separator would slip past every regex here and be
        // decoded by the gateway. Rather than decode-then-match (and inherit every double-decoding
        // bug), refuse any path carrying a percent at all. Cortex builds these paths itself and
        // never needs one.
        assertFalse(InferrixGatewayRoutes.isAllowed("GET", "/v2/data-source/%2e%2e%2fauth%2fuser"));
        assertFalse(InferrixGatewayRoutes.isAllowed("GET", "/v2/data-source/a%2Fb"));
        assertFalse(InferrixGatewayRoutes.isAllowed("GET", "/v2/about%00"));
    }

    @Test
    void aQueryStringNeverReachesTheDevice() {
        // The stack parses the raw query string as RQL, and a POST can carry RQL alongside a JSON
        // body. Cortex constructs RQL itself (spec 2.4); nothing operator-typed is forwarded, so a
        // path arriving here with a query string attached is a bug, not a request to permit.
        assertFalse(InferrixGatewayRoutes.isAllowed("GET", "/v2/data-source?limit(50,0)"));
        assertFalse(InferrixGatewayRoutes.isAllowed("GET", "/v2/about?x=1"));
        assertFalse(InferrixGatewayRoutes.isAllowed("POST", "/v2/data-source?eq(name,foo)"));
    }

    @Test
    void theRestPrefixIsSuppliedByTheClientNotTheCaller() {
        // isAllowed matches the resource path; InferrixGatewayRoutes.BASE is prepended when the URL
        // is built. A caller that passes an already-prefixed path is confused about which half owns
        // the prefix, and must fail closed rather than double it.
        assertFalse(InferrixGatewayRoutes.isAllowed("GET", "/rest/v2/about"));
        assertTrue(InferrixGatewayRoutes.isAllowed("GET", "/v2/about"));
    }

    // --- Method handling --------------------------------------------------------------------

    @Test
    void aRouteIsOnlyOpenForTheMethodsItDeclares() {
        assertTrue(InferrixGatewayRoutes.isAllowed("GET", "/v2/about"));
        assertFalse(InferrixGatewayRoutes.isAllowed("PUT", "/v2/about"));
        assertFalse(InferrixGatewayRoutes.isAllowed("DELETE", "/v2/about"));
        assertTrue(InferrixGatewayRoutes.isAllowed("GET", "/v2/system-setting"));
        assertTrue(InferrixGatewayRoutes.isAllowed("GET", "/v2/system-setting/thingsboardManagementAddress"));
        assertFalse(InferrixGatewayRoutes.isAllowed("DELETE", "/v2/system-setting/thingsboardManagementAddress"));
        assertFalse(InferrixGatewayRoutes.isAllowed("POST", "/v2/system-setting"));
    }

    @Test
    void noSystemSettingCanBeWrittenThroughTheProxy() {
        // A single-key PUT is an arbitrary-key write over the gateway's whole configuration
        // keyspace: SystemSettingsService.save() never calls SystemSettingsDao.validate(), and
        // updateSettings has no key allowlist, so nothing on the far side constrains the name.
        // These four are the ones that turn a misconfiguration into an egress channel or a
        // persistent DoS, and none of them may be reachable from a browser.
        assertFalse(InferrixGatewayRoutes.isAllowed("PUT", "/v2/system-setting/httpClientProxyServer"));
        assertFalse(InferrixGatewayRoutes.isAllowed("PUT", "/v2/system-setting/httpClientUseProxy"));
        assertFalse(InferrixGatewayRoutes.isAllowed("PUT", "/v2/system-setting/emailSmtpHost"));
        assertFalse(InferrixGatewayRoutes.isAllowed("PUT", "/v2/system-setting/databaseBackupFileLocation"));
        assertFalse(InferrixGatewayRoutes.isAllowed("PUT", "/v2/system-setting/databaseSchemaVersion"));
        assertFalse(InferrixGatewayRoutes.isAllowed("PUT", "/v2/system-setting/emailSmtpPassword"));
        // ...and not the one that looks innocuous either. If G6 reopens writes it must do so with
        // an explicit list of keys, never a pattern -- so no key at all passes today.
        assertFalse(InferrixGatewayRoutes.isAllowed("PUT", "/v2/system-setting/thingsboardManagementAddress"));
        assertFalse(InferrixGatewayRoutes.isAllowed("PUT", "/v2/system-setting"));
    }

    @Test
    void methodMatchingIsCaseInsensitive() {
        assertTrue(InferrixGatewayRoutes.isAllowed("get", "/v2/about"));
        assertTrue(InferrixGatewayRoutes.isAllowed("GeT", "/v2/about"));
        assertFalse(InferrixGatewayRoutes.isAllowed("GETT", "/v2/about"));
        // U+017F upper-cases to S under a locale-sensitive fold. Pinned to Locale.ROOT here and in
        // the client so the two cannot drift into disagreeing about what verb is being sent.
        assertFalse(InferrixGatewayRoutes.isAllowed("poſt", "/v2/data-source"));
    }

    // --- Properties of the whole table, not of example strings ------------------------------

    @Test
    void everyRouteIsLiteralTextPlusOneOfThreeSanctionedClasses() {
        // Asserts on the pattern SOURCE, not on matching.
        //
        // The first version of this test probed one-character strings with find(), which cannot
        // match a whole-path pattern for ANY character -- so it passed unconditionally and proved
        // nothing, while reading like the guarantee the whole design rests on. Adversarial review
        // caught it; verified by emptying the skip-list and watching it still pass.
        //
        // Restating the three class sources literally here is deliberate. A test that derived them
        // from the class under test would accept whatever that class happened to say, which is the
        // opposite of what this is for.
        for (Pattern pattern : InferrixGatewayRoutes.patterns()) {
            String stripped = pattern.pattern()
                    .replace("[A-Za-z0-9_-][A-Za-z0-9_.-]{0,63}", "")   // XID
                    .replace("[0-9]{1,10}", "")                          // ID
                    .replace("[A-Za-z0-9_-]{1,64}", "");                 // TYPE

            assertTrue(stripped.matches("[A-Za-z0-9/_-]*"),
                    "route " + pattern.pattern() + " contains something that is neither literal text"
                            + " nor one of the three sanctioned classes -- residue: " + stripped);
        }
    }

    @Test
    void theRouteInvariantWouldCatchAWidenedOrWildcardRoute() {
        // Guards the guard: proves the assertion above actually fires, using the shapes it exists
        // to prevent. Without this, a future edit could weaken it back into vacuity unnoticed --
        // which is precisely what happened to its predecessor.
        for (String dangerous : new String[]{
                "/v2/data-source/.+",                        // wildcard tail
                "/v2/file-stores/[A-Za-z0-9_-]{1,64}/.*",    // Ant-style remainder
                "/v2/data-source/[A-Za-z0-9_.-/]{1,64}",     // class widened to swallow a slash
                "/v2/data.source"}) {                        // unescaped dot in a literal segment
            String stripped = dangerous
                    .replace("[A-Za-z0-9_-][A-Za-z0-9_.-]{0,63}", "")
                    .replace("[0-9]{1,10}", "")
                    .replace("[A-Za-z0-9_-]{1,64}", "");
            assertFalse(stripped.matches("[A-Za-z0-9/_-]*"),
                    "the route invariant failed to reject " + dangerous);
        }
    }

    @Test
    void noVariableSlotCanSpanASegmentBoundary() {
        // A placeholder that matched '/' would let one route reach another's endpoint -- the way a
        // system-actions rule could otherwise reach db-utils. Asserted over the table rather than
        // per route, so a new pattern inherits the guarantee instead of needing its own test.
        for (Pattern pattern : InferrixGatewayRoutes.patterns()) {
            long literalSlashes = pattern.pattern().chars().filter(c -> c == '/').count();
            for (String probe : new String[]{"a/b", "1/2", "../x", "a/../b"}) {
                String candidate = pattern.pattern().replaceAll("\\[[^\\]]*\\][^/]*", probe);
                assertFalse(Pattern.compile(pattern.pattern()).matcher(candidate).matches()
                                && candidate.chars().filter(c -> c == '/').count() > literalSlashes,
                        "pattern " + pattern + " let a variable slot swallow a '/'");
            }
        }
    }

    // --- What v1 must be able to reach ------------------------------------------------------

    @Test
    void theSchemaAndIdentityEndpointsAreReachable() {
        // The two endpoints stack 5.1.0 added for us. Without model-schemas there is no form
        // rendering at all, so this is the single most load-bearing GET in the feature.
        assertTrue(InferrixGatewayRoutes.isAllowed("GET", "/v2/model-schemas"));
        assertTrue(InferrixGatewayRoutes.isAllowed("GET", "/v2/about"));
        assertTrue(InferrixGatewayRoutes.isAllowed("GET", "/v2/stack-monitor"));
        // ServerResource has no bare mapping -- it is email/SMS/network/restart/languages. Only the
        // two read-only members are ours, and the restart is emphatically not.
        assertTrue(InferrixGatewayRoutes.isAllowed("GET", "/v2/server/network-interfaces"));
        assertTrue(InferrixGatewayRoutes.isAllowed("GET", "/v2/server/languages"));
        assertFalse(InferrixGatewayRoutes.isAllowed("GET", "/v2/server"));
        // ...and they are reads.
        assertFalse(InferrixGatewayRoutes.isAllowed("POST", "/v2/model-schemas"));
        assertFalse(InferrixGatewayRoutes.isAllowed("POST", "/v2/about"));
    }

    @Test
    void theDataSourceAndPointPlaneIsReachable() {
        assertTrue(InferrixGatewayRoutes.isAllowed("GET", "/v2/data-source"));
        assertTrue(InferrixGatewayRoutes.isAllowed("POST", "/v2/data-source"));
        assertTrue(InferrixGatewayRoutes.isAllowed("GET", "/v2/data-source/DS_1"));
        assertTrue(InferrixGatewayRoutes.isAllowed("PUT", "/v2/data-source/DS_1"));
        assertTrue(InferrixGatewayRoutes.isAllowed("DELETE", "/v2/data-source/DS_1"));
        assertTrue(InferrixGatewayRoutes.isAllowed("GET", "/v2/data-source-types"));
        // Reachability, which no property test can establish: the invariant above proves every
        // route is narrow, never that a route exists. A typo in a literal -- "default-event-type"
        // for "default-event-types" -- leaves the invariant perfectly green while the route is
        // silently dead, and that surfaces as a broken form in the UI rather than as a red test.
        assertTrue(InferrixGatewayRoutes.isAllowed("GET", "/v2/data-source/default-event-types/ModbusIp"));

        assertTrue(InferrixGatewayRoutes.isAllowed("GET", "/v2/data-point"));
        assertTrue(InferrixGatewayRoutes.isAllowed("POST", "/v2/data-point"));
        assertTrue(InferrixGatewayRoutes.isAllowed("GET", "/v2/data-point/DP_1"));
        assertTrue(InferrixGatewayRoutes.isAllowed("PUT", "/v2/data-point/DP_1"));
        assertTrue(InferrixGatewayRoutes.isAllowed("DELETE", "/v2/data-point/DP_1"));
    }

    @Test
    void theEventPlaneIsReachable() {
        assertTrue(InferrixGatewayRoutes.isAllowed("GET", "/v2/event-detector"));
        assertTrue(InferrixGatewayRoutes.isAllowed("POST", "/v2/event-detector"));
        // The detector-type resource is only served per data type; there is no bare collection.
        assertTrue(InferrixGatewayRoutes.isAllowed("GET", "/v2/event-detector-type/NUMERIC"));
        assertFalse(InferrixGatewayRoutes.isAllowed("GET", "/v2/event-detector-type"));
        assertTrue(InferrixGatewayRoutes.isAllowed("GET", "/v2/event-handler-types"));
        assertTrue(InferrixGatewayRoutes.isAllowed("GET", "/v2/event-handler"));
        assertTrue(InferrixGatewayRoutes.isAllowed("POST", "/v2/event-handler"));
        assertTrue(InferrixGatewayRoutes.isAllowed("GET", "/v2/events"));
        assertTrue(InferrixGatewayRoutes.isAllowed("GET", "/v2/event-types"));
        assertTrue(InferrixGatewayRoutes.isAllowed("GET", "/v2/alert-list"));
        assertTrue(InferrixGatewayRoutes.isAllowed("POST", "/v2/event-handler/validate"));
        assertTrue(InferrixGatewayRoutes.isAllowed("POST", "/v2/events/query/events-by-source-type"));
    }

    @Test
    void thePublisherPlaneIsReachable() {
        assertTrue(InferrixGatewayRoutes.isAllowed("GET", "/v2/publisher"));
        assertTrue(InferrixGatewayRoutes.isAllowed("POST", "/v2/publisher"));
        assertTrue(InferrixGatewayRoutes.isAllowed("DELETE", "/v2/publisher/PUB_1"));
        assertTrue(InferrixGatewayRoutes.isAllowed("GET", "/v2/publisher-types"));
        assertTrue(InferrixGatewayRoutes.isAllowed("GET", "/v2/published-points"));
        assertTrue(InferrixGatewayRoutes.isAllowed("PUT", "/v2/published-points/PP_1"));
        assertTrue(InferrixGatewayRoutes.isAllowed("GET", "/v2/published-points/by-id/12"));
        assertTrue(InferrixGatewayRoutes.isAllowed("POST", "/v2/published-points/bulk"));
        assertTrue(InferrixGatewayRoutes.isAllowed("DELETE", "/v2/published-points/bulk/7"));
        assertTrue(InferrixGatewayRoutes.isAllowed("PUT", "/v2/published-points/enable-disable/PP_1"));
    }

    @Test
    void theScheduleSurfaceIsReachable() {
        // Declared with @RequestMapping({"/v2/schedules"}) and a @RequestMapping(method=DELETE)
        // instead of @DeleteMapping -- both forms defeated a naive grep, so these are pinned.
        assertTrue(InferrixGatewayRoutes.isAllowed("GET", "/v2/schedules"));
        assertTrue(InferrixGatewayRoutes.isAllowed("POST", "/v2/schedules"));
        assertTrue(InferrixGatewayRoutes.isAllowed("DELETE", "/v2/schedules/SCH_1"));
        assertTrue(InferrixGatewayRoutes.isAllowed("PUT", "/v2/schedules/enable-disable/SCH_1"));
        assertTrue(InferrixGatewayRoutes.isAllowed("GET", "/v2/schedule-rule-sets"));
        assertTrue(InferrixGatewayRoutes.isAllowed("DELETE", "/v2/schedule-rule-sets/RS_1"));
    }

    @Test
    void bacnetLocalDevicesAreReachableBecauseDataSourcesReferenceThem() {
        // Not a general per-module carve-out. A BACnet data source's localDeviceConfig is declared
        // String but used as a lookup key into this CRUD, so without these the schema-driven form
        // has no picker and BACnet data sources cannot be created at all.
        assertTrue(InferrixGatewayRoutes.isAllowed("GET", "/v2/bacnet/local-devices"));
        assertTrue(InferrixGatewayRoutes.isAllowed("POST", "/v2/bacnet/local-devices"));
        assertTrue(InferrixGatewayRoutes.isAllowed("PUT", "/v2/bacnet/local-devices/3"));
        assertTrue(InferrixGatewayRoutes.isAllowed("GET", "/v2/bacnet/object-types"));
        assertTrue(InferrixGatewayRoutes.isAllowed("GET", "/v2/bacnet/object-properties/analogInput"));
        // The BACnet tools drive network scans and arbitrary property writes: out of v1 scope.
        assertFalse(InferrixGatewayRoutes.isAllowed("POST", "/v2/bacnet/tool/whois"));
        assertFalse(InferrixGatewayRoutes.isAllowed("POST", "/v2/bacnet/tool/write"));
    }

    @Test
    void theHighRiskSurfacesAreAllRefused() {
        assertFalse(InferrixGatewayRoutes.isAllowed("POST", "/v2/users"));
        assertFalse(InferrixGatewayRoutes.isAllowed("DELETE", "/v2/users/admin"));
        assertFalse(InferrixGatewayRoutes.isAllowed("GET", "/v2/file-stores"));
        assertFalse(InferrixGatewayRoutes.isAllowed("GET", "/v2/file-stores/default/etc/passwd"));
        assertFalse(InferrixGatewayRoutes.isAllowed("POST", "/v2/script/eval-file-store/default/x.js"));
        assertFalse(InferrixGatewayRoutes.isAllowed("POST", "/v2/point-value-modification/import"));
        assertFalse(InferrixGatewayRoutes.isAllowed("POST", "/v2/opc-da/list-servers"));
        assertFalse(InferrixGatewayRoutes.isAllowed("POST", "/v2/modbus/serial/write"));
        assertFalse(InferrixGatewayRoutes.isAllowed("POST", "/v2/modbus/scan"));
        assertFalse(InferrixGatewayRoutes.isAllowed("PUT", "/v2/server/restart"));
        assertFalse(InferrixGatewayRoutes.isAllowed("DELETE", "/v2/mesh-console/ota/image.bin"));
        // A live point write reaches real plant. Deferred to an explicit G5 decision rather than
        // inherited from the controller, whose equivalent was allowed under a different gate.
        assertFalse(InferrixGatewayRoutes.isAllowed("PUT", "/v2/point-value/DP_1"));
        assertTrue(InferrixGatewayRoutes.isAllowed("GET", "/v2/point-value/latest/DP_1"));
    }

    @Test
    void theAsyncJobPollSurfaceIsReachable() {
        // Long stack operations return 201 + Location and are polled here. Excluding it would break
        // every long-running operation, so it is required rather than merely convenient.
        assertTrue(InferrixGatewayRoutes.isAllowed("GET", "/v2/system-actions/status/abc123"));
        assertTrue(InferrixGatewayRoutes.isAllowed("DELETE", "/v2/system-actions/cancel/abc123"));
        // The rest of the system-actions surface is not ours to drive. db-utils in particular
        // resolves a raw backup filename under the backup path -- which is why the two rules above
        // are exact paths rather than a /v2/system-actions prefix.
        assertFalse(InferrixGatewayRoutes.isAllowed("POST", "/v2/system-actions"));
        assertFalse(InferrixGatewayRoutes.isAllowed("GET", "/v2/system-actions"));
        assertFalse(InferrixGatewayRoutes.isAllowed("GET", "/v2/system-actions/db-utils/backup.zip"));
        assertFalse(InferrixGatewayRoutes.isAllowed("POST", "/v2/system-actions/db-utils/upload"));
    }

    @Test
    void aProcessEventHandlerIsRefusedByItsBody() {
        // The one payload this list inspects. /v2/event-handler has to be forwardable -- email, SMS
        // and set-point handlers are the feature -- but a PROCESS handler's activeProcessCommand is
        // handed to Runtime.getRuntime().exec on the gateway host, and stack fix D16 (2026-09-23)
        // put handler creation within reach of the gateway-configuration permission the platform
        // holds. Before that the gateway refused it and the path-only list was enough.
        String process = "{\"handlerType\":\"PROCESS_HANDLER\",\"name\":\"h\","
                + "\"activeProcessCommand\":\"/bin/sh -c id\"}";
        String email = "{\"handlerType\":\"EMAIL_HANDLER\",\"name\":\"h\"}";

        assertTrue(InferrixGatewayRoutes.isAllowed("POST", "/v2/event-handler"),
                "the route itself must stay forwardable, or three legitimate handler types die with it");
        assertFalse(InferrixGatewayRoutes.bodyIsAllowed("POST", "/v2/event-handler", process));
        assertFalse(InferrixGatewayRoutes.bodyIsAllowed("PUT", "/v2/event-handler/EH_1", process));
        assertFalse(InferrixGatewayRoutes.bodyIsAllowed("PATCH", "/v2/event-handler/EH_1", process));
        assertFalse(InferrixGatewayRoutes.bodyIsAllowed("POST", "/v2/event-handler/validate", process));
        assertTrue(InferrixGatewayRoutes.bodyIsAllowed("POST", "/v2/event-handler", email));
        assertTrue(InferrixGatewayRoutes.bodyIsAllowed("PUT", "/v2/event-handler/EH_1", email));

        // A write with no discriminator cannot be shown to be one of the three that are allowed --
        // and a PATCH of one field into an existing process handler is exactly that shape.
        assertFalse(InferrixGatewayRoutes.bodyIsAllowed("PATCH", "/v2/event-handler/EH_1",
                "{\"activeProcessCommand\":\"/bin/sh -c id\"}"));
        assertFalse(InferrixGatewayRoutes.bodyIsAllowed("POST", "/v2/event-handler", null));
        assertFalse(InferrixGatewayRoutes.bodyIsAllowed("POST", "/v2/event-handler", ""));
        assertFalse(InferrixGatewayRoutes.bodyIsAllowed("POST", "/v2/event-handler", "not json"));
        assertFalse(InferrixGatewayRoutes.bodyIsAllowed("POST", "/v2/event-handler", "[]"));
        // A non-textual discriminator is not a discriminator. Without the isTextual() check,
        // asText() on a number or null node returns "" and the handler sails through.
        assertFalse(InferrixGatewayRoutes.bodyIsAllowed("POST", "/v2/event-handler",
                "{\"handlerType\":null}"));
        assertFalse(InferrixGatewayRoutes.bodyIsAllowed("POST", "/v2/event-handler",
                "{\"handlerType\":7}"));

        // Jackson keeps the LAST duplicate key, and so does the gateway, so the two cannot be made
        // to read one body differently.
        assertFalse(InferrixGatewayRoutes.bodyIsAllowed("POST", "/v2/event-handler",
                "{\"handlerType\":\"EMAIL_HANDLER\",\"handlerType\":\"PROCESS_HANDLER\"}"));
        assertTrue(InferrixGatewayRoutes.bodyIsAllowed("POST", "/v2/event-handler",
                "{\"handlerType\":\"PROCESS_HANDLER\",\"handlerType\":\"EMAIL_HANDLER\"}"));
    }

    @Test
    void everyOtherRouteKeepsItsBodyUninspected() {
        // Deliberately one route, not a habit. The proxy forwards bodies opaquely; a list that
        // grew a second body rule would be claiming to understand payloads it does not.
        assertTrue(InferrixGatewayRoutes.bodyIsAllowed("POST", "/v2/data-source", "not json"));
        assertTrue(InferrixGatewayRoutes.bodyIsAllowed("PUT", "/v2/data-point/DP_1", null));
        assertTrue(InferrixGatewayRoutes.bodyIsAllowed("POST", "/v2/events/counts", "{}"));
        // Reads carry no body worth judging, and /v2/event-handler GET is a list.
        assertTrue(InferrixGatewayRoutes.bodyIsAllowed("GET", "/v2/event-handler", null));
        assertTrue(InferrixGatewayRoutes.bodyIsAllowed("DELETE", "/v2/event-handler/EH_1", null));
        // Not a handler route despite the prefix.
        assertTrue(InferrixGatewayRoutes.bodyIsAllowed("GET", "/v2/event-handler-types", null));
        assertFalse(InferrixGatewayRoutes.bodyIsAllowed(null, "/v2/event-handler", "{}"));
        assertFalse(InferrixGatewayRoutes.bodyIsAllowed("POST", null, "{}"));
    }

    @Test
    void theGatewayProvisioningEndpointIsNotForwarded() {
        // Spec 2.7: it registers the whole stack as one TB device, but only works because the
        // gateway holds Cortex tenant-admin credentials -- the custody inversion R1 removes.
        assertFalse(InferrixGatewayRoutes.isAllowed("PUT", "/v2/platform-integration/provision-gateway/1/gw"));
    }

    @Test
    void thePlatformLinkIsReadableAndItsBrokerSettingsWritable() {
        assertTrue(InferrixGatewayRoutes.isAllowed("GET", "/v2/platform-integration/mqtt-configuration"));
        assertTrue(InferrixGatewayRoutes.isAllowed("PUT", "/v2/platform-integration/mqtt-configuration"));
        assertTrue(InferrixGatewayRoutes.isAllowed("GET", "/v2/platform-integration/unprovisioned"));
        assertTrue(InferrixGatewayRoutes.isAllowed("GET", "/v2/platform-integration/provisioned"));
        assertTrue(InferrixGatewayRoutes.isAllowed("PUT", "/v2/platform-integration/provision/DP_1/12"));
        assertTrue(InferrixGatewayRoutes.isAllowed("DELETE", "/v2/platform-integration/provisioned/12"));
    }

    @Test
    void theTenantCredentialsAreReadableButNotWritableThroughCortex() {
        // server-details holds the ThingsBoard tenant-admin username and password that stack ask R1
        // exists to remove from the device. Stack 5.1.0 made the password write-only, so a read is
        // now safe and shows the operator what the link is. Writing them is a different matter:
        // Cortex is the last place that should be re-entrenching a credential it is trying to
        // delete, and an operator who genuinely needs to set them can do it on the gateway itself.
        assertTrue(InferrixGatewayRoutes.isAllowed("GET", "/v2/platform-integration/server-details"));
        assertFalse(InferrixGatewayRoutes.isAllowed("POST", "/v2/platform-integration/server-details"));

        // Same reasoning for creating platform entities from the device: reading the profile and
        // re-syncing it are fine, minting one with tenant credentials is not.
        assertTrue(InferrixGatewayRoutes.isAllowed("GET", "/v2/platform-integration/device-profile"));
        assertTrue(InferrixGatewayRoutes.isAllowed("GET", "/v2/platform-integration/device-profile/sync"));
        assertFalse(InferrixGatewayRoutes.isAllowed("POST", "/v2/platform-integration/device-profile"));
        assertFalse(InferrixGatewayRoutes.isAllowed("DELETE", "/v2/platform-integration/device-profile/3"));
    }

    @Test
    void aGetThatWritesOrReadsAdminStateIsNotTreatedAsACustomerRead() {
        // The stack serves one state-changing endpoint over GET: syncDeviceProfiles() calls
        // deviceProfileService.syncAndSaveDeviceProfile(). Classifying reads by HTTP method alone
        // would hand that to any device-READ holder with no tenant-admin gate, which is the exact
        // shape of a CSRF-able write. What stops it today is the gateway's own isAdmin() plus our
        // deliberately non-admin service account -- but this list is meant to be the boundary by
        // itself, and the day someone configures an admin token that defence evaporates silently.
        assertTrue(InferrixGatewayRoutes.requiresTenantAdmin(
                "GET", "/v2/platform-integration/device-profile/sync"));
        // Its sibling is a read -- but still an administrator's read, because the whole
        // platform-integration family exposes the link to this platform, including the
        // ThingsBoard URL and the tenant-admin username the gateway connects with. The
        // read/write distinction lives in changesGatewayState, not here.
        assertTrue(InferrixGatewayRoutes.requiresTenantAdmin(
                "GET", "/v2/platform-integration/device-profile"));
        assertFalse(InferrixGatewayRoutes.changesGatewayState(
                "GET", "/v2/platform-integration/device-profile"));
        // The ordinary configuration plane stays open to a customer user.
        assertFalse(InferrixGatewayRoutes.requiresTenantAdmin("GET", "/v2/data-source"));
        // Every other verb changes state by definition.
        for (String method : new String[]{"POST", "PUT", "PATCH", "DELETE"}) {
            assertTrue(InferrixGatewayRoutes.requiresTenantAdmin(method, "/v2/data-source"));
            assertTrue(InferrixGatewayRoutes.changesGatewayState(method, "/v2/data-source"));
        }

        // The two questions are separate, and this is the case that separates them: reading a
        // gateway's settings needs an administrator but is not a write, so the caller must not be
        // made to hold device WRITE for it.
        assertTrue(InferrixGatewayRoutes.requiresTenantAdmin("GET", "/v2/system-setting"));
        assertFalse(InferrixGatewayRoutes.changesGatewayState("GET", "/v2/system-setting"));
        // Whereas the sync GET is both.
        assertTrue(InferrixGatewayRoutes.changesGatewayState(
                "GET", "/v2/platform-integration/device-profile/sync"));

        // Admin families are matched by prefix, the opposite of the route patterns. A route added
        // to one of these later is swallowed into admin-only without anyone remembering to list
        // it -- which is the failure direction we want, since a looser prefix can only ever demand
        // MORE authority. Every currently declared route in these families is covered.
        for (String path : new String[]{"/v2/platform-integration/server-details",
                "/v2/platform-integration/mqtt-configuration", "/v2/platform-integration/provisioned",
                "/v2/server/languages", "/v2/system-setting/license-key", "/v2/anything-added-later"}) {
            boolean inFamily = path.startsWith("/v2/system-setting")
                    || path.startsWith("/v2/platform-integration") || path.startsWith("/v2/server");
            assertEquals(inFamily, InferrixGatewayRoutes.requiresTenantAdmin("GET", path), path);
        }
        // Case folding matches isAllowed's, so a lowercase verb cannot slip past as a read.
        assertTrue(InferrixGatewayRoutes.requiresTenantAdmin(
                "get", "/v2/platform-integration/device-profile/sync"));

        // Administrator reads on the gateway. SystemSettingsResource carries no @PreAuthorize at
        // all -- enforcement is ensureAdminRole one layer down -- and the settings keyspace holds
        // the outbound proxy host, the SMTP host and the backup path. A customer user with device
        // READ must not reach them through us just because the verb is GET.
        assertTrue(InferrixGatewayRoutes.requiresTenantAdmin("GET", "/v2/system-setting"));
        assertTrue(InferrixGatewayRoutes.requiresTenantAdmin("GET", "/v2/system-setting/license-key"));
        assertTrue(InferrixGatewayRoutes.requiresTenantAdmin("GET", "/v2/system-setting/emailSmtpHost"));
        assertTrue(InferrixGatewayRoutes.requiresTenantAdmin("GET", "/v2/server/network-interfaces"));
        // But the ordinary configuration plane stays readable by a customer user, which is the
        // whole point of having two levels rather than locking the page to tenant admins.
        assertFalse(InferrixGatewayRoutes.requiresTenantAdmin("GET", "/v2/publisher"));
        assertFalse(InferrixGatewayRoutes.requiresTenantAdmin("GET", "/v2/about"));
        assertFalse(InferrixGatewayRoutes.requiresTenantAdmin("GET", "/v2/model-schemas"));
    }
}
