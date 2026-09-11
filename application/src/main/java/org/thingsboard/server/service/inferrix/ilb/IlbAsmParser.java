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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.thingsboard.server.service.inferrix.ilb.IlbFormat.*;

/**
 * Reads the tag table and opcode listing that INTEGRATION-API §8 writes programs in.
 *
 * <p>§8 presents every worked example as "a tag table + opcode listing (readable assembly)" and says
 * the platform's compiler turns that into the container. This is that compiler's front end. It
 * exists so a program can be written, reviewed and diffed as text rather than as a hex dump — the
 * only form anyone had before this.
 *
 * <pre>
 *   ; economiser enable
 *   .program id=0x0C0FFEE1 version=4 profile=1 scan=1000
 *
 *   .tag oat      real time  input  icc_point 101
 *   .tag enable   bool       output local_do  0
 *   .const real 18.0
 *
 *   .code
 *     LD oat
 *     LDC 0
 *     LT
 *     ST enable
 *     END
 * </pre>
 *
 * <p>Operands are written the way the instruction means them: a tag by name, a jump by label, a
 * timer by slot. Referring to a tag by name rather than by index is the point — the format numbers
 * tags positionally, and a hand-written index silently shifts the moment a tag is inserted above it.
 */
public final class IlbAsmParser {

    private IlbAsmParser() {}

    /** A source-level error, carrying the line so the operator can find it. */
    public static class IlbSyntaxException extends RuntimeException {
        private final int line;

        public IlbSyntaxException(int line, String message) {
            super("line " + line + ": " + message);
            this.line = line;
        }

        public int getLine() {
            return line;
        }
    }

    private static final Map<String, Integer> TYPES = Map.of(
            "bool", T_BOOL, "int", T_INT, "real", T_REAL, "time", T_TIME);

    private static final Map<String, Integer> CLASSES = Map.of(
            "input", C_INPUT, "output", C_OUTPUT, "memory", C_MEMORY, "system", C_SYSTEM);

    private static final Map<String, Integer> BINDINGS = Map.of(
            "none", B_NONE, "local_di", B_LOCAL_DI, "local_do", B_LOCAL_DO,
            "local_ai", B_LOCAL_AI, "local_ao", B_LOCAL_AO,
            "icc_point", B_ICC_POINT, "sysreg", B_SYSTEM);

    private static final Map<String, Integer> OPCODES = buildOpcodes();

    /** Opcodes whose operand names a tag. */
    private static final List<Integer> TAG_OPS = List.of(OP_LD, OP_ST, OP_SET, OP_RST);
    /** Opcodes whose operand is a jump target. */
    private static final List<Integer> JUMP_OPS = List.of(OP_JMP, OP_JMPZ, OP_CALL);

    public static IlbProgram parse(String source) {
        if (source == null || source.isBlank()) {
            throw new IlbSyntaxException(0, "the program is empty");
        }
        int programId = 0;
        long programVersion = 1;
        int profile = 1;
        long scanPeriod = 1000;

        List<IlbProgram.Tag> tags = new ArrayList<>();
        Map<String, Integer> tagIndex = new HashMap<>();
        List<IlbProgram.Constant> constants = new ArrayList<>();
        List<IlbProgram.Instruction> code = new ArrayList<>();
        boolean inCode = false;
        String pendingLabel = null;

        String[] lines = source.split("\r?\n", -1);
        for (int n = 0; n < lines.length; n++) {
            int lineNumber = n + 1;
            String line = stripComment(lines[n]).trim();
            if (line.isEmpty()) {
                continue;
            }

            if (line.startsWith(".program")) {
                Map<String, String> fields = keyValues(lineNumber, line.substring(".program".length()));
                programId = (int) number(lineNumber, fields.getOrDefault("id", "0"));
                programVersion = number(lineNumber, fields.getOrDefault("version", "1"));
                profile = (int) number(lineNumber, fields.getOrDefault("profile", "1"));
                scanPeriod = number(lineNumber, fields.getOrDefault("scan", "1000"));
                continue;
            }
            if (line.startsWith(".tag")) {
                if (inCode) {
                    throw new IlbSyntaxException(lineNumber, "tags have to be declared before .code");
                }
                IlbProgram.Tag tag = parseTag(lineNumber, line.substring(".tag".length()).trim());
                if (tagIndex.put(tag.name(), tags.size()) != null) {
                    throw new IlbSyntaxException(lineNumber,
                            "there is already a tag called '" + tag.name() + "'");
                }
                tags.add(tag);
                continue;
            }
            if (line.startsWith(".const")) {
                if (inCode) {
                    throw new IlbSyntaxException(lineNumber, "constants have to be declared before .code");
                }
                constants.add(parseConstant(lineNumber, line.substring(".const".length()).trim()));
                continue;
            }
            if (line.equals(".code")) {
                inCode = true;
                continue;
            }
            if (line.startsWith(".")) {
                throw new IlbSyntaxException(lineNumber,
                        "'" + line.split("\\s+")[0] + "' is not a directive");
            }
            if (!inCode) {
                throw new IlbSyntaxException(lineNumber,
                        "instructions have to come after a .code line");
            }

            // A label may sit alone on its line or ahead of an instruction.
            if (line.endsWith(":") && !line.contains(" ")) {
                if (pendingLabel != null) {
                    throw new IlbSyntaxException(lineNumber, "'" + pendingLabel
                            + "' has no instruction to label");
                }
                pendingLabel = line.substring(0, line.length() - 1);
                continue;
            }
            int colon = labelBoundary(line);
            if (colon > 0) {
                if (pendingLabel != null) {
                    throw new IlbSyntaxException(lineNumber, "'" + pendingLabel
                            + "' has no instruction to label");
                }
                pendingLabel = line.substring(0, colon);
                line = line.substring(colon + 1).trim();
            }

            IlbProgram.Instruction instruction = parseInstruction(lineNumber, line, tagIndex);
            code.add(pendingLabel == null ? instruction : instruction.withLabel(pendingLabel));
            pendingLabel = null;
        }

        if (pendingLabel != null) {
            throw new IlbSyntaxException(lines.length,
                    "'" + pendingLabel + "' has no instruction to label");
        }
        if (code.isEmpty()) {
            throw new IlbSyntaxException(lines.length, "the program has no instructions");
        }
        return new IlbProgram(programId, programVersion, profile, scanPeriod, null,
                tags, constants, code);
    }

    private static IlbProgram.Tag parseTag(int line, String rest) {
        // name type class [binding [address]] [init=value]
        List<String> words = new ArrayList<>();
        String init = null;
        for (String word : rest.split("\\s+")) {
            if (word.toLowerCase(Locale.ROOT).startsWith("init=")) {
                init = word.substring("init=".length());
            } else if (!word.isEmpty()) {
                words.add(word);
            }
        }
        if (words.size() < 3) {
            throw new IlbSyntaxException(line,
                    "a tag needs at least a name, a data type and a class");
        }
        String name = words.get(0);
        Integer type = TYPES.get(words.get(1).toLowerCase(Locale.ROOT));
        Integer cls = CLASSES.get(words.get(2).toLowerCase(Locale.ROOT));
        if (type == null) {
            throw new IlbSyntaxException(line, "'" + words.get(1)
                    + "' is not a data type; use bool, int, real or time");
        }
        if (cls == null) {
            throw new IlbSyntaxException(line, "'" + words.get(2)
                    + "' is not a class; use input, output, memory or system");
        }

        int binding = B_NONE;
        int address = 0;
        if (words.size() > 3) {
            Integer bound = BINDINGS.get(words.get(3).toLowerCase(Locale.ROOT));
            if (bound == null) {
                throw new IlbSyntaxException(line, "'" + words.get(3) + "' is not a binding; use "
                        + "none, local_di, local_do, local_ai, local_ao, icc_point or sysreg");
            }
            binding = bound;
            if (words.size() > 4) {
                address = (int) number(line, words.get(4));
            } else if (binding != B_NONE) {
                throw new IlbSyntaxException(line, "a " + words.get(3)
                        + " binding needs an address — a channel, point id or register id");
            }
        }
        int initial = init == null ? 0 : rawValue(line, type, init);
        return new IlbProgram.Tag(type, cls, binding, address, initial, name);
    }

    private static IlbProgram.Constant parseConstant(int line, String rest) {
        String[] words = rest.split("\\s+");
        if (words.length < 2) {
            throw new IlbSyntaxException(line, "a constant needs a data type and a value");
        }
        Integer type = TYPES.get(words[0].toLowerCase(Locale.ROOT));
        if (type == null) {
            throw new IlbSyntaxException(line, "'" + words[0]
                    + "' is not a data type; use bool, int, real or time");
        }
        return new IlbProgram.Constant(type, rawValue(line, type, words[1]));
    }

    private static IlbProgram.Instruction parseInstruction(int line, String text,
                                                           Map<String, Integer> tagIndex) {
        String[] words = text.split("\\s+", 2);
        Integer opcode = OPCODES.get(words[0].toUpperCase(Locale.ROOT));
        if (opcode == null) {
            throw new IlbSyntaxException(line, "'" + words[0] + "' is not an instruction");
        }
        int operandSize = operandSize(opcode);
        String operand = words.length > 1 ? words[1].trim() : null;

        if (operandSize == 0) {
            if (operand != null) {
                throw new IlbSyntaxException(line, words[0].toUpperCase(Locale.ROOT)
                        + " does not take an operand");
            }
            return IlbProgram.Instruction.of(opcode);
        }
        if (operand == null) {
            throw new IlbSyntaxException(line,
                    words[0].toUpperCase(Locale.ROOT) + " needs an operand");
        }
        if (JUMP_OPS.contains(opcode)) {
            return IlbProgram.Instruction.jump(opcode, operand);
        }
        if (TAG_OPS.contains(opcode)) {
            Integer index = tagIndex.get(operand);
            if (index == null) {
                // A bare number still works, for a listing transcribed from a disassembly.
                if (!operand.isEmpty() && Character.isDigit(operand.charAt(0))) {
                    return IlbProgram.Instruction.of(opcode, (int) number(line, operand));
                }
                throw new IlbSyntaxException(line, "there is no tag called '" + operand + "'");
            }
            return IlbProgram.Instruction.of(opcode, index);
        }
        return IlbProgram.Instruction.of(opcode, (int) number(line, operand));
    }

    /** The raw 32 bits a value occupies for its declared type. */
    private static int rawValue(int line, int type, String text) {
        try {
            if (type == T_REAL) {
                return Float.floatToIntBits(Float.parseFloat(text));
            }
            if (type == T_BOOL) {
                if (text.equalsIgnoreCase("true")) {
                    return 1;
                }
                if (text.equalsIgnoreCase("false")) {
                    return 0;
                }
            }
            return (int) number(line, text);
        } catch (NumberFormatException e) {
            throw new IlbSyntaxException(line, "'" + text + "' is not a value for that data type");
        }
    }

    private static long number(int line, String text) {
        try {
            String value = text.trim();
            boolean negative = value.startsWith("-");
            if (negative) {
                value = value.substring(1);
            }
            long parsed = value.toLowerCase(Locale.ROOT).startsWith("0x")
                    ? Long.parseLong(value.substring(2), 16)
                    : Long.parseLong(value);
            return negative ? -parsed : parsed;
        } catch (NumberFormatException e) {
            throw new IlbSyntaxException(line, "'" + text + "' is not a number");
        }
    }

    /** Where a leading {@code label:} ends, or -1. Ignores a colon inside a later word. */
    private static int labelBoundary(String line) {
        int colon = line.indexOf(':');
        int space = line.indexOf(' ');
        return colon > 0 && (space < 0 || colon < space) ? colon : -1;
    }

    private static String stripComment(String line) {
        int hash = line.indexOf('#');
        int semi = line.indexOf(';');
        int cut = hash < 0 ? semi : (semi < 0 ? hash : Math.min(hash, semi));
        return cut < 0 ? line : line.substring(0, cut);
    }

    private static Map<String, String> keyValues(int line, String text) {
        Map<String, String> out = new HashMap<>();
        for (String word : text.trim().split("\\s+")) {
            if (word.isEmpty()) {
                continue;
            }
            int eq = word.indexOf('=');
            if (eq <= 0) {
                throw new IlbSyntaxException(line, "'" + word + "' should be written key=value");
            }
            out.put(word.substring(0, eq).toLowerCase(Locale.ROOT), word.substring(eq + 1));
        }
        return out;
    }

    private static Map<String, Integer> buildOpcodes() {
        Map<String, Integer> map = new HashMap<>();
        map.put("LD", OP_LD); map.put("ST", OP_ST); map.put("LDC", OP_LDC); map.put("LDI", OP_LDI);
        map.put("DUP", OP_DUP); map.put("DROP", OP_DROP); map.put("SWAP", OP_SWAP);
        map.put("AND", OP_AND); map.put("OR", OP_OR); map.put("XOR", OP_XOR); map.put("NOT", OP_NOT);
        map.put("RTRIG", OP_RTRIG); map.put("FTRIG", OP_FTRIG);
        map.put("SET", OP_SET); map.put("RST", OP_RST);
        map.put("EQ", OP_EQ); map.put("NE", OP_NE); map.put("GT", OP_GT);
        map.put("GE", OP_GE); map.put("LT", OP_LT); map.put("LE", OP_LE);
        map.put("ADD", OP_ADD); map.put("SUB", OP_SUB); map.put("MUL", OP_MUL);
        map.put("DIV", OP_DIV); map.put("MOD", OP_MOD); map.put("NEG", OP_NEG); map.put("ABS", OP_ABS);
        map.put("MIN", OP_MIN); map.put("MAX", OP_MAX); map.put("LIMIT", OP_LIMIT);
        map.put("I2R", OP_I2R); map.put("R2I", OP_R2I); map.put("SCALE", OP_SCALE);
        map.put("TON", OP_TON); map.put("TOF", OP_TOF); map.put("TP", OP_TP);
        map.put("CTU", OP_CTU); map.put("CTD", OP_CTD); map.put("PID", OP_PID);
        map.put("JMP", OP_JMP); map.put("JMPZ", OP_JMPZ); map.put("CALL", OP_CALL);
        map.put("RET", OP_RET); map.put("END", OP_END);
        return Map.copyOf(map);
    }
}
