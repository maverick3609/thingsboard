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

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * A logic program as the editor holds it: tags, and a list of statements.
 *
 * <p>This is the form a person writes. {@link IlbProgram} is the form the device runs, and
 * {@link IlbBlockCompiler} is the distance between them — the VM is a stack machine, so a statement
 * as ordinary as {@code fan := oat < 18.0} becomes four instructions whose order only makes sense if
 * you are thinking about a stack. Nobody configuring an air handler should have to.
 *
 * <p>Deliberately a tree rather than an instruction list, and that buys the one thing the bytecode
 * verifier cannot do: <b>types</b>. {@code ilb_verify} proves the stack is the right depth but not
 * that it holds the right kinds of value — a known gap in the firmware's own verifier, where storing
 * an INT into a BOOL tag passes every check and faults at run time. A tree still knows that
 * {@code oat} is REAL, so the compiler can refuse it.
 *
 * <p>Flat nodes with a {@code kind} discriminator rather than a polymorphic class hierarchy: the
 * producer is a browser building JSON, and a shape that is awkward to construct by hand is the wrong
 * shape for that.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class IlbBlock {

    private IlbBlock() {}

    /** A whole program, as posted by the editor. */
    public record Program(long programId, long programVersion, int profile, long scanPeriodMs,
                          List<Tag> tags, List<Statement> statements) {}

    /**
     * One variable.
     *
     * @param dataType one of BOOL, INT, REAL, TIME
     * @param cls      one of INPUT, OUTPUT, MEMORY, SYSTEM
     * @param binding  one of NONE, LOCAL_DI, LOCAL_DO, LOCAL_AI, LOCAL_AO, ICC_POINT, SYSTEM_REGISTER
     * @param address  channel number, ICC point id, or system register id
     * @param initial  initial value, read according to dataType
     */
    public record Tag(String name, String dataType, String cls, String binding, Integer address,
                      Double initial) {}

    /**
     * One statement.
     *
     * <p>{@code kind} selects which fields matter:
     * <ul>
     *   <li>{@code ASSIGN} — {@code target}, {@code value}</li>
     *   <li>{@code IF} — {@code condition}, {@code then}, {@code otherwise}</li>
     *   <li>{@code SET} / {@code RESET} — {@code target}, {@code condition} (latch while true)</li>
     *   <li>{@code TIMER} — {@code target}, {@code slot}, {@code input}, {@code preset}
     *       ({@code timerKind} TON, TOF or TP)</li>
     *   <li>{@code COUNTER} — {@code target}, {@code slot}, {@code input}, {@code reset},
     *       {@code preset} ({@code counterKind} CTU or CTD)</li>
     *   <li>{@code PID} — {@code target}, {@code slot}, {@code kp}, {@code ki}, {@code kd},
     *       {@code setpoint}, {@code processValue}</li>
     * </ul>
     */
    public record Statement(String kind, String target, Expression value, Expression condition,
                            List<Statement> then, List<Statement> otherwise,
                            String timerKind, String counterKind, Integer slot,
                            Expression input, Expression preset, Expression reset,
                            Expression kp, Expression ki, Expression kd,
                            Expression setpoint, Expression processValue) {}

    /**
     * One expression node.
     *
     * <p>{@code kind} is {@code LITERAL} (with {@code dataType} and {@code number}/{@code flag}),
     * {@code TAG} (with {@code tag}), {@code BINARY} or {@code UNARY} (with {@code op} and
     * {@code args}), or {@code CALL} (with {@code fn}, {@code args} and for edge functions a
     * {@code slot}).
     *
     * <p>Binary ops: ADD SUB MUL DIV MOD, EQ NE GT GE LT LE, AND OR XOR, MIN MAX.
     * Unary ops: NOT NEG ABS, INT_TO_REAL, REAL_TO_INT.
     * Calls: LIMIT(min, value, max), SCALE(value, multiplier, divisor, offset),
     * RISING(signal), FALLING(signal).
     */
    public record Expression(String kind, String dataType, Double number, Boolean flag,
                             String tag, String op, String fn, Integer slot,
                             List<Expression> args) {}
}
