///
/// Copyright © 2016-2026 The Inferrix Authors
///
/// Licensed under the Apache License, Version 2.0 (the "License");
/// you may not use this file except in compliance with the License.
/// You may obtain a copy of the License at
///
///     http://www.apache.org/licenses/LICENSE-2.0
///
/// Unless required by applicable law or agreed to in writing, software
/// distributed under the License is distributed on an "AS IS" BASIS,
/// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
/// See the License for the specific language governing permissions and
/// limitations under the License.
///

import { Component, Input } from '@angular/core';
import { LogicStatement, LogicStatementKind, LogicTag, newStatement }
  from '@shared/models/inferrix-logic.models';

/**
 * The steps of a logic program, in the order the controller runs them.
 *
 * Recursive: the branches of an IF are themselves a list of steps. Every step is depth-neutral on
 * the controller's operand stack, which is what lets them nest freely — the device's verifier
 * rejects a program where two paths reach the same instruction holding different numbers of values,
 * and building only from whole statements makes that impossible to write.
 */
@Component({
  selector: 'tb-controller-statements',
  templateUrl: './controller-statements.component.html',
  styleUrls: ['./controller-statements.component.scss'],
  standalone: false
})
export class ControllerStatementsComponent {

  @Input() statements: LogicStatement[] = [];
  @Input() tags: LogicTag[] = [];
  @Input() readonly = false;

  /** Offered in the order an engineer reaches for them, not the order the compiler defines them. */
  readonly kinds: {value: LogicStatementKind; labelKey: string}[] = [
    {value: 'ASSIGN', labelKey: 'inferrix.logic-add-assign'},
    {value: 'IF', labelKey: 'inferrix.logic-add-if'},
    {value: 'TIMER', labelKey: 'inferrix.logic-add-timer'},
    {value: 'COUNTER', labelKey: 'inferrix.logic-add-counter'},
    {value: 'PID', labelKey: 'inferrix.logic-add-pid'},
    {value: 'SET', labelKey: 'inferrix.logic-add-set'},
    {value: 'RESET', labelKey: 'inferrix.logic-add-reset'}
  ];

  /** Tags a step can write to. A step writes to a tag, never to an input it cannot drive. */
  writableTags(): LogicTag[] {
    return this.tags.filter(tag => tag.cls === 'OUTPUT' || tag.cls === 'MEMORY');
  }

  add(kind: LogicStatementKind): void {
    this.statements.push(newStatement(kind));
  }

  remove(index: number): void {
    this.statements.splice(index, 1);
  }

  move(index: number, by: number): void {
    const to = index + by;
    if (to < 0 || to >= this.statements.length) {
      return;
    }
    // Order is execution order, so moving a step is a real edit rather than a cosmetic one: a
    // value computed after it is read is a scan out of date.
    const [moved] = this.statements.splice(index, 1);
    this.statements.splice(to, 0, moved);
  }

  branchOf(statement: LogicStatement, which: 'then' | 'otherwise'): LogicStatement[] {
    if (!statement[which]) {
      statement[which] = [];
    }
    return statement[which];
  }
}
