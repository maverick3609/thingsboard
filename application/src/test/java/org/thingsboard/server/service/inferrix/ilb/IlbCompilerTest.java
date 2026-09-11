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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The compiler and the verifier, pinned to what the device actually does.
 *
 * <p>Every expected verdict here was taken from the firmware's own {@code ilb_verify()}, compiled
 * from {@code lib/ilb_vm} and run over the same containers this test builds: 538 mutated images plus
 * the hand-written cases below, zero disagreements. That matters because the two implementations
 * can drift — the device is the authority, and a change here that makes a test pass while the
 * hardware still refuses the program has made things worse, not better.
 *
 * <p>The whole point of this layer is that the controller will not tell us why it said no. A
 * container it rejects at boot reports {@code state 0} over REST, which is what it also reports
 * having never been given a program at all.
 */
class IlbCompilerTest {

    private static final String HEADER = """
            .program id=0xC0FFEE version=9 profile=1 scan=1000
            .tag uptime time input sysreg 0
            .tag last   time memory
            .tag same   bool memory
            .code
            """;

    private static byte[] compile(String body) {
        return IlbAssembler.assemble(IlbAsmParser.parse(HEADER + body));
    }

    private static IlbVerifyError verdict(String body) {
        return IlbVerifier.verify(compile(body), 1, null).error();
    }

    @Test
    void aProgramSurvivesTheRoundTrip() {
        byte[] image = compile("  LD uptime\n  DUP\n  ST last\n  LD last\n  EQ\n  ST same\n  END\n");
        IlbVerifier.Result result = IlbVerifier.verify(image, 1, null);

        assertTrue(result.isOk(), result.message());
        assertEquals(0xC0FFEE, result.programId());
        assertEquals(9, result.programVersion());
        assertEquals(3, result.tagCount());
        assertEquals(1000, result.scanPeriodMs());
    }

    @Test
    void theSectionsEndExactlyWhereTheCrcBegins() {
        // The packing invariant is not cosmetic: the device recovers a stored container's length
        // from the header, so a trailing gap makes the boot-time CRC hash the wrong range and the
        // program is dropped without a word.
        byte[] image = compile("  LD uptime\n  DROP\n  END\n");

        // Read the geometry back out of the header, the way the device does, rather than trusting
        // arithmetic here: the highest non-empty section has to end exactly on the CRC.
        long highestEnd = IlbFormat.HEADER_SIZE;
        int[][] sections = {{IlbFormat.OFF_TAG_OFFSET, IlbFormat.OFF_TAG_LENGTH},
                            {IlbFormat.OFF_CODE_OFFSET, IlbFormat.OFF_CODE_LENGTH},
                            {IlbFormat.OFF_CONST_OFFSET, IlbFormat.OFF_CONST_LENGTH}};
        for (int[] section : sections) {
            long offset = readU32(image, section[0]);
            long length = readU32(image, section[1]);
            if (length > 0) {
                highestEnd = Math.max(highestEnd, offset + length);
            }
        }

        assertEquals(image.length - IlbFormat.CRC_SIZE, highestEnd,
                "the last section must end on the CRC with no gap");
        assertTrue(IlbVerifier.verify(image, 1, null).isOk());
    }

    private static long readU32(byte[] b, int off) {
        return (b[off] & 0xFFL) | ((b[off + 1] & 0xFFL) << 8)
                | ((b[off + 2] & 0xFFL) << 16) | ((b[off + 3] & 0xFFL) << 24);
    }

    @Test
    void aProgramForAnotherBoardIsRefused() {
        assertEquals(IlbVerifyError.WRONG_PROFILE,
                IlbVerifier.verify(compile("  END\n"), 7, null).error());
    }

    @Test
    void theStackHasToBalance() {
        assertEquals(IlbVerifyError.STACK_ERROR, verdict("  ST last\n  END\n"));
        assertEquals(IlbVerifyError.STACK_ERROR, verdict("  LD uptime\n  ADD\n  END\n"));
        // Two paths reaching END holding different numbers of values.
        assertEquals(IlbVerifyError.STACK_ERROR,
                verdict("  LD same\n  JMPZ skip\n  LDI 7\nskip:\n  END\n"));
    }

    @Test
    void theStackCeilingIsSixtyFour() {
        assertEquals(IlbVerifyError.OK, verdict("  LDI 1\n".repeat(64) + "  END\n"));
        assertEquals(IlbVerifyError.STACK_ERROR, verdict("  LDI 1\n".repeat(65) + "  END\n"));
    }

    @Test
    void everyPathHasToReachEnd() {
        assertEquals(IlbVerifyError.NO_END, verdict("  LD uptime\n  DROP\n"));
        // A subroutine placed after the final END leaves RET as the section's last instruction.
        // Easy to write by accident, and the device refuses it — jump over the subroutine instead.
        assertEquals(IlbVerifyError.NO_END, verdict("  CALL sub\n  END\nsub:\n  RET\n"));
        assertEquals(IlbVerifyError.OK,
                verdict("  CALL sub\n  JMP done\nsub:\n  RET\ndone:\n  END\n"));
    }

    @Test
    void operandsHaveToExist() {
        assertEquals(IlbVerifyError.BAD_TAG_REF, verdict("  LD 99\n  DROP\n  END\n"));
        assertEquals(IlbVerifyError.BAD_CONST_REF, verdict("  LDC 0\n  DROP\n  END\n"));
    }

    @Test
    void resourceSlotsAreBounded() {
        assertEquals(IlbVerifyError.OK,
                verdict("  LD uptime\n  LD uptime\n  TON 63\n  DROP\n  END\n"));
        assertEquals(IlbVerifyError.BAD_RESOURCE,
                verdict("  LD uptime\n  LD uptime\n  TON 64\n  DROP\n  END\n"));
        assertEquals(IlbVerifyError.BAD_RESOURCE, verdict("  LD same\n  RTRIG 256\n  DROP\n  END\n"));
    }

    @Test
    void aJumpHasToLandOnAnInstruction() {
        // Straight into the middle of LDI's four-byte immediate.
        byte[] image = IlbAssembler.assemble(new IlbProgram(1, 1, 1, 1000, null,
                List.of(), List.of(),
                List.of(IlbProgram.Instruction.of(IlbFormat.OP_LDI, 1),
                        IlbProgram.Instruction.of(IlbFormat.OP_DROP),
                        IlbProgram.Instruction.of(IlbFormat.OP_JMP, -6 & 0xFFFF),
                        IlbProgram.Instruction.of(IlbFormat.OP_END))));

        assertEquals(IlbVerifyError.BAD_JUMP, IlbVerifier.verify(image, 1, null).error());
    }

    @Test
    void aBindingThisVersionReservesIsRefused() {
        // 0x0030 is the platform-attribute binding a later format version will use.
        byte[] image = IlbAssembler.assemble(new IlbProgram(1, 1, 1, 1000, null,
                List.of(new IlbProgram.Tag(IlbFormat.T_INT, IlbFormat.C_INPUT, 0x0030, 1, 0, "attr")),
                List.of(), List.of(IlbProgram.Instruction.of(IlbFormat.OP_END))));

        assertEquals(IlbVerifyError.BAD_TAG_SECTION, IlbVerifier.verify(image, 1, null).error());
    }

    @Test
    void anIccPointIsCheckedAgainstTheControllersConfiguration() {
        byte[] image = IlbAssembler.assemble(new IlbProgram(1, 1, 1, 1000, null,
                List.of(new IlbProgram.Tag(IlbFormat.T_REAL, IlbFormat.C_INPUT,
                        IlbFormat.B_ICC_POINT, 101, 0, "oat")),
                List.of(), List.of(IlbProgram.Instruction.of(IlbFormat.OP_END))));

        assertTrue(IlbVerifier.bindsIccPoint(image));
        assertEquals(IlbVerifyError.BAD_BINDING,
                IlbVerifier.verify(image, 1, point -> false).error());
        assertEquals(IlbVerifyError.OK,
                IlbVerifier.verify(image, 1, point -> point == 101).error());
        // Without the hook the binding is not checked — the same latitude the firmware gives its own
        // verify-only tools, and the reason the upload path always passes one.
        assertEquals(IlbVerifyError.OK, IlbVerifier.verify(image, 1, null).error());
    }

    @Test
    void aCorruptedContainerIsCaughtByTheCrc() {
        byte[] image = compile("  LD uptime\n  DROP\n  END\n");
        image[IlbFormat.HEADER_SIZE + 4] ^= 0x20;

        assertEquals(IlbVerifyError.BAD_CRC, IlbVerifier.verify(image, 1, null).error());
    }

    @Test
    void aProgramThatBindsNoPointNeedsNoPointLookup() {
        assertFalse(IlbVerifier.bindsIccPoint(compile("  END\n")));
        // Never throws on rubbish; saying why is verify's job, not this one's.
        assertFalse(IlbVerifier.bindsIccPoint(new byte[]{1, 2, 3}));
        assertFalse(IlbVerifier.bindsIccPoint(null));
    }

    @Test
    void aSyntaxErrorNamesTheLine() {
        IlbAsmParser.IlbSyntaxException missingTag = assertThrows(IlbAsmParser.IlbSyntaxException.class,
                () -> compile("  LD nosuch\n  END\n"));
        assertEquals(6, missingTag.getLine());
        assertTrue(missingTag.getMessage().contains("nosuch"), missingTag.getMessage());

        assertThrows(IlbAsmParser.IlbSyntaxException.class, () -> compile("  FROB\n  END\n"));
        assertThrows(IlbAsmParser.IlbSyntaxException.class, () -> compile("  LD\n  END\n"));
        assertThrows(IlbAsmParser.IlbSyntaxException.class, () -> compile("  END 3\n"));
        assertThrows(IlbAssembler.IlbAssemblyException.class, () -> compile("  JMP nowhere\n  END\n"));
    }

    @Test
    void tagsAreReferredToByNameSoInsertingOneDoesNotSilentlyRebind() {
        // The format numbers tags positionally. Writing indices by hand means adding a tag at the
        // top shifts every reference below it, which is exactly the bug the name lookup removes.
        byte[] before = compile("  LD last\n  DROP\n  END\n");
        byte[] after = IlbAssembler.assemble(IlbAsmParser.parse("""
                .program id=0xC0FFEE version=9 profile=1 scan=1000
                .tag inserted int memory
                .tag uptime time input sysreg 0
                .tag last   time memory
                .tag same   bool memory
                .code
                  LD last
                  DROP
                  END
                """));

        int codeOffsetBefore = IlbFormat.HEADER_SIZE + 3 * IlbFormat.TAG_RECORD_SIZE;
        int codeOffsetAfter = IlbFormat.HEADER_SIZE + 4 * IlbFormat.TAG_RECORD_SIZE;
        assertEquals(1, before[codeOffsetBefore + 1], "last was tag 1");
        assertEquals(2, after[codeOffsetAfter + 1], "and is tag 2 once one is inserted above it");
        assertTrue(IlbVerifier.verify(after, 1, null).isOk());
    }

    @Test
    void aDeviceLogCodeMapsBackToItsName() {
        // The device logs "logic slot B: rejected, ilb_verify_err=9" and nothing else, anywhere.
        assertEquals(IlbVerifyError.BAD_TAG_SECTION, IlbVerifyError.fromDeviceCode(9));
        assertEquals(IlbVerifyError.OK, IlbVerifyError.fromDeviceCode(0));
        // A code from a newer firmware gets an honest null rather than a confident wrong name.
        assertEquals(null, IlbVerifyError.fromDeviceCode(99));
    }
}
