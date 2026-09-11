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

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.CRC32;

import static org.thingsboard.server.service.inferrix.ilb.IlbFormat.*;

/**
 * Turns a {@link IlbProgram} into the bytes the controller stages.
 *
 * <p>Layout is header, tags, code, const pool, CRC. The format permits any section order; this one
 * is chosen so the const pool — the section most likely to be empty — is last, because an empty
 * section's offset is only a placeholder and putting a real section after one would leave a gap.
 *
 * <p>The rule that catches people is the packing invariant: the highest non-empty section must end
 * exactly where the CRC begins. The device recovers a stored container's length from the header
 * alone, so a trailing gap does not waste four bytes, it makes the boot-time CRC hash the wrong
 * range and the program is silently dropped.
 */
public final class IlbAssembler {

    private IlbAssembler() {}

    /** Refuses a program that cannot be encoded at all, as opposed to one that merely fails verify. */
    public static class IlbAssemblyException extends RuntimeException {
        public IlbAssemblyException(String message) {
            super(message);
        }
    }

    public static byte[] assemble(IlbProgram program) {
        byte[] tags = encodeTags(program);
        byte[] code = encodeCode(program);
        byte[] constants = encodeConstants(program);

        int tagOffset = HEADER_SIZE;
        int codeOffset = tagOffset + tags.length;
        int constOffset = codeOffset + code.length;
        int bodyEnd = constOffset + constants.length;

        byte[] image = new byte[bodyEnd + CRC_SIZE];
        System.arraycopy(MAGIC, 0, image, 0, MAGIC.length);
        wr16(image, OFF_FORMAT_VERSION, FORMAT_VERSION);
        wr16(image, OFF_FLAGS, 0);
        wr32(image, OFF_PROGRAM_ID, program.programId());
        wr32(image, OFF_PROGRAM_VERSION, program.programVersion());
        wr16(image, OFF_MIN_RUNTIME, RUNTIME_VERSION);
        wr16(image, OFF_PROFILE, program.profile());
        wr32(image, OFF_SCAN_PERIOD_MS, program.scanPeriodMs());

        wr32(image, OFF_TAG_OFFSET, tags.length == 0 ? HEADER_SIZE : tagOffset);
        wr32(image, OFF_TAG_LENGTH, tags.length);
        wr32(image, OFF_CODE_OFFSET, codeOffset);
        wr32(image, OFF_CODE_LENGTH, code.length);
        // An empty pool still needs an offset inside the file; it is a placeholder the device
        // never reads, and it must not be the body end or it would claim the packing slot.
        wr32(image, OFF_CONST_OFFSET, constants.length == 0 ? HEADER_SIZE : constOffset);
        wr32(image, OFF_CONST_LENGTH, constants.length);

        byte[] uuid = program.buildUuid();
        if (uuid != null) {
            System.arraycopy(uuid, 0, image, OFF_BUILD_UUID, Math.min(uuid.length, BUILD_UUID_LEN));
        }

        System.arraycopy(tags, 0, image, tagOffset, tags.length);
        System.arraycopy(code, 0, image, codeOffset, code.length);
        System.arraycopy(constants, 0, image, constOffset, constants.length);

        CRC32 crc = new CRC32();
        crc.update(image, 0, bodyEnd);
        wr32(image, bodyEnd, crc.getValue());
        return image;
    }

    private static byte[] encodeTags(IlbProgram program) {
        byte[] out = new byte[program.tags().size() * TAG_RECORD_SIZE];
        for (int i = 0; i < program.tags().size(); i++) {
            IlbProgram.Tag tag = program.tags().get(i);
            int rec = i * TAG_RECORD_SIZE;
            wr16(out, rec + TAG_OFF_INDEX, i);
            out[rec + TAG_OFF_TYPE] = (byte) tag.type();
            out[rec + TAG_OFF_CLASS] = (byte) tag.cls();
            wr16(out, rec + TAG_OFF_BINDING, tag.binding());
            wr16(out, rec + TAG_OFF_ADDRESS, tag.address());
            wr32(out, rec + TAG_OFF_INIT, tag.initialValue() & 0xFFFFFFFFL);
            if (tag.name() != null) {
                byte[] name = tag.name().getBytes(StandardCharsets.UTF_8);
                // One byte short of the field: the name is NUL-padded and the device reads it as a
                // C string, so the last byte has to stay zero.
                System.arraycopy(name, 0, out, rec + TAG_OFF_NAME,
                        Math.min(name.length, TAG_NAME_LEN - 1));
            }
        }
        return out;
    }

    private static byte[] encodeConstants(IlbProgram program) {
        byte[] out = new byte[program.constants().size() * CONST_ENTRY_SIZE];
        for (int i = 0; i < program.constants().size(); i++) {
            IlbProgram.Constant constant = program.constants().get(i);
            wr32(out, i * CONST_ENTRY_SIZE, constant.type() & 0xFFFFFFFFL);
            wr32(out, i * CONST_ENTRY_SIZE + 4, constant.rawValue() & 0xFFFFFFFFL);
        }
        return out;
    }

    /**
     * Encodes the instruction stream, resolving labels.
     *
     * <p>Two passes: the first records where each instruction lands, because a jump's operand is
     * signed and relative to the instruction <i>after</i> it and so cannot be computed until every
     * size is known. Sizes are static in this format, so one layout pass is enough — no iteration
     * to a fixed point.
     */
    private static byte[] encodeCode(IlbProgram program) {
        Map<String, Integer> labels = new HashMap<>();
        int offset = 0;
        for (IlbProgram.Instruction instruction : program.code()) {
            if (instruction.label() != null
                    && labels.put(instruction.label(), offset) != null) {
                throw new IlbAssemblyException("the label '" + instruction.label()
                        + "' is defined more than once");
            }
            if (operandSize(instruction.opcode()) < 0) {
                throw new IlbAssemblyException(String.format(
                        "0x%02x is not an opcode in this format version", instruction.opcode()));
            }
            offset += instruction.size();
        }

        byte[] out = new byte[offset];
        int pc = 0;
        for (IlbProgram.Instruction instruction : program.code()) {
            int opcode = instruction.opcode();
            int operandSize = operandSize(opcode);
            out[pc] = (byte) opcode;
            int next = pc + 1 + operandSize;

            int operand = instruction.operand();
            if (instruction.targetLabel() != null) {
                Integer target = labels.get(instruction.targetLabel());
                if (target == null) {
                    throw new IlbAssemblyException("the jump at code offset " + pc
                            + " targets '" + instruction.targetLabel() + "', which is not defined");
                }
                int relative = target - next;
                if (relative < Short.MIN_VALUE || relative > Short.MAX_VALUE) {
                    throw new IlbAssemblyException("the jump at code offset " + pc + " is "
                            + relative + " bytes, beyond the signed 16-bit reach of this format");
                }
                operand = relative & 0xFFFF;
            }
            if (operandSize == 2) {
                wr16(out, pc + 1, operand & 0xFFFF);
            } else if (operandSize == 4) {
                wr32(out, pc + 1, operand & 0xFFFFFFFFL);
            }
            pc = next;
        }
        return out;
    }

    private static void wr16(byte[] b, int off, int value) {
        b[off] = (byte) value;
        b[off + 1] = (byte) (value >>> 8);
    }

    private static void wr32(byte[] b, int off, long value) {
        b[off] = (byte) value;
        b[off + 1] = (byte) (value >>> 8);
        b[off + 2] = (byte) (value >>> 16);
        b[off + 3] = (byte) (value >>> 24);
    }
}
