/**
 * Copyright © 2016-2026 The Inferrix Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.thingsboard.server.service.inferrix.ilb;

/**
 * Why a container was rejected.
 *
 * <p><b>Declaration order is the wire contract.</b> Each constant's ordinal equals the value of the
 * matching {@code enum ilb_verify_err} in the firmware, because the device logs a rejection as a
 * bare number ("logic slot B: rejected, ilb_verify_err=9") and never returns it over REST. Keeping
 * the ordinals aligned is what lets {@link #fromDeviceCode(int)} turn a console line from the field
 * into something an operator can read. Append only; never reorder.
 */
public enum IlbVerifyError {

    OK("the program is valid"),
    TOO_SHORT("the file is smaller than an empty container"),
    BAD_MAGIC("the file does not start with ILB1"),
    BAD_FORMAT_VER("the container format version is not one this device runs"),
    BAD_FLAGS("the header flags field must be zero in this format version"),
    BAD_CRC("the trailing CRC-32 does not match the file"),
    RUNTIME_TOO_OLD("the program needs a newer logic runtime than the device has"),
    WRONG_PROFILE("the program targets a different hardware profile"),
    BAD_SECTIONS("the tag, code and const sections overlap, fall outside the file, or leave a gap before the CRC"),
    BAD_TAG_SECTION("a tag record is malformed, out of order, or there are more than 512 tags"),
    BAD_TAG_REF("an instruction references a tag that does not exist"),
    BAD_CONST_REF("an instruction references a constant outside the pool"),
    BAD_OPCODE("the code contains a byte that is not an opcode"),
    BAD_JUMP("a jump or call target is outside the code or not on an instruction boundary"),
    STACK_ERROR("the operand stack under- or overflows, or two paths reach the same instruction at different depths"),
    NO_END("the code does not end with END on every path"),
    BAD_RESOURCE("a timer, counter, edge or PID slot is out of range"),
    LIMITS("the code or const section is over its size limit"),
    BAD_BINDING("a tag is bound to an ICC point that the controller's configuration does not define");

    private final String detail;

    IlbVerifyError(String detail) {
        this.detail = detail;
    }

    public String getDetail() {
        return detail;
    }

    public boolean isOk() {
        return this == OK;
    }

    /**
     * The name behind an {@code ilb_verify_err=N} in a device boot log.
     *
     * <p>Returns null for a code this platform does not know, which is the honest answer when a
     * newer firmware has added one: better an explicit gap than a confidently wrong name.
     */
    public static IlbVerifyError fromDeviceCode(int code) {
        IlbVerifyError[] all = values();
        return code >= 0 && code < all.length ? all[code] : null;
    }
}
