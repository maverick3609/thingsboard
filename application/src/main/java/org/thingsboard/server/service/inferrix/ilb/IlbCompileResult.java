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

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;

import java.util.Base64;

/**
 * What came back from compiling a logic program.
 *
 * <p>A failure is a result, not an error status: a misspelt tag and a container the device would
 * refuse are both things the operator fixes in the editor, and both need the same answer — what is
 * wrong and where. Reporting them as HTTP failures would leave the UI showing a stack trace where
 * it should be highlighting a line.
 */
@Getter
public class IlbCompileResult {

    @Schema(description = "Whether the program compiled and would be accepted by the controller")
    private final boolean ok;

    @Schema(description = "What is wrong, naming the line or the offending tag; null when ok")
    private final String message;

    @Schema(description = "The source line at fault, or 0 when the problem is not a line")
    private final int line;

    @Schema(description = "The compiled container, base64; null unless ok")
    private final String image;

    @Schema(description = "Size of the compiled container in bytes")
    private final int size;

    @Schema(description = "Program id from the header")
    private final int programId;

    @Schema(description = "Program version — the controller boots the highest it holds")
    private final long programVersion;

    @Schema(description = "Number of tags the program declares")
    private final int tagCount;

    @Schema(description = "Size of the compiled code section in bytes")
    private final int codeLength;

    private IlbCompileResult(boolean ok, String message, int line, String image, int size,
                             int programId, long programVersion, int tagCount, int codeLength) {
        this.ok = ok;
        this.message = message;
        this.line = line;
        this.image = image;
        this.size = size;
        this.programId = programId;
        this.programVersion = programVersion;
        this.tagCount = tagCount;
        this.codeLength = codeLength;
    }

    public static IlbCompileResult ok(byte[] image, IlbVerifier.Result result) {
        return new IlbCompileResult(true, null, 0, Base64.getEncoder().encodeToString(image),
                image.length, result.programId(), result.programVersion(), result.tagCount(),
                result.codeLength());
    }

    public static IlbCompileResult failed(String message, int line) {
        return new IlbCompileResult(false, message, line, null, 0, 0, 0, 0, 0);
    }
}
