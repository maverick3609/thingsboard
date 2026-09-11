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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.thingsboard.server.service.inferrix.ilb.IlbFormat.*;

/**
 * Compiles the editor's statements into the stack machine the controller runs.
 *
 * <p>The VM has an operand stack, so {@code fan := oat < 18.0} is four instructions in an order that
 * only makes sense if you are thinking about one. This turns the tree into that order, and in doing
 * so takes on the job the bytecode verifier cannot: <b>type checking</b>. {@code ilb_verify} proves
 * the stack is the right depth and nothing more, so an INT stored into a BOOL tag passes every check
 * the device makes and faults at run time. Here the types are still known, so it is a compile error
 * with the tag's name in it.
 *
 * <p>Widening is automatic and one-way: an INT where a REAL is wanted gets an {@code I2R}, because
 * writing {@code 20} for a setpoint is not a mistake anyone should have to correct. Narrowing is
 * never implicit — losing a fraction silently is how a setpoint quietly becomes a different setpoint.
 */
public final class IlbBlockCompiler {

    private IlbBlockCompiler() {}

    /** A program the editor could express but the controller could not run. */
    public static class IlbCompileException extends RuntimeException {
        public IlbCompileException(String message) {
            super(message);
        }
    }

    private static IlbCompileException fail(String message) {
        return new IlbCompileException(message);
    }

    public static IlbProgram compile(IlbBlock.Program source) {
        return new Compilation(source).run();
    }

    /** One compilation. Holds the tag table, the const pool and the label bookkeeping. */
    private static final class Compilation {

        private final IlbBlock.Program source;
        private final Map<String, Integer> tagIndex = new LinkedHashMap<>();
        private final Map<String, Integer> tagType = new HashMap<>();
        private final List<IlbProgram.Tag> tags = new ArrayList<>();
        private final List<IlbProgram.Constant> constants = new ArrayList<>();
        private final List<IlbProgram.Instruction> code = new ArrayList<>();

        /**
         * Labels waiting for an instruction to land on.
         *
         * <p>Nested ifs routinely end at the same instruction, and the container gives an
         * instruction one label, so the second and later names become aliases of the first and jumps
         * are resolved through {@link #alias} at the end. The alternative — emitting a no-op to hang
         * a label on — would put dead instructions in a program running every scan.
         */
        private final List<String> pending = new ArrayList<>();
        private final Map<String, String> alias = new HashMap<>();
        private int labelCounter;

        Compilation(IlbBlock.Program source) {
            this.source = source;
        }

        IlbProgram run() {
            if (source == null) {
                throw fail("There is no program to compile.");
            }
            buildTags();
            List<IlbBlock.Statement> statements =
                    source.statements() == null ? List.of() : source.statements();
            for (IlbBlock.Statement statement : statements) {
                statement(statement);
            }
            emit(IlbProgram.Instruction.of(OP_END));

            List<IlbProgram.Instruction> resolved = new ArrayList<>(code.size());
            for (IlbProgram.Instruction instruction : code) {
                String target = instruction.targetLabel();
                resolved.add(target == null ? instruction
                        : IlbProgram.Instruction.jump(instruction.opcode(), canonical(target))
                                .withLabel(instruction.label()));
            }
            return new IlbProgram(source.programId(),
                    source.programVersion() == 0 ? 1 : source.programVersion(),
                    source.profile() == 0 ? 1 : source.profile(),
                    source.scanPeriodMs() == 0 ? 1000 : source.scanPeriodMs(),
                    null, tags, constants, resolved);
        }

        // --- tags ---

        private void buildTags() {
            List<IlbBlock.Tag> declared = source.tags() == null ? List.of() : source.tags();
            for (IlbBlock.Tag tag : declared) {
                if (tag.name() == null || tag.name().isBlank()) {
                    throw fail("Every tag needs a name.");
                }
                int type = dataType(tag.dataType(), "tag '" + tag.name() + "'");
                int cls = tagClass(tag.cls(), tag.name());
                int binding = binding(tag.binding(), tag.name());
                if (binding != B_NONE && tag.address() == null) {
                    throw fail("Tag '" + tag.name() + "' is bound to " + tag.binding()
                            + " but has no address.");
                }
                if (tagIndex.put(tag.name(), tags.size()) != null) {
                    throw fail("There is more than one tag called '" + tag.name() + "'.");
                }
                tagType.put(tag.name(), type);
                tags.add(new IlbProgram.Tag(type, cls, binding,
                        tag.address() == null ? 0 : tag.address(),
                        initialValue(type, tag.initial()), tag.name()));
            }
        }

        private static int initialValue(int type, Double initial) {
            if (initial == null) {
                return 0;
            }
            return type == T_REAL ? Float.floatToIntBits(initial.floatValue())
                    : (int) Math.round(initial);
        }

        private int tagOf(String name) {
            Integer index = tagIndex.get(name);
            if (index == null) {
                throw fail("There is no tag called '" + name + "'.");
            }
            return index;
        }

        private int typeOf(String name) {
            tagOf(name);
            return tagType.get(name);
        }

        // --- statements ---

        private void statement(IlbBlock.Statement statement) {
            if (statement == null || statement.kind() == null) {
                throw fail("A statement is missing its kind.");
            }
            switch (statement.kind().toUpperCase(Locale.ROOT)) {
                case "ASSIGN" -> assign(statement);
                case "IF" -> conditional(statement);
                case "SET" -> latch(statement, OP_SET);
                case "RESET" -> latch(statement, OP_RST);
                case "TIMER" -> timer(statement);
                case "COUNTER" -> counter(statement);
                case "PID" -> pid(statement);
                default -> throw fail("'" + statement.kind() + "' is not a kind of statement.");
            }
        }

        private void assign(IlbBlock.Statement statement) {
            String target = requireTarget(statement, "An assignment");
            int wanted = typeOf(target);
            int got = expression(statement.value(), "the value assigned to '" + target + "'");
            coerce(got, wanted, "the value assigned to '" + target + "'");
            emit(IlbProgram.Instruction.of(OP_ST, tagOf(target)));
        }

        private void latch(IlbBlock.Statement statement, int opcode) {
            String target = requireTarget(statement, "A latch");
            if (typeOf(target) != T_BOOL) {
                throw fail("'" + target + "' is not a switch, so it cannot be latched on or off.");
            }
            int got = expression(statement.condition(), "the condition of a latch");
            if (got != T_BOOL) {
                throw fail("A latch needs a true/false condition, and this one is "
                        + typeName(got) + ".");
            }
            emit(IlbProgram.Instruction.of(opcode, tagOf(target)));
        }

        /**
         * {@code condition} then one branch or the other, joining at a common label.
         *
         * <p>Both branches have to leave the stack as they found it or the device's own verifier
         * rejects the program where two paths meet — every statement here is depth-neutral, which is
         * what makes that hold by construction rather than by luck.
         */
        private void conditional(IlbBlock.Statement statement) {
            int got = expression(statement.condition(), "an IF condition");
            if (got != T_BOOL) {
                throw fail("An IF needs a true/false condition, and this one is "
                        + typeName(got) + ".");
            }
            List<IlbBlock.Statement> otherwise =
                    statement.otherwise() == null ? List.of() : statement.otherwise();
            String elseLabel = newLabel("else");
            String endLabel = otherwise.isEmpty() ? elseLabel : newLabel("endif");

            emit(IlbProgram.Instruction.jump(OP_JMPZ, elseLabel));
            for (IlbBlock.Statement inner : nullSafe(statement.then())) {
                statement(inner);
            }
            if (!otherwise.isEmpty()) {
                emit(IlbProgram.Instruction.jump(OP_JMP, endLabel));
                mark(elseLabel);
                for (IlbBlock.Statement inner : otherwise) {
                    statement(inner);
                }
            }
            mark(endLabel);
        }

        private void timer(IlbBlock.Statement statement) {
            String target = requireTarget(statement, "A timer");
            if (typeOf(target) != T_BOOL) {
                throw fail("A timer reports on or off, so '" + target + "' has to be a switch.");
            }
            int opcode = switch (upper(statement.timerKind(), "TON")) {
                case "TON" -> OP_TON;
                case "TOF" -> OP_TOF;
                case "TP" -> OP_TP;
                default -> throw fail("'" + statement.timerKind()
                        + "' is not a timer; use TON, TOF or TP.");
            };
            int slot = slot(statement.slot(), MAX_TIMERS, "timer");
            require(expression(statement.input(), "a timer input"), T_BOOL, "A timer input");
            int preset = expression(statement.preset(), "a timer preset");
            if (preset != T_TIME && preset != T_INT) {
                throw fail("A timer preset is a length of time in milliseconds, and this one is "
                        + typeName(preset) + ".");
            }
            emit(IlbProgram.Instruction.of(opcode, slot));
            emit(IlbProgram.Instruction.of(OP_ST, tagOf(target)));
        }

        private void counter(IlbBlock.Statement statement) {
            String target = requireTarget(statement, "A counter");
            if (typeOf(target) != T_BOOL) {
                throw fail("A counter reports whether it has reached its preset, so '" + target
                        + "' has to be a switch.");
            }
            int opcode = switch (upper(statement.counterKind(), "CTU")) {
                case "CTU" -> OP_CTU;
                case "CTD" -> OP_CTD;
                default -> throw fail("'" + statement.counterKind()
                        + "' is not a counter; use CTU or CTD.");
            };
            int slot = slot(statement.slot(), MAX_COUNTERS, "counter");
            require(expression(statement.input(), "a counter clock"), T_BOOL, "A counter clock");
            require(expression(statement.reset(), "a counter reset"), T_BOOL, "A counter reset");
            require(expression(statement.preset(), "a counter preset"), T_INT, "A counter preset");
            emit(IlbProgram.Instruction.of(opcode, slot));
            emit(IlbProgram.Instruction.of(OP_ST, tagOf(target)));
        }

        private void pid(IlbBlock.Statement statement) {
            String target = requireTarget(statement, "A PID loop");
            if (typeOf(target) != T_REAL) {
                throw fail("A PID loop outputs a percentage, so '" + target
                        + "' has to be a decimal.");
            }
            int slot = slot(statement.slot(), MAX_PIDS, "PID");
            real(statement.kp(), "the proportional gain");
            real(statement.ki(), "the integral gain");
            real(statement.kd(), "the derivative gain");
            real(statement.setpoint(), "the setpoint");
            real(statement.processValue(), "the measurement");
            emit(IlbProgram.Instruction.of(OP_PID, slot));
            emit(IlbProgram.Instruction.of(OP_ST, tagOf(target)));
        }

        private void real(IlbBlock.Expression expression, String what) {
            coerce(expression(expression, what), T_REAL, what);
        }

        // --- expressions ---

        /** Emits the code for one expression and returns the type it leaves on the stack. */
        private int expression(IlbBlock.Expression expression, String what) {
            if (expression == null || expression.kind() == null) {
                throw fail("There is nothing filled in for " + what + ".");
            }
            return switch (expression.kind().toUpperCase(Locale.ROOT)) {
                case "LITERAL" -> literal(expression, what);
                case "TAG" -> {
                    int index = tagOf(expression.tag());
                    emit(IlbProgram.Instruction.of(OP_LD, index));
                    yield tagType.get(expression.tag());
                }
                case "BINARY" -> binary(expression);
                case "UNARY" -> unary(expression);
                case "CALL" -> call(expression);
                default -> throw fail("'" + expression.kind() + "' is not a kind of value.");
            };
        }

        /**
         * An INT literal is inlined; everything else goes to the const pool.
         *
         * <p>{@code LDI} only ever pushes an INT, so a decimal, a duration or a true/false has to be
         * a pool entry carrying its own type — which is also how a BOOL literal exists at all, since
         * there is no instruction that pushes one.
         */
        private int literal(IlbBlock.Expression expression, String what) {
            int type = dataType(expression.dataType(), what);
            if (type == T_INT) {
                emit(IlbProgram.Instruction.of(OP_LDI, (int) Math.round(number(expression, what))));
                return T_INT;
            }
            int raw = switch (type) {
                case T_REAL -> Float.floatToIntBits((float) number(expression, what));
                case T_BOOL -> Boolean.TRUE.equals(expression.flag()) ? 1 : 0;
                default -> (int) Math.round(number(expression, what));
            };
            emit(IlbProgram.Instruction.of(OP_LDC, constant(type, raw)));
            return type;
        }

        private static double number(IlbBlock.Expression expression, String what) {
            if (expression.number() == null) {
                throw fail("There is no number filled in for " + what + ".");
            }
            return expression.number();
        }

        /** Interns a const-pool entry, so the same literal used twice costs eight bytes once. */
        private int constant(int type, int raw) {
            for (int i = 0; i < constants.size(); i++) {
                if (constants.get(i).type() == type && constants.get(i).rawValue() == raw) {
                    return i;
                }
            }
            constants.add(new IlbProgram.Constant(type, raw));
            return constants.size() - 1;
        }

        private int binary(IlbBlock.Expression expression) {
            String op = upper(expression.op(), "");
            List<IlbBlock.Expression> args = nullSafe(expression.args());
            if (args.size() != 2) {
                throw fail("'" + op + "' works on two values and was given " + args.size() + ".");
            }
            boolean comparison = List.of("EQ", "NE", "GT", "GE", "LT", "LE").contains(op);
            boolean logical = List.of("AND", "OR", "XOR").contains(op);

            // Both sides are compiled before the type is known, so widening cannot be inserted
            // between them — an INT under a REAL would need an I2R that is now buried on the stack.
            // The types are therefore settled first, on a dry run, and the wider one is asked for.
            int wanted = logical ? T_BOOL : agreedType(args.get(0), args.get(1), op);
            int left = expression(args.get(0), "the left side of " + op);
            coerce(left, wanted, "the left side of " + op);
            int right = expression(args.get(1), "the right side of " + op);
            coerce(right, wanted, "the right side of " + op);

            if (comparison && wanted == T_BOOL && !op.equals("EQ") && !op.equals("NE")) {
                throw fail("Two switches can only be compared for equality, not with " + op + ".");
            }
            if (op.equals("MOD") && wanted != T_INT) {
                throw fail("A remainder only makes sense on whole numbers.");
            }
            emit(IlbProgram.Instruction.of(binaryOpcode(op)));
            return comparison ? T_BOOL : wanted;
        }

        /**
         * The type two operands will meet at, without emitting anything.
         *
         * <p>Compiled code cannot be rewound, so the widening decision has to be made before either
         * side is emitted.
         */
        private int agreedType(IlbBlock.Expression left, IlbBlock.Expression right, String op) {
            int a = peekType(left);
            int b = peekType(right);
            if (a == b) {
                return a;
            }
            if ((a == T_INT && b == T_REAL) || (a == T_REAL && b == T_INT)) {
                return T_REAL;
            }
            throw fail("'" + op + "' cannot compare " + typeName(a) + " with " + typeName(b) + ".");
        }

        /** The type an expression will produce, worked out without emitting code. */
        private int peekType(IlbBlock.Expression expression) {
            if (expression == null || expression.kind() == null) {
                throw fail("A value is missing.");
            }
            return switch (expression.kind().toUpperCase(Locale.ROOT)) {
                case "LITERAL" -> dataType(expression.dataType(), "a value");
                case "TAG" -> typeOf(expression.tag());
                case "BINARY" -> {
                    String op = upper(expression.op(), "");
                    if (List.of("EQ", "NE", "GT", "GE", "LT", "LE", "AND", "OR", "XOR").contains(op)) {
                        yield T_BOOL;
                    }
                    List<IlbBlock.Expression> args = nullSafe(expression.args());
                    yield args.size() == 2 ? agreedType(args.get(0), args.get(1), op) : T_INT;
                }
                case "UNARY" -> switch (upper(expression.op(), "")) {
                    case "NOT" -> T_BOOL;
                    case "INT_TO_REAL" -> T_REAL;
                    case "REAL_TO_INT" -> T_INT;
                    default -> peekType(nullSafe(expression.args()).get(0));
                };
                case "CALL" -> switch (upper(expression.fn(), "")) {
                    case "RISING", "FALLING" -> T_BOOL;
                    case "SCALE" -> T_REAL;
                    default -> peekType(nullSafe(expression.args()).get(0));
                };
                default -> throw fail("'" + expression.kind() + "' is not a kind of value.");
            };
        }

        private int unary(IlbBlock.Expression expression) {
            String op = upper(expression.op(), "");
            List<IlbBlock.Expression> args = nullSafe(expression.args());
            if (args.size() != 1) {
                throw fail("'" + op + "' works on one value and was given " + args.size() + ".");
            }
            int type = expression(args.get(0), "the value given to " + op);
            return switch (op) {
                case "NOT" -> {
                    require(type, T_BOOL, "NOT");
                    emit(IlbProgram.Instruction.of(OP_NOT));
                    yield T_BOOL;
                }
                case "NEG", "ABS" -> {
                    if (type != T_INT && type != T_REAL) {
                        throw fail(op + " works on numbers, not " + typeName(type) + ".");
                    }
                    emit(IlbProgram.Instruction.of(op.equals("NEG") ? OP_NEG : OP_ABS));
                    yield type;
                }
                case "INT_TO_REAL" -> {
                    require(type, T_INT, "Converting to a decimal");
                    emit(IlbProgram.Instruction.of(OP_I2R));
                    yield T_REAL;
                }
                case "REAL_TO_INT" -> {
                    require(type, T_REAL, "Rounding to a whole number");
                    emit(IlbProgram.Instruction.of(OP_R2I));
                    yield T_INT;
                }
                default -> throw fail("'" + op + "' is not an operation on a single value.");
            };
        }

        private int call(IlbBlock.Expression expression) {
            String fn = upper(expression.fn(), "");
            List<IlbBlock.Expression> args = nullSafe(expression.args());
            return switch (fn) {
                case "MIN", "MAX" -> {
                    int wanted = requireArgs(args, 2, fn) == 0 ? T_INT
                            : agreedType(args.get(0), args.get(1), fn);
                    coerce(expression(args.get(0), "the first value given to " + fn), wanted, fn);
                    coerce(expression(args.get(1), "the second value given to " + fn), wanted, fn);
                    emit(IlbProgram.Instruction.of(fn.equals("MIN") ? OP_MIN : OP_MAX));
                    yield wanted;
                }
                case "LIMIT" -> {
                    requireArgs(args, 3, fn);
                    // The device pops min, value, max in that order.
                    int wanted = peekType(args.get(1));
                    coerce(expression(args.get(0), "the lowest value allowed"), wanted, fn);
                    coerce(expression(args.get(1), "the value being limited"), wanted, fn);
                    coerce(expression(args.get(2), "the highest value allowed"), wanted, fn);
                    emit(IlbProgram.Instruction.of(OP_LIMIT));
                    yield wanted;
                }
                case "SCALE" -> {
                    requireArgs(args, 4, fn);
                    coerce(expression(args.get(0), "the value being scaled"), T_REAL, fn);
                    // SCALE reads three consecutive INT entries, so they are allocated together
                    // rather than interned; interning could place them apart.
                    int base = constants.size();
                    for (int i = 1; i <= 3; i++) {
                        constants.add(new IlbProgram.Constant(T_INT, constantInt(args.get(i), fn)));
                    }
                    emit(IlbProgram.Instruction.of(OP_SCALE, base));
                    yield T_REAL;
                }
                case "RISING", "FALLING" -> {
                    requireArgs(args, 1, fn);
                    require(expression(args.get(0), "the signal watched by " + fn), T_BOOL, fn);
                    emit(IlbProgram.Instruction.of(fn.equals("RISING") ? OP_RTRIG : OP_FTRIG,
                            slot(expression.slot(), MAX_EDGES, "edge")));
                    yield T_BOOL;
                }
                default -> throw fail("'" + fn + "' is not a function.");
            };
        }

        /** SCALE's multiplier, divisor and offset are fixed numbers, not expressions. */
        private static int constantInt(IlbBlock.Expression expression, String fn) {
            if (expression == null || !"LITERAL".equalsIgnoreCase(expression.kind())
                    || expression.number() == null) {
                throw fail(fn + "'s multiplier, divisor and offset each have to be a fixed number.");
            }
            return (int) Math.round(expression.number());
        }

        private static int requireArgs(List<IlbBlock.Expression> args, int wanted, String fn) {
            if (args.size() != wanted) {
                throw fail("'" + fn + "' takes " + wanted + " values and was given "
                        + args.size() + ".");
            }
            return args.size();
        }

        // --- types ---

        /** Widens INT to REAL where a REAL is wanted; anything else has to already match. */
        private void coerce(int got, int wanted, String what) {
            if (got == wanted) {
                return;
            }
            if (got == T_INT && wanted == T_REAL) {
                emit(IlbProgram.Instruction.of(OP_I2R));
                return;
            }
            throw fail(capitalise(what) + " is " + typeName(got) + ", and " + typeName(wanted)
                    + " is needed here.");
        }

        private static void require(int got, int wanted, String what) {
            if (got != wanted) {
                throw fail(capitalise(what) + " has to be " + typeName(wanted) + ", not "
                        + typeName(got) + ".");
            }
        }

        // --- emission ---

        private void emit(IlbProgram.Instruction instruction) {
            IlbProgram.Instruction out = instruction;
            if (!pending.isEmpty()) {
                out = instruction.withLabel(pending.get(0));
                for (int i = 1; i < pending.size(); i++) {
                    alias.put(pending.get(i), pending.get(0));
                }
                pending.clear();
            }
            code.add(out);
        }

        private void mark(String label) {
            pending.add(label);
        }

        private String newLabel(String prefix) {
            return prefix + "_" + (labelCounter++);
        }

        private String canonical(String label) {
            String out = label;
            for (int guard = 0; guard < 64 && alias.containsKey(out); guard++) {
                out = alias.get(out);
            }
            return out;
        }
    }

    // --- shared helpers ---

    private static <T> List<T> nullSafe(List<T> list) {
        return list == null ? List.of() : list;
    }

    private static String upper(String value, String fallback) {
        return value == null ? fallback : value.trim().toUpperCase(Locale.ROOT);
    }

    private static int slot(Integer slot, int limit, String what) {
        int value = slot == null ? 0 : slot;
        if (value < 0 || value >= limit) {
            throw fail("This controller has " + limit + " " + what + " slots, numbered 0 to "
                    + (limit - 1) + "; this one asks for " + value + ".");
        }
        return value;
    }

    private static int binaryOpcode(String op) {
        return switch (op) {
            case "ADD" -> OP_ADD;
            case "SUB" -> OP_SUB;
            case "MUL" -> OP_MUL;
            case "DIV" -> OP_DIV;
            case "MOD" -> OP_MOD;
            case "EQ" -> OP_EQ;
            case "NE" -> OP_NE;
            case "GT" -> OP_GT;
            case "GE" -> OP_GE;
            case "LT" -> OP_LT;
            case "LE" -> OP_LE;
            case "AND" -> OP_AND;
            case "OR" -> OP_OR;
            case "XOR" -> OP_XOR;
            default -> throw fail("'" + op + "' is not an operation on two values.");
        };
    }

    private static int dataType(String name, String what) {
        return switch (upper(name, "")) {
            case "BOOL" -> T_BOOL;
            case "INT" -> T_INT;
            case "REAL" -> T_REAL;
            case "TIME" -> T_TIME;
            default -> throw fail("'" + name + "' is not a data type for " + what
                    + "; use BOOL, INT, REAL or TIME.");
        };
    }

    private static int tagClass(String name, String tag) {
        return switch (upper(name, "MEMORY")) {
            case "INPUT" -> C_INPUT;
            case "OUTPUT" -> C_OUTPUT;
            case "MEMORY" -> C_MEMORY;
            case "SYSTEM" -> C_SYSTEM;
            default -> throw fail("'" + name + "' is not a role for tag '" + tag
                    + "'; use INPUT, OUTPUT, MEMORY or SYSTEM.");
        };
    }

    private static int binding(String name, String tag) {
        return switch (upper(name, "NONE")) {
            case "NONE" -> B_NONE;
            case "LOCAL_DI" -> B_LOCAL_DI;
            case "LOCAL_DO" -> B_LOCAL_DO;
            case "LOCAL_AI" -> B_LOCAL_AI;
            case "LOCAL_AO" -> B_LOCAL_AO;
            case "ICC_POINT" -> B_ICC_POINT;
            case "SYSTEM_REGISTER" -> B_SYSTEM;
            default -> throw fail("'" + name + "' is not something tag '" + tag
                    + "' can be connected to.");
        };
    }

    private static String requireTarget(IlbBlock.Statement statement, String what) {
        if (statement.target() == null || statement.target().isBlank()) {
            throw fail(what + " has no tag to write to.");
        }
        return statement.target();
    }

    static String typeName(int type) {
        return switch (type) {
            case T_BOOL -> "a switch (on or off)";
            case T_INT -> "a whole number";
            case T_REAL -> "a decimal";
            case T_TIME -> "a length of time";
            default -> "an unknown kind of value";
        };
    }

    private static String capitalise(String text) {
        return text.isEmpty() ? text : Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }
}
