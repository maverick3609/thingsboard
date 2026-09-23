// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.service.inferrix.gateway;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Builds the gateway's query string from typed, validated arguments.
 *
 * <p>The gateway has no {@code ?limit=}/{@code ?offset=} parameters. Its list endpoints read the
 * <em>raw query string</em> as an RQL expression — {@code RQLUtils.parseRQLtoAST(
 * request.getQueryString())} — and a custom argument resolver does that on every verb, not only on
 * GET. So "forward the caller's query string" would mean handing operator-typed text straight to a
 * parser that drives the device's database.
 *
 * <p>The spec settles that (§2.4): the platform constructs the RQL and nothing typed by a user ever
 * reaches the gateway's parser. This class is where that is true or not true. Two rules make it so:
 *
 * <ul>
 *   <li><b>Field names are validated, never escaped.</b> RQL has no quoting in an operand position,
 *       so there is nothing to escape <em>with</em>; anything that is not a plain identifier is
 *       refused.</li>
 *   <li><b>Values are percent-encoded, never validated.</b> They are operator search text and may
 *       legitimately contain anything. The gateway's {@code Converter} percent-decodes each value
 *       after the expression has been parsed, so encoding is what keeps a metacharacter inside the
 *       term it was typed into.</li>
 * </ul>
 *
 * <p>An escape here is not a broken page: {@code &} separates RQL terms, so a value carrying one
 * appends a term of the caller's choosing to a query the platform authored, and {@code ()} nest.
 */
public final class InferrixGatewayRql {

    /**
     * The largest page the platform will ask for.
     *
     * <p>Not a UI preference. A gateway is a small edge box that materialises the whole page before
     * answering, and the response then crosses a LAN into the platform's heap.
     */
    public static final int MAX_LIMIT = 500;

    /** Long enough for any name on a gateway; short enough that a search cannot become a payload. */
    private static final int MAX_VALUE_LENGTH = 128;

    /**
     * A model property name. Letters, digits and underscore, starting with a letter.
     *
     * <p>Narrower than the gateway's own property names need — it has none with a dot or a dash —
     * and deliberately so, because this is the position RQL cannot quote.
     */
    private static final Pattern FIELD = Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,63}");

    /**
     * RFC 3986 unreserved. Everything else is percent-encoded, including characters that would be
     * legal in a URL: {@code &()=<>|,} are RQL's own syntax, {@code :} would be read as a type cast
     * ({@code number:}, {@code date:}, {@code re:}), {@code *} is the wildcard {@code match} turns
     * into a SQL {@code %}, and {@code %} itself starts an escape.
     */
    private static final String UNRESERVED = "-._~";

    private final List<String> terms = new ArrayList<>();

    private InferrixGatewayRql() {
    }

    public static InferrixGatewayRql query() {
        return new InferrixGatewayRql();
    }

    /** {@code eq(field,string:value)} — an exact match, for scoping a list to its parent. */
    public InferrixGatewayRql eq(String field, String value) {
        terms.add("eq(" + field(field) + "," + value(value) + ")");
        return this;
    }

    /** {@code match(field,string:*value*)} — a case-insensitive contains, for a search box. */
    public InferrixGatewayRql match(String field, String value) {
        // The wildcards belong to the builder. A '*' inside the value is encoded, so an operator's
        // search term cannot widen itself into a scan the page then has to render.
        terms.add("match(" + field(field) + ",string:*" + encode(value) + "*)");
        return this;
    }

    /** {@code sort(+field)} or {@code sort(-field)}. */
    public InferrixGatewayRql sort(String field, boolean descending) {
        terms.add("sort(" + (descending ? '-' : '+') + field(field) + ")");
        return this;
    }

    /**
     * {@code name=true} — a plain boolean request parameter.
     *
     * <p>Not every gateway endpoint reads its query string as RQL. {@code enable-disable} declares
     * ordinary {@code @RequestParam} arguments and no {@code ASTNode}, so the value it needs cannot
     * be expressed as a filter term. A {@code boolean} is the only type allowed here for the
     * obvious reason: there are two of them, and neither can carry a metacharacter.
     *
     * <p>On an endpoint that <em>does</em> parse RQL this reads as {@code eq(name,true)}, which is
     * a legitimate filter — so a parameter sent to the wrong place narrows a list rather than
     * doing anything surprising.
     */
    public InferrixGatewayRql flag(String name, boolean value) {
        terms.add(field(name) + "=" + value);
        return this;
    }

    /** {@code limit(count,offset)} — the gateway's only paging mechanism. */
    public InferrixGatewayRql page(int limit, int offset) {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException(
                    "A page must hold between 1 and " + MAX_LIMIT + " rows, not " + limit);
        }
        if (offset < 0) {
            throw new IllegalArgumentException("A page offset cannot be negative");
        }
        terms.add("limit(" + limit + "," + offset + ")");
        return this;
    }

    /**
     * @return the query string, or {@code null} when nothing was asked for. Null rather than an
     *         empty string: the gateway's own default for an absent query is {@code limit(100)},
     *         so there is nothing to say, and the callers below treat null as "send no query at
     *         all" — which keeps the no-paging case free of a query string entirely.
     */
    public String build() {
        return terms.isEmpty() ? null : String.join("&", terms);
    }

    private static String field(String field) {
        if (field == null || !FIELD.matcher(field).matches()) {
            throw new IllegalArgumentException("Not a gateway model property name: " + field);
        }
        return field;
    }

    /**
     * The explicit {@code string:} type cast is load-bearing, not tidiness. RQL's default converter
     * auto-types its values: a point named {@code 00123} would arrive as the number 123 and match
     * nothing, and one named {@code true} as a boolean. {@code match} is worse than wrong — the
     * gateway's {@code RQLToCondition} casts the argument to {@code String} outright, so an
     * auto-typed value is a {@code ClassCastException} there.
     *
     * <p>The cost is that an equality filter on a numeric column compares a string to it. No list
     * in this feature filters on one, and a wrong answer that is loud beats one that is silent.
     */
    private static String value(String value) {
        return "string:" + encode(value);
    }

    private static String encode(String value) {
        if (value == null) {
            throw new IllegalArgumentException("A filter value cannot be null");
        }
        if (value.length() > MAX_VALUE_LENGTH) {
            throw new IllegalArgumentException(
                    "A filter value may be at most " + MAX_VALUE_LENGTH + " characters");
        }
        StringBuilder encoded = new StringBuilder(value.length());
        // Hand-rolled rather than URLEncoder.encode, which writes a space as '+'. That would be
        // wrong here: the gateway's Converter percent-escapes '+' *before* decoding, exactly so a
        // literal plus survives -- so a '+' from URLEncoder stays a '+' and a search for
        // "hot water" silently looks for "hot+water".
        for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
            char c = (char) (b & 0xFF);
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || UNRESERVED.indexOf(c) >= 0) {
                encoded.append(c);
            } else {
                encoded.append('%').append(String.format("%02X", b & 0xFF));
            }
        }
        return encoded.toString();
    }
}
