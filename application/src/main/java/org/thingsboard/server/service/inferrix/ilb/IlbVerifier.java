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

import java.util.Arrays;
import java.util.function.IntPredicate;
import java.util.zip.CRC32;

import static org.thingsboard.server.service.inferrix.ilb.IlbFormat.*;

/**
 * The device's boot-time container check, run on the platform before anything is uploaded.
 *
 * <p>A port of the firmware's {@code ilb_verify()}, rule for rule and in the same order, so the
 * first thing it complains about is the first thing the device would have. It exists because the
 * device's answer to a bad program is silence: a rejected container is reported over REST as
 * {@code state 0} — identical to never having staged one — and the actual reason appears only on a
 * serial console nobody is watching. Catching it here is the difference between a message naming
 * the broken tag and an operator power-cycling a controller wondering why nothing happened.
 *
 * <p>This is deliberately a second implementation of a check the device already performs, which is
 * duplication worth paying for: the alternative is shipping bytes we have not checked to hardware
 * that will not tell us why it refused them. Where the two disagree, the <b>device is right</b> and
 * this is the bug — {@code IlbVerifierAgreementTest} pins them together against the real C verifier.
 *
 * <p>Not checked here, because the platform cannot know it: stack <i>type</i> consistency, which the
 * firmware also leaves to its typed runtime cells (a known v0.2 gap in {@code ilb_verify.c}). A
 * program that stores an INT into a BOOL tag passes both verifiers and faults at run time.
 */
public final class IlbVerifier {

    private IlbVerifier() {}

    /** Marks an instruction offset the depth walk has not reached yet. */
    private static final int DEPTH_UNSEEN = -1;

    /**
     * The outcome, and on success everything the header declared.
     *
     * @param error       why it was rejected, or {@link IlbVerifyError#OK}
     * @param detail      a sentence naming the specific record or offset at fault, or null
     */
    public record Result(IlbVerifyError error, String detail, long programId, long programVersion,
                         int profile, long scanPeriodMs, int tagCount, int codeLength,
                         int constCount) {

        public boolean isOk() {
            return error.isOk();
        }

        /**
         * What to show an operator: the rule that failed, plus the specific thing that broke it.
         *
         * <p>Deliberately ASCII. This string travels two ways out: as JSON from the compile
         * endpoint, which is UTF-8, and inside a ThingsboardException from the build endpoint,
         * where TB's error path replaces anything non-ASCII with a literal '?'. An em dash here
         * came back mangled on one of the two routes.
         */
        public String message() {
            return detail == null ? error.getDetail() : error.getDetail() + " - " + detail;
        }
    }

    private static Result fail(IlbVerifyError error, String detail) {
        return new Result(error, detail, 0, 0, 0, 0, 0, 0, 0);
    }

    /**
     * Verifies a complete container.
     *
     * @param image        the whole file, header through trailing CRC
     * @param deviceProfile the controller's own profile id, from {@code GET /api/v1/info}
     * @param pointExists  resolves an ICC-point binding against the controller's active config, or
     *                     null to skip that check — the same optional hook the firmware takes. Null
     *                     means a program can still be rejected at boot for a point we did not
     *                     check, so pass one whenever the config is known.
     */
    public static Result verify(byte[] image, int deviceProfile, IntPredicate pointExists) {
        if (image == null || image.length < MIN_FILE_SIZE) {
            return fail(IlbVerifyError.TOO_SHORT,
                    "a container is at least " + MIN_FILE_SIZE + " bytes");
        }
        int len = image.length;
        if (len > MAX_FILE_SIZE) {
            return fail(IlbVerifyError.LIMITS, "the file is " + len + " bytes and the logic slot is "
                    + MAX_FILE_SIZE);
        }

        // Rule 1: magic, format version, flags, CRC.
        for (int i = 0; i < MAGIC.length; i++) {
            if (image[i] != MAGIC[i]) {
                return fail(IlbVerifyError.BAD_MAGIC, null);
            }
        }
        int formatVersion = rd16(image, OFF_FORMAT_VERSION);
        if (formatVersion != FORMAT_VERSION) {
            return fail(IlbVerifyError.BAD_FORMAT_VER,
                    "the file declares version " + formatVersion + " and this device runs " + FORMAT_VERSION);
        }
        int flags = rd16(image, OFF_FLAGS);
        if (flags != 0) {
            return fail(IlbVerifyError.BAD_FLAGS, "flags are 0x" + Integer.toHexString(flags));
        }
        long storedCrc = rd32(image, len - CRC_SIZE);
        CRC32 crc = new CRC32();
        crc.update(image, 0, len - CRC_SIZE);
        if (crc.getValue() != storedCrc) {
            return fail(IlbVerifyError.BAD_CRC, String.format(
                    "the file computes %08x and carries %08x", crc.getValue(), storedCrc));
        }

        // Rule 2: runtime version.
        int minRuntime = rd16(image, OFF_MIN_RUNTIME);
        if (minRuntime > RUNTIME_VERSION) {
            return fail(IlbVerifyError.RUNTIME_TOO_OLD,
                    "the program asks for runtime " + minRuntime + " and the device has " + RUNTIME_VERSION);
        }

        // Rule 3: hardware profile.
        int profile = rd16(image, OFF_PROFILE);
        if (profile != deviceProfile) {
            return fail(IlbVerifyError.WRONG_PROFILE,
                    "the program targets profile " + profile + " and this controller reports " + deviceProfile);
        }

        // Rule 4: section geometry. Offsets and lengths are u32 on the wire, so they are read as
        // long and compared as long: a length of 0xFFFFFFFF must read as four billion and fail the
        // bounds check, not as -1 and slip past it.
        long tagOff = rd32(image, OFF_TAG_OFFSET);
        long tagLen = rd32(image, OFF_TAG_LENGTH);
        long codeOff = rd32(image, OFF_CODE_OFFSET);
        long codeLen = rd32(image, OFF_CODE_LENGTH);
        long constOff = rd32(image, OFF_CONST_OFFSET);
        long constLen = rd32(image, OFF_CONST_LENGTH);
        long bodyEnd = len - CRC_SIZE;

        long[][] sections = {{tagOff, tagLen}, {codeOff, codeLen}, {constOff, constLen}};
        String[] names = {"tag", "code", "const"};
        for (int i = 0; i < 3; i++) {
            long off = sections[i][0], sl = sections[i][1];
            if (off < HEADER_SIZE || off > bodyEnd || sl > bodyEnd - off) {
                return fail(IlbVerifyError.BAD_SECTIONS, "the " + names[i] + " section at " + off
                        + " for " + sl + " bytes does not fit inside the file");
            }
            for (int j = i + 1; j < 3; j++) {
                if (overlap(off, sl, sections[j][0], sections[j][1])) {
                    return fail(IlbVerifyError.BAD_SECTIONS,
                            "the " + names[i] + " and " + names[j] + " sections overlap");
                }
            }
        }

        // The packing invariant: the highest non-empty section end must land exactly on the CRC.
        // The device recovers a slot-stored container's length from the header this way, so a
        // trailing gap is not cosmetic — it makes the boot-time CRC read the wrong number of bytes.
        long maxEnd = HEADER_SIZE;
        for (long[] section : sections) {
            if (section[1] > 0 && section[0] + section[1] > maxEnd) {
                maxEnd = section[0] + section[1];
            }
        }
        if (bodyEnd != maxEnd) {
            return fail(IlbVerifyError.BAD_SECTIONS, "the sections end at " + maxEnd
                    + " but the CRC starts at " + bodyEnd + "; there must be no gap before it");
        }

        if (codeLen == 0 || codeLen > MAX_CODE_LEN || constLen > MAX_CONST_LEN) {
            return fail(IlbVerifyError.LIMITS, "code is " + codeLen + " bytes (limit " + MAX_CODE_LEN
                    + ") and the const pool is " + constLen + " (limit " + MAX_CONST_LEN + ")");
        }
        if ((constLen % CONST_ENTRY_SIZE) != 0) {
            return fail(IlbVerifyError.BAD_SECTIONS,
                    "the const pool is " + constLen + " bytes, not a multiple of " + CONST_ENTRY_SIZE);
        }

        // Tag section: whole records, dense indices, known type/class/binding.
        if ((tagLen % TAG_RECORD_SIZE) != 0) {
            return fail(IlbVerifyError.BAD_TAG_SECTION,
                    "the tag section is " + tagLen + " bytes, not a multiple of " + TAG_RECORD_SIZE);
        }
        int tagCount = (int) (tagLen / TAG_RECORD_SIZE);
        if (tagCount > MAX_TAGS) {
            return fail(IlbVerifyError.BAD_TAG_SECTION,
                    "the program declares " + tagCount + " tags and the limit is " + MAX_TAGS);
        }
        for (int i = 0; i < tagCount; i++) {
            int rec = (int) tagOff + i * TAG_RECORD_SIZE;
            int declared = rd16(image, rec + TAG_OFF_INDEX);
            if (declared != i) {
                return fail(IlbVerifyError.BAD_TAG_SECTION, "tag records must be numbered 0 upwards"
                        + " in order; record " + i + " calls itself " + declared);
            }
            int type = image[rec + TAG_OFF_TYPE] & 0xFF;
            int cls = image[rec + TAG_OFF_CLASS] & 0xFF;
            if (type > T_TIME || cls > C_SYSTEM) {
                return fail(IlbVerifyError.BAD_TAG_SECTION,
                        tagName(image, rec) + " has data type " + type + " and class " + cls);
            }
            int binding = rd16(image, rec + TAG_OFF_BINDING);
            switch (binding) {
                case B_NONE, B_LOCAL_DI, B_LOCAL_DO, B_LOCAL_AI, B_LOCAL_AO, B_SYSTEM -> { }
                case B_ICC_POINT -> {
                    int pointId = rd16(image, rec + TAG_OFF_ADDRESS);
                    if (pointExists != null && !pointExists.test(pointId)) {
                        return fail(IlbVerifyError.BAD_BINDING, tagName(image, rec)
                                + " is bound to point " + pointId + ", which the controller's"
                                + " configuration does not define");
                    }
                }
                default -> {
                    return fail(IlbVerifyError.BAD_TAG_SECTION, tagName(image, rec)
                            + " uses binding 0x" + String.format("%04x", binding)
                            + ", which this format version does not accept");
                }
            }
        }

        int constCount = (int) (constLen / CONST_ENTRY_SIZE);
        for (int i = 0; i < constCount; i++) {
            long type = rd32(image, (int) constOff + i * CONST_ENTRY_SIZE);
            if (type > T_TIME) {
                return fail(IlbVerifyError.BAD_CONST_REF,
                        "const entry " + i + " declares data type " + type);
            }
        }

        // Rule 6: instruction boundaries and stack depth.
        Result code = verifyCode(image, (int) codeOff, (int) codeLen, tagCount, constCount);
        if (!code.isOk()) {
            return code;
        }

        return new Result(IlbVerifyError.OK, null,
                rd32(image, OFF_PROGRAM_ID), rd32(image, OFF_PROGRAM_VERSION),
                profile, rd32(image, OFF_SCAN_PERIOD_MS), tagCount, (int) codeLen, constCount);
    }

    /**
     * Decodes the code section, then proves the operand stack.
     *
     * <p>Two passes, as the firmware does them. The first walks instructions linearly to record
     * where each one starts, reject unknown opcodes and range-check operands. The second is a
     * worklist over the instruction graph proving depth is consistent: a jump into the middle of an
     * instruction is caught by the boundary map from pass one, and two paths meeting at different
     * depths is a rejection rather than a guess.
     */
    private static Result verifyCode(byte[] image, int codeOff, int codeLen,
                                     int tagCount, int constCount) {
        if (codeLen == 0 || codeLen > MAX_CODE_LEN) {
            return fail(IlbVerifyError.LIMITS, "the code section is " + codeLen + " bytes");
        }
        boolean[] boundary = new boolean[codeLen];

        int pc = 0;
        int lastOp = 0;
        while (pc < codeLen) {
            int op = image[codeOff + pc] & 0xFF;
            int operandSize = operandSize(op);
            if (operandSize < 0) {
                return fail(IlbVerifyError.BAD_OPCODE,
                        String.format("byte 0x%02x at code offset %d", op, pc));
            }
            if (pc + 1 + operandSize > codeLen) {
                return fail(IlbVerifyError.NO_END, "the last instruction at code offset " + pc
                        + " runs past the end of the section");
            }
            boundary[pc] = true;
            int operand = operandSize >= 2 ? rd16(image, codeOff + pc + 1) : 0;
            Result operandError = checkOperand(op, operand, pc, tagCount, constCount);
            if (operandError != null) {
                return operandError;
            }
            lastOp = op;
            pc += 1 + operandSize;
        }
        if (lastOp != OP_END) {
            return fail(IlbVerifyError.NO_END, "the code section's last instruction is "
                    + String.format("0x%02x", lastOp) + ", not END");
        }

        int[] depth = new int[codeLen];
        Arrays.fill(depth, DEPTH_UNSEEN);
        int[] work = new int[codeLen];
        int nWork = 0;
        depth[0] = 0;
        work[nWork++] = 0;

        while (nWork > 0) {
            pc = work[--nWork];
            int d = depth[pc];
            int op = image[codeOff + pc] & 0xFF;
            int operandSize = operandSize(op);
            int next = pc + 1 + operandSize;
            int operand = operandSize >= 2 ? rd16(image, codeOff + pc + 1) : 0;

            int pops = pops(op);
            int pushes = pushes(op);
            if (pops < 0 || pushes < 0) {
                return fail(IlbVerifyError.BAD_OPCODE,
                        String.format("byte 0x%02x at code offset %d", op, pc));
            }
            if (d < pops) {
                return fail(IlbVerifyError.STACK_ERROR, "the instruction at code offset " + pc
                        + " takes " + pops + " value(s) and the stack holds " + d);
            }
            int depthOut = d - pops + pushes;
            if (depthOut > STACK_DEPTH) {
                return fail(IlbVerifyError.STACK_ERROR, "the stack reaches " + depthOut
                        + " at code offset " + pc + " and the limit is " + STACK_DEPTH);
            }

            int[] successors = new int[2];
            int nSucc = 0;
            if (op == OP_END || op == OP_RET) {
                nSucc = 0;
            } else if (op == OP_JMP || op == OP_JMPZ || op == OP_CALL) {
                int target = next + (short) operand;
                if (target < 0 || target >= codeLen || !boundary[target]) {
                    return fail(IlbVerifyError.BAD_JUMP, "the jump at code offset " + pc
                            + " targets " + target + ", which is not the start of an instruction");
                }
                successors[nSucc++] = target;
                // A conditional jump and a call both continue at the following instruction too; a
                // subroutine shares the caller's operand stack, so both paths enter at the same depth.
                if (op != OP_JMP) {
                    successors[nSucc++] = next;
                }
            } else {
                successors[nSucc++] = next;
            }

            for (int i = 0; i < nSucc; i++) {
                int s = successors[i];
                if (s >= codeLen) {
                    return fail(IlbVerifyError.NO_END, "the instruction at code offset " + pc
                            + " runs off the end of the code section");
                }
                if (depth[s] == DEPTH_UNSEEN) {
                    depth[s] = depthOut;
                    work[nWork++] = s;
                } else if (depth[s] != depthOut) {
                    return fail(IlbVerifyError.STACK_ERROR, "code offset " + s + " is reached with "
                            + depth[s] + " value(s) on the stack by one path and " + depthOut
                            + " by another");
                }
            }
        }
        return new Result(IlbVerifyError.OK, null, 0, 0, 0, 0, 0, 0, 0);
    }

    /**
     * True if any tag binds an ICC point.
     *
     * <p>Asked before verification so the caller knows whether it is worth a round trip to read the
     * controller's points: a program driving local channels needs none, and every read of the device
     * costs a full TLS handshake. Deliberately tolerant of a malformed container — it answers false
     * rather than throwing, and leaves saying why to {@link #verify}.
     */
    public static boolean bindsIccPoint(byte[] image) {
        if (image == null || image.length < MIN_FILE_SIZE) {
            return false;
        }
        long tagOff = rd32(image, OFF_TAG_OFFSET);
        long tagLen = rd32(image, OFF_TAG_LENGTH);
        if (tagOff < HEADER_SIZE || tagLen <= 0 || tagOff + tagLen > image.length) {
            return false;
        }
        for (long rec = tagOff; rec + TAG_RECORD_SIZE <= tagOff + tagLen; rec += TAG_RECORD_SIZE) {
            if (rd16(image, (int) rec + TAG_OFF_BINDING) == B_ICC_POINT) {
                return true;
            }
        }
        return false;
    }

    /** Range-checks one instruction's operand; null when it is in range. */
    private static Result checkOperand(int op, int operand, int pc, int tagCount, int constCount) {
        return switch (op) {
            case OP_LD, OP_ST, OP_SET, OP_RST -> operand < tagCount ? null
                    : fail(IlbVerifyError.BAD_TAG_REF, "code offset " + pc + " uses tag " + operand
                            + " and the program declares " + tagCount);
            case OP_LDC -> operand < constCount ? null
                    : fail(IlbVerifyError.BAD_CONST_REF, "code offset " + pc + " uses const "
                            + operand + " and the pool holds " + constCount);
            // SCALE reads three consecutive entries (multiplier, divisor, offset), so the last one
            // has to be in the pool too.
            case OP_SCALE -> operand + 2 < constCount ? null
                    : fail(IlbVerifyError.BAD_CONST_REF, "the SCALE at code offset " + pc
                            + " needs const entries " + operand + " to " + (operand + 2)
                            + " and the pool holds " + constCount);
            case OP_RTRIG, OP_FTRIG -> operand < MAX_EDGES ? null
                    : slotError(pc, "edge", operand, MAX_EDGES);
            case OP_TON, OP_TOF, OP_TP -> operand < MAX_TIMERS ? null
                    : slotError(pc, "timer", operand, MAX_TIMERS);
            case OP_CTU, OP_CTD -> operand < MAX_COUNTERS ? null
                    : slotError(pc, "counter", operand, MAX_COUNTERS);
            case OP_PID -> operand < MAX_PIDS ? null
                    : slotError(pc, "PID", operand, MAX_PIDS);
            default -> null;
        };
    }

    private static Result slotError(int pc, String kind, int slot, int limit) {
        return fail(IlbVerifyError.BAD_RESOURCE, "code offset " + pc + " uses " + kind + " slot "
                + slot + " and there are " + limit);
    }

    /** A tag's name for an error message, falling back to its index when the name is blank. */
    private static String tagName(byte[] image, int record) {
        int end = record + TAG_OFF_NAME;
        while (end < record + TAG_RECORD_SIZE && image[end] != 0) {
            end++;
        }
        int index = rd16(image, record + TAG_OFF_INDEX);
        int length = end - (record + TAG_OFF_NAME);
        return length == 0 ? "tag " + index
                : "tag " + index + " (" + new String(image, record + TAG_OFF_NAME, length,
                        java.nio.charset.StandardCharsets.UTF_8) + ")";
    }

    private static boolean overlap(long aOff, long aLen, long bOff, long bLen) {
        // A zero-length section is a placeholder offset, not a region, so it aliases nothing.
        return aLen != 0 && bLen != 0 && aOff < bOff + bLen && bOff < aOff + aLen;
    }

    private static int rd16(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8);
    }

    private static long rd32(byte[] b, int off) {
        return (b[off] & 0xFFL) | ((b[off + 1] & 0xFFL) << 8)
                | ((b[off + 2] & 0xFFL) << 16) | ((b[off + 3] & 0xFFL) << 24);
    }
}
