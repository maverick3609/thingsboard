// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.service.inferrix.ilb;

import java.util.List;

/**
 * A logic program before it becomes bytes.
 *
 * <p>The intermediate form everything else meets at: {@link IlbAsmParser} produces one from text,
 * {@link IlbAssembler} turns one into a container, and a future program editor in the UI would
 * build one directly rather than generating text for us to re-parse.
 *
 * <p>Tag indices are positional — a tag's index is where it sits in {@link #tags()}, because the
 * format requires them dense and ascending and there is no reason to let a caller number them
 * wrongly. Instructions reference tags by that position.
 */
public record IlbProgram(int programId, long programVersion, int profile, long scanPeriodMs,
                         byte[] buildUuid, List<Tag> tags, List<Constant> constants,
                         List<Instruction> code) {

    /**
     * One variable.
     *
     * @param address the binding address: a channel number for local I/O, a point id for an ICC
     *                point, a register id for a system register. Unused when unbound.
     * @param initialValue the raw 32-bit initial value — float bits for REAL, milliseconds for TIME
     * @param name diagnostics only; the device truncates it to 15 characters plus a terminator
     */
    public record Tag(int type, int cls, int binding, int address, int initialValue, String name) {}

    /** One const-pool entry: a type and the raw 32 bits of the value. */
    public record Constant(int type, int rawValue) {}

    /**
     * One instruction.
     *
     * <p>A jump carries {@code targetLabel} instead of an operand and is resolved at assembly, since
     * the encoded operand is relative to the <i>following</i> instruction and cannot be known until
     * every instruction's size is laid out.
     */
    public record Instruction(int opcode, int operand, String targetLabel, String label) {

        public static Instruction of(int opcode) {
            return new Instruction(opcode, 0, null, null);
        }

        public static Instruction of(int opcode, int operand) {
            return new Instruction(opcode, operand, null, null);
        }

        public static Instruction jump(int opcode, String targetLabel) {
            return new Instruction(opcode, 0, targetLabel, null);
        }

        public Instruction withLabel(String label) {
            return new Instruction(opcode, operand, targetLabel, label);
        }

        /** Encoded size: the opcode byte plus its operand. */
        public int size() {
            return 1 + Math.max(IlbFormat.operandSize(opcode), 0);
        }
    }
}
