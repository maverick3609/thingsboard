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

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The editor's statements, compiled to the stack machine the controller runs.
 *
 * <p>Two things are being checked. That the emitted code is accepted by the same verifier the device
 * applies at boot — {@link IlbCompilerTest} establishes that verifier agrees with the firmware's own.
 * And that the compiler refuses programs the device would accept and then fault on, which is the
 * whole reason the editor works in statements: `ilb_verify` proves the stack is the right depth, not
 * that it holds the right kinds of value, so an INT stored into a BOOL tag is caught here or nowhere.
 */
class IlbBlockCompilerTest {

    private static final List<IlbBlock.Tag> TAGS = List.of(
            new IlbBlock.Tag("oat", "REAL", "INPUT", "ICC_POINT", 101, null),
            new IlbBlock.Tag("fan", "BOOL", "OUTPUT", "LOCAL_DO", 0, null),
            new IlbBlock.Tag("started", "BOOL", "MEMORY", "NONE", null, null),
            new IlbBlock.Tag("count", "INT", "MEMORY", "NONE", null, null),
            new IlbBlock.Tag("command", "REAL", "MEMORY", "NONE", null, null));

    private static IlbBlock.Expression tag(String name) {
        return new IlbBlock.Expression("TAG", null, null, null, name, null, null, null, null);
    }

    private static IlbBlock.Expression literal(String type, Double number, Boolean flag) {
        return new IlbBlock.Expression("LITERAL", type, number, flag, null, null, null, null, null);
    }

    private static IlbBlock.Expression binary(String op, IlbBlock.Expression a, IlbBlock.Expression b) {
        return new IlbBlock.Expression("BINARY", null, null, null, null, op, null, null, List.of(a, b));
    }

    private static IlbBlock.Statement assign(String target, IlbBlock.Expression value) {
        return new IlbBlock.Statement("ASSIGN", target, value, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null);
    }

    private static IlbBlock.Program program(IlbBlock.Statement... statements) {
        return new IlbBlock.Program(0xC0FFEE, 1, 1, 1000, TAGS, List.of(statements));
    }

    private static byte[] compile(IlbBlock.Program source) {
        return IlbAssembler.assemble(IlbBlockCompiler.compile(source));
    }

    private static String refuse(IlbBlock.Program source) {
        return assertThrows(IlbBlockCompiler.IlbCompileException.class,
                () -> IlbBlockCompiler.compile(source)).getMessage();
    }

    @Test
    void anIfWithBothBranchesCompilesAndVerifies() {
        IlbBlock.Statement conditional = new IlbBlock.Statement("IF", null, null,
                binary("LT", tag("oat"), literal("REAL", 18.0, null)),
                List.of(assign("fan", literal("BOOL", null, true))),
                List.of(assign("fan", literal("BOOL", null, false))),
                null, null, null, null, null, null, null, null, null, null, null);

        IlbVerifier.Result result = IlbVerifier.verify(compile(program(conditional)), 1, p -> p == 101);
        assertTrue(result.isOk(), result.message());
    }

    @Test
    void everyStatementKindCompilesTogether() {
        // One program using the if, the timer, the counter, the PID and a latch, because they share
        // the operand stack and a mistake in one only shows up next to the others.
        IlbBlock.Statement timer = new IlbBlock.Statement("TIMER", "started", null, null, null, null,
                "TON", null, 0, tag("fan"), literal("TIME", 30000.0, null),
                null, null, null, null, null, null);
        IlbBlock.Statement counter = new IlbBlock.Statement("COUNTER", "started", null, null, null, null,
                // The record declares input, preset, reset in that order; the device pops
                // clock, reset, then the preset count.
                null, "CTU", 0, tag("fan"), literal("INT", 10.0, null), literal("BOOL", null, false),
                null, null, null, null, null);
        IlbBlock.Statement pid = new IlbBlock.Statement("PID", "command", null, null, null, null,
                null, null, 0, null, null, null,
                literal("REAL", 2.0, null), literal("REAL", 0.1, null), literal("REAL", 0.0, null),
                literal("REAL", 21.0, null), tag("oat"));
        IlbBlock.Statement latch = new IlbBlock.Statement("SET", "fan", null,
                binary("GT", tag("oat"), literal("REAL", 25.0, null)), null, null,
                null, null, null, null, null, null, null, null, null, null, null);

        IlbVerifier.Result result =
                IlbVerifier.verify(compile(program(timer, counter, pid, latch)), 1, p -> p == 101);
        assertTrue(result.isOk(), result.message());
    }

    @Test
    void aWholeNumberWidensWhereADecimalIsWanted() {
        // Writing 20 for a setpoint is not a mistake, so it gets an I2R rather than a complaint.
        IlbProgram compiled = IlbBlockCompiler.compile(
                program(assign("command", literal("INT", 20.0, null))));

        assertEquals(List.of(IlbFormat.OP_LDI, IlbFormat.OP_I2R, IlbFormat.OP_ST, IlbFormat.OP_END),
                compiled.code().stream().map(IlbProgram.Instruction::opcode).toList());
    }

    @Test
    void aDecimalIsNeverQuietlyTruncated() {
        // The reverse is not safe: losing the fraction is how a setpoint becomes a different one.
        assertTrue(refuse(program(assign("count", literal("REAL", 20.5, null))))
                .contains("whole number"));
    }

    @Test
    void theTypeErrorsTheDeviceWouldNotCatch() {
        // ilb_verify proves stack depth only — every one of these passes it and faults at run time.
        assertTrue(refuse(program(assign("fan", literal("INT", 5.0, null)))).contains("switch"));
        assertTrue(refuse(program(assign("fan", binary("LT", tag("oat"), literal("BOOL", null, true)))))
                .contains("cannot compare"));
        assertTrue(refuse(program(assign("count", binary("MOD", tag("oat"), tag("oat")))))
                .contains("whole numbers"));
    }

    @Test
    void anIfNeedsAConditionRatherThanANumber() {
        IlbBlock.Statement conditional = new IlbBlock.Statement("IF", null, null, tag("oat"),
                List.of(), null, null, null, null, null, null, null, null, null, null, null, null);
        assertTrue(refuse(program(conditional)).contains("true/false"));
    }

    @Test
    void resourceSlotsAreCheckedAgainstWhatTheControllerHas() {
        IlbBlock.Statement timer = new IlbBlock.Statement("TIMER", "started", null, null, null, null,
                "TON", null, 64, tag("fan"), literal("TIME", 1000.0, null),
                null, null, null, null, null, null);
        assertTrue(refuse(program(timer)).contains("64 timer slots"));
    }

    @Test
    void aMisspeltTagIsNamedRatherThanNumbered() {
        assertTrue(refuse(program(assign("nosuch", literal("BOOL", null, true))))
                .contains("'nosuch'"));
    }

    @Test
    void theSameLiteralUsedTwiceCostsThePoolOnce() {
        IlbProgram compiled = IlbBlockCompiler.compile(program(
                assign("fan", binary("LT", tag("oat"), literal("REAL", 18.0, null))),
                assign("started", binary("GT", tag("oat"), literal("REAL", 18.0, null)))));

        assertEquals(1, compiled.constants().size(), "18.0 should be interned once");
    }

    @Test
    void nestedIfsEndingTogetherStillVerify() {
        // Two ifs closing on the same instruction is the case that needs label aliasing: the
        // container gives an instruction one label, and emitting a no-op to hang the second on
        // would put a dead instruction in a program that runs every scan.
        IlbBlock.Statement inner = new IlbBlock.Statement("IF", null, null,
                binary("GT", tag("oat"), literal("REAL", 30.0, null)),
                List.of(assign("fan", literal("BOOL", null, true))), null,
                null, null, null, null, null, null, null, null, null, null, null);
        IlbBlock.Statement outer = new IlbBlock.Statement("IF", null, null,
                binary("LT", tag("oat"), literal("REAL", 18.0, null)),
                List.of(inner), null,
                null, null, null, null, null, null, null, null, null, null, null);

        IlbVerifier.Result result = IlbVerifier.verify(compile(program(outer)), 1, p -> p == 101);
        assertTrue(result.isOk(), result.message());
    }

    @Test
    void aProgramIdAboveTwoBillionSurvives() {
        // The id is a u32 on the wire. Holding it as an int in the JSON model meant any id past
        // 2^31 was refused by the parser before the compiler ever saw it, with a 500 rather than
        // anything an operator could act on.
        long big = 0xDEADBEEFL;
        IlbBlock.Program source = new IlbBlock.Program(big, 1, 1, 1000, TAGS, List.of());
        IlbVerifier.Result result = IlbVerifier.verify(compile(source), 1, p -> p == 101);

        assertTrue(result.isOk(), result.message());
        assertEquals(big, result.programId());
    }

    @Test
    void aWholeNumberTooWideToBeOneIsRefused() {
        // Rounding a double straight into an int does not overflow, it lands somewhere arbitrary:
        // 3000000000 arrives as -1294967296 and 1e308 as -1, so a compiled program would have run
        // for years against a number nobody typed.
        assertTrue(refuse(program(assign("count", literal("INT", 3.0e9, null))))
                .contains("outside the range"));
        assertTrue(refuse(program(assign("count", literal("INT", 1.0e308, null))))
                .contains("outside the range"));
        assertTrue(refuse(program(assign("count", literal("INT", -3.0e9, null))))
                .contains("outside the range"));

        // The edges themselves still compile.
        assertTrue(IlbVerifier.verify(compile(program(
                assign("count", literal("INT", (double) Integer.MAX_VALUE, null)))), 1, p -> true)
                .isOk());
        assertTrue(IlbVerifier.verify(compile(program(
                assign("count", literal("INT", (double) Integer.MIN_VALUE, null)))), 1, p -> true)
                .isOk());
    }

    @Test
    void aFractionWhereAWholeNumberBelongsIsRefused() {
        // Silently rounding 2.7 to 3 is the same drift as narrowing a REAL into an INT tag, which
        // is already refused - a preset or a count that is quietly not what was typed.
        assertTrue(refuse(program(assign("count", literal("INT", 2.7, null))))
                .contains("whole number is needed"));

        // A tag's starting value and SCALE's three fixed numbers narrow the same way, so they are
        // held to the same rule.
        List<IlbBlock.Tag> fractionalStart = List.of(
                new IlbBlock.Tag("count", "INT", "MEMORY", "NONE", null, 2.7));
        assertTrue(assertThrows(IlbBlockCompiler.IlbCompileException.class,
                () -> IlbBlockCompiler.compile(new IlbBlock.Program(1, 1, 1, 1000,
                        fractionalStart, List.of()))).getMessage().contains("whole number"));

        IlbBlock.Expression scale = new IlbBlock.Expression("CALL", null, null, null, null, null,
                "SCALE", null, List.of(tag("count"), literal("INT", 1.5, null),
                        literal("INT", 1.0, null), literal("INT", 0.0, null)));
        assertTrue(refuse(program(assign("command", scale))).contains("whole number"));
    }

    @Test
    void anEmptyProgramIsStillAValidOne() {
        // A controller with nothing to do runs END once a scan; refusing that would make "clear the
        // logic" impossible to express.
        IlbVerifier.Result result = IlbVerifier.verify(
                IlbAssembler.assemble(IlbBlockCompiler.compile(
                        new IlbBlock.Program(1, 1, 1, 1000, List.of(), List.of()))), 1, null);
        assertTrue(result.isOk(), result.message());
    }
}
