// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.service.inferrix.gateway;

import org.apache.hc.core5.http.ClassicHttpRequest;
import org.apache.hc.core5.http.io.support.ClassicRequestBuilder;
import org.junit.jupiter.api.Test;

import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The gateway parses its raw query string as RQL, on every verb. This builder is the only thing
 * allowed to produce one, so it is the whole of the query-side security boundary — the same role
 * {@link InferrixGatewayRoutes} plays for the path.
 *
 * <p>What an escape would buy an attacker is not "a broken page": RQL's {@code &} separates terms,
 * so a value that carries one appends a term of the caller's choosing to a query the platform
 * authored. {@code (} and {@code )} let it nest. Between them that is arbitrary filtering and
 * arbitrary paging over whatever the endpoint reads.
 */
class InferrixGatewayRqlTest {

    /**
     * Everything the builder may ever emit. Deliberately written as one flat pattern rather than
     * per-method assertions: a future method that forgets to encode something is caught by the
     * shape of the output, not by whether someone remembered to write a test for that method.
     *
     * <p>Note what is absent: {@code &} appears only as the term separator this pattern places,
     * and no term body may contain {@code &}, {@code (}, {@code )}, {@code =}, {@code <},
     * {@code >}, {@code |} or a bare {@code ,}.
     */
    private static final Pattern EMITTED = Pattern.compile(
            "(?:eq\\([A-Za-z][A-Za-z0-9_]*,string:[A-Za-z0-9._~%-]*\\)"
                    + "|match\\([A-Za-z][A-Za-z0-9_]*,string:\\*[A-Za-z0-9._~%-]*\\*\\)"
                    + "|sort\\([-+][A-Za-z][A-Za-z0-9_]*\\)"
                    + "|limit\\([0-9]+,[0-9]+\\)"
                    + "|[A-Za-z][A-Za-z0-9_]*=(?:true|false))"
                    + "(?:&(?:eq\\([A-Za-z][A-Za-z0-9_]*,string:[A-Za-z0-9._~%-]*\\)"
                    + "|match\\([A-Za-z][A-Za-z0-9_]*,string:\\*[A-Za-z0-9._~%-]*\\*\\)"
                    + "|sort\\([-+][A-Za-z][A-Za-z0-9_]*\\)"
                    + "|limit\\([0-9]+,[0-9]+\\)"
                    + "|[A-Za-z][A-Za-z0-9_]*=(?:true|false)))*");

    @Test
    void anEmptyQueryIsNoQueryAtAll() {
        // Not "limit(100,0)". The gateway's own default for an absent query string is limit(100),
        // so emitting nothing is both shorter and exactly equivalent -- and it keeps the "no query
        // string" case genuinely free of a query string, which is what the proxy's path-only
        // allowlist check assumes.
        assertThat(InferrixGatewayRql.query().build()).isNull();
    }

    @Test
    void pagingIsLimitAndOffset() {
        assertThat(InferrixGatewayRql.query().page(50, 100).build()).isEqualTo("limit(50,100)");
        assertThat(InferrixGatewayRql.query().page(1, 0).build()).isEqualTo("limit(1,0)");
    }

    @Test
    void aPageSizeOutsideTheCapIsRefused() {
        // Refused, not clamped. A caller asking for 100000 rows has a bug or an intent; silently
        // serving 500 hides both, and the UI would page as though it had received the rest.
        assertThatThrownBy(() -> InferrixGatewayRql.query().page(0, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> InferrixGatewayRql.query().page(InferrixGatewayRql.MAX_LIMIT + 1, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> InferrixGatewayRql.query().page(10, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void equalityCarriesAnExplicitStringType() {
        // string: is not decoration. RQL's default converter auto-types its values, so a point
        // named "00123" would arrive as the number 123 and match nothing, and a data source named
        // "true" would arrive as a boolean. Worse for match(): RQLToCondition casts the argument
        // to String outright, so an auto-typed number is a ClassCastException on the gateway.
        assertThat(InferrixGatewayRql.query().eq("dataSourceXid", "DS_1").build())
                .isEqualTo("eq(dataSourceXid,string:DS_1)");
    }

    @Test
    void searchIsAWildcardMatchWithTheValueEscapedInside() {
        assertThat(InferrixGatewayRql.query().match("name", "boiler").build())
                .isEqualTo("match(name,string:*boiler*)");
        // The wildcards are the builder's, never the value's: a value carrying '*' is encoded, so
        // an operator cannot widen their own search into a full scan by accident.
        assertThat(InferrixGatewayRql.query().match("name", "a*b").build())
                .isEqualTo("match(name,string:*a%2Ab*)");
    }

    @Test
    void sortCarriesItsDirectionAsAPrefix() {
        assertThat(InferrixGatewayRql.query().sort("name", false).build()).isEqualTo("sort(+name)");
        assertThat(InferrixGatewayRql.query().sort("name", true).build()).isEqualTo("sort(-name)");
    }

    @Test
    void termsJoinWithAmpersand() {
        String query = InferrixGatewayRql.query()
                .eq("dataSourceXid", "DS_1")
                .match("name", "boiler")
                .sort("name", true)
                .page(25, 50)
                .build();
        assertThat(query).isEqualTo("eq(dataSourceXid,string:DS_1)&match(name,string:*boiler*)"
                + "&sort(-name)&limit(25,50)");
        assertThat(query).matches(EMITTED);
    }

    @Test
    void aFieldNameThatIsNotAFieldNameIsRefused() {
        // A field name is the one part that cannot be escaped: RQL has no quoting for an operand
        // position, so anything but a plain identifier has to be refused outright. These are the
        // shapes that would otherwise append a term, close the call early, or reach the parser's
        // comparison-operator branch.
        for (String field : new String[]{"name)&limit(1", "na me", "na,me", "na=me", "-name",
                "1name", "", "name(", "name|x", "na<me", "n".repeat(65)}) {
            assertThatThrownBy(() -> InferrixGatewayRql.query().eq(field, "x"))
                    .as(field)
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> InferrixGatewayRql.query().sort(field, false))
                    .as(field)
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> InferrixGatewayRql.query().eq(null, "x"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aValueCannotEscapeTheTermItSitsIn() {
        // Every RQL metacharacter, plus the ':' that would otherwise be read as a type cast and
        // the '%' that starts an escape of the caller's own.
        String hostile = "a)&limit(9999)&eq(x,y|z<>=,:%+ \"'\\";
        String query = InferrixGatewayRql.query().eq("name", hostile).build();
        assertThat(query).matches(EMITTED);
        assertThat(query).doesNotContain("limit(9999)");
        // One '(' and one ')': the ones this builder wrote.
        assertThat(query.chars().filter(c -> c == '(').count()).isEqualTo(1);
        assertThat(query.chars().filter(c -> c == ')').count()).isEqualTo(1);
        assertThat(query.indexOf('&')).isNegative();
        // And the ':' that survives is the type cast's, not the value's.
        assertThat(query.chars().filter(c -> c == ':').count()).isEqualTo(1);

        assertThat(InferrixGatewayRql.query().match("name", hostile).build()).matches(EMITTED);
    }

    @Test
    void aSpaceIsEncodedAsPercentTwentyNotAsPlus() {
        // URLEncoder.encode would write '+' here, and it would be wrong: the gateway's converter
        // percent-escapes '+' *before* decoding precisely so that a '+' survives as a literal
        // plus. A search for "hot water" would then look for "hot+water" and find nothing.
        assertThat(InferrixGatewayRql.query().match("name", "hot water").build())
                .isEqualTo("match(name,string:*hot%20water*)");
        assertThat(InferrixGatewayRql.query().eq("name", "a+b").build())
                .isEqualTo("eq(name,string:a%2Bb)");
    }

    @Test
    void aNonAsciiValueIsEncodedAsUtf8() {
        // The gateway decodes with UTF-8, so anything else would silently corrupt a name.
        assertThat(InferrixGatewayRql.query().eq("name", "café").build())
                .isEqualTo("eq(name,string:caf%C3%A9)");
    }

    @Test
    void anOverlongValueIsRefused() {
        assertThatThrownBy(() -> InferrixGatewayRql.query().eq("name", "x".repeat(129)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> InferrixGatewayRql.query().match("name", "x".repeat(129)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> InferrixGatewayRql.query().eq("name", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aBooleanParameterIsNotAFilterTerm() {
        // The enable-disable routes declare ordinary @RequestParam arguments and parse no RQL, so
        // "enabled" has to travel as itself. A boolean is the only type allowed: there are two
        // values and neither can carry a metacharacter.
        assertThat(InferrixGatewayRql.query().flag("enabled", true).build()).isEqualTo("enabled=true");
        assertThat(InferrixGatewayRql.query().flag("enabled", true).flag("restart", false).build())
                .isEqualTo("enabled=true&restart=false");
        assertThatThrownBy(() -> InferrixGatewayRql.query().flag("enabled)&x(", true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theBuiltQueryReachesTheWireByteForByte() throws Exception {
        // The half this file cannot prove on its own. Every escape here is deliberate, so an HTTP
        // client that re-encoded them would turn a searched-for '%' into '%25', and one that
        // decoded them would let a value's metacharacter back out into the expression the gateway
        // parses. Neither would fail any test above -- the string leaving build() would still be
        // right, and the one on the wire would not.
        String query = InferrixGatewayRql.query().match("name", "a b%c(").page(25, 0).build();
        String uri = InferrixGatewayClient.requestUri("https://gw.local:443", "/v2/data-point", query);
        ClassicHttpRequest request = ClassicRequestBuilder.get().setUri(uri).build();

        assertThat(request.getRequestUri()).isEqualTo("/rest/v2/data-point?" + query);
        assertThat(request.getRequestUri()).contains("%20").contains("%25").contains("%28");
    }

    @Test
    void noQueryMeansNoQuestionMark() {
        assertThat(InferrixGatewayClient.requestUri("https://gw.local:443", "/v2/about", null))
                .isEqualTo("https://gw.local:443/rest/v2/about");
        assertThat(InferrixGatewayClient.requestUri("https://gw.local:443", "/v2/about", ""))
                .isEqualTo("https://gw.local:443/rest/v2/about");
    }

    @Test
    void whatThisBuildsIsNeverJudgedByTheRouteAllowlist() {
        // The two halves must stay apart. isAllowed() refuses a path containing '?', so if a query
        // were ever appended to the path before the check, every paged call would 403 -- and if it
        // were appended after, the check would be judging a different string from the one sent.
        // The query travels as its own argument for exactly this reason.
        String query = InferrixGatewayRql.query().page(25, 0).build();
        assertThat(InferrixGatewayRoutes.isAllowed("GET", "/v2/data-source")).isTrue();
        assertThat(InferrixGatewayRoutes.isAllowed("GET", "/v2/data-source?" + query)).isFalse();
    }
}
