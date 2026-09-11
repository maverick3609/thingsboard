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
 * The ILB container format: every constant, opcode and limit the device enforces.
 *
 * <p>This is a transcription of the firmware's {@code lib/ilb_vm} headers, and it is the only place
 * in the platform that may hold these numbers. It exists because the controller has no on-board
 * compiler by design (INTEGRATION-API §7) and answers a rejected program with silence: a container
 * that fails {@code ilb_verify} at boot is reported over REST as {@code state 0}, byte-identical to
 * having staged nothing at all. The platform therefore has to be the thing that says no.
 *
 * <p>Everything here is bound to <b>format version 1</b>. The device checks the container's format
 * version against its own, so a future firmware that changes the layout will reject our output
 * rather than misread it — but it will do so silently, which is why {@link #FORMAT_VERSION} must be
 * bumped deliberately alongside a firmware that wants a new one.
 */
public final class IlbFormat {

    private IlbFormat() {}

    public static final byte[] MAGIC = {'I', 'L', 'B', '1'};
    public static final int FORMAT_VERSION = 0x0001;
    public static final int RUNTIME_VERSION = 0x0001;
    public static final int HEADER_SIZE = 0x40;
    public static final int CRC_SIZE = 4;
    public static final int MIN_FILE_SIZE = HEADER_SIZE + CRC_SIZE;

    /** The logic slot is 64 KB; a container larger than its slot can never be staged. */
    public static final int MAX_FILE_SIZE = 64 * 1024;

    public static final int MAX_CODE_LEN = 32 * 1024;
    public static final int MAX_CONST_LEN = 8 * 1024;
    public static final int CONST_ENTRY_SIZE = 8;

    public static final int MAX_TAGS = 512;
    public static final int TAG_RECORD_SIZE = 32;
    public static final int TAG_NAME_LEN = 16;

    public static final int STACK_DEPTH = 64;
    public static final int MAX_TIMERS = 64;
    public static final int MAX_COUNTERS = 64;
    public static final int MAX_EDGES = 256;
    public static final int MAX_PIDS = 16;

    // Header field offsets.
    public static final int OFF_FORMAT_VERSION = 0x04;
    public static final int OFF_FLAGS = 0x06;
    public static final int OFF_PROGRAM_ID = 0x08;
    public static final int OFF_PROGRAM_VERSION = 0x0C;
    public static final int OFF_MIN_RUNTIME = 0x10;
    public static final int OFF_PROFILE = 0x12;
    public static final int OFF_TAG_OFFSET = 0x14;
    public static final int OFF_TAG_LENGTH = 0x18;
    public static final int OFF_CODE_OFFSET = 0x1C;
    public static final int OFF_CODE_LENGTH = 0x20;
    public static final int OFF_CONST_OFFSET = 0x24;
    public static final int OFF_CONST_LENGTH = 0x28;
    public static final int OFF_SCAN_PERIOD_MS = 0x2C;
    public static final int OFF_BUILD_UUID = 0x30;
    public static final int BUILD_UUID_LEN = 16;

    // Tag record field offsets.
    public static final int TAG_OFF_INDEX = 0;
    public static final int TAG_OFF_TYPE = 2;
    public static final int TAG_OFF_CLASS = 3;
    public static final int TAG_OFF_BINDING = 4;
    public static final int TAG_OFF_ADDRESS = 6;
    public static final int TAG_ADDRESS_LEN = 6;
    public static final int TAG_OFF_INIT = 12;
    public static final int TAG_OFF_NAME = 16;

    // Data types.
    public static final int T_BOOL = 0;
    public static final int T_INT = 1;
    public static final int T_REAL = 2;
    public static final int T_TIME = 3;

    // Classes.
    public static final int C_INPUT = 0;
    public static final int C_OUTPUT = 1;
    public static final int C_MEMORY = 2;
    public static final int C_SYSTEM = 3;

    // Bindings. 0x0020 and 0x0030 are reserved for a later version and are rejected by v1.
    public static final int B_NONE = 0x0000;
    public static final int B_LOCAL_DI = 0x0001;
    public static final int B_LOCAL_DO = 0x0002;
    public static final int B_LOCAL_AI = 0x0003;
    public static final int B_LOCAL_AO = 0x0004;
    public static final int B_ICC_POINT = 0x0010;
    public static final int B_SYSTEM = 0x00F0;

    // Opcodes.
    public static final int OP_LD = 0x01;
    public static final int OP_ST = 0x02;
    public static final int OP_LDC = 0x03;
    public static final int OP_LDI = 0x04;
    public static final int OP_DUP = 0x05;
    public static final int OP_DROP = 0x06;
    public static final int OP_SWAP = 0x07;
    public static final int OP_AND = 0x10;
    public static final int OP_OR = 0x11;
    public static final int OP_XOR = 0x12;
    public static final int OP_NOT = 0x13;
    public static final int OP_RTRIG = 0x14;
    public static final int OP_FTRIG = 0x15;
    public static final int OP_SET = 0x16;
    public static final int OP_RST = 0x17;
    public static final int OP_EQ = 0x20;
    public static final int OP_NE = 0x21;
    public static final int OP_GT = 0x22;
    public static final int OP_GE = 0x23;
    public static final int OP_LT = 0x24;
    public static final int OP_LE = 0x25;
    public static final int OP_ADD = 0x30;
    public static final int OP_SUB = 0x31;
    public static final int OP_MUL = 0x32;
    public static final int OP_DIV = 0x33;
    public static final int OP_MOD = 0x34;
    public static final int OP_NEG = 0x35;
    public static final int OP_ABS = 0x36;
    public static final int OP_MIN = 0x37;
    public static final int OP_MAX = 0x38;
    public static final int OP_LIMIT = 0x39;
    public static final int OP_I2R = 0x3A;
    public static final int OP_R2I = 0x3B;
    public static final int OP_SCALE = 0x3C;
    public static final int OP_TON = 0x40;
    public static final int OP_TOF = 0x41;
    public static final int OP_TP = 0x42;
    public static final int OP_CTU = 0x48;
    public static final int OP_CTD = 0x49;
    public static final int OP_JMP = 0x50;
    public static final int OP_JMPZ = 0x51;
    public static final int OP_CALL = 0x52;
    public static final int OP_RET = 0x53;
    public static final int OP_END = 0x5F;
    public static final int OP_PID = 0x60;

    /**
     * Operand width in bytes, or -1 for an opcode this format version does not define.
     *
     * <p>Mirrors {@code ilb_op_operand_size}. The -1 is load-bearing: it is what turns an unknown
     * byte into a rejection instead of a misparse that shifts every following instruction.
     */
    public static int operandSize(int opcode) {
        return switch (opcode) {
            case OP_LD, OP_ST, OP_LDC, OP_RTRIG, OP_FTRIG, OP_SET, OP_RST, OP_SCALE,
                 OP_TON, OP_TOF, OP_TP, OP_CTU, OP_CTD, OP_PID,
                 OP_JMP, OP_JMPZ, OP_CALL -> 2;
            case OP_LDI -> 4;
            case OP_DUP, OP_DROP, OP_SWAP, OP_AND, OP_OR, OP_XOR, OP_NOT,
                 OP_EQ, OP_NE, OP_GT, OP_GE, OP_LT, OP_LE,
                 OP_ADD, OP_SUB, OP_MUL, OP_DIV, OP_MOD, OP_NEG, OP_ABS,
                 OP_MIN, OP_MAX, OP_LIMIT, OP_I2R, OP_R2I, OP_RET, OP_END -> 0;
            default -> -1;
        };
    }

    /** How many operands an opcode pops, or -1 if it has no static stack effect. */
    public static int pops(int opcode) {
        return switch (opcode) {
            case OP_LD, OP_LDC, OP_LDI, OP_JMP, OP_CALL, OP_RET, OP_END -> 0;
            case OP_ST, OP_DROP, OP_SET, OP_RST, OP_JMPZ, OP_DUP,
                 OP_NOT, OP_RTRIG, OP_FTRIG, OP_NEG, OP_ABS, OP_I2R, OP_R2I, OP_SCALE -> 1;
            case OP_SWAP, OP_AND, OP_OR, OP_XOR, OP_EQ, OP_NE, OP_GT, OP_GE, OP_LT, OP_LE,
                 OP_ADD, OP_SUB, OP_MUL, OP_DIV, OP_MOD, OP_MIN, OP_MAX,
                 OP_TON, OP_TOF, OP_TP -> 2;
            case OP_LIMIT, OP_CTU, OP_CTD -> 3;
            case OP_PID -> 5;
            default -> -1;
        };
    }

    /** How many values an opcode pushes; only meaningful when {@link #pops} is not -1. */
    public static int pushes(int opcode) {
        return switch (opcode) {
            case OP_ST, OP_DROP, OP_SET, OP_RST, OP_JMPZ,
                 OP_JMP, OP_CALL, OP_RET, OP_END -> 0;
            case OP_LD, OP_LDC, OP_LDI, OP_NOT, OP_RTRIG, OP_FTRIG, OP_NEG, OP_ABS,
                 OP_I2R, OP_R2I, OP_SCALE,
                 OP_AND, OP_OR, OP_XOR, OP_EQ, OP_NE, OP_GT, OP_GE, OP_LT, OP_LE,
                 OP_ADD, OP_SUB, OP_MUL, OP_DIV, OP_MOD, OP_MIN, OP_MAX,
                 OP_TON, OP_TOF, OP_TP, OP_LIMIT, OP_CTU, OP_CTD, OP_PID -> 1;
            case OP_DUP, OP_SWAP -> 2;
            default -> -1;
        };
    }
}
