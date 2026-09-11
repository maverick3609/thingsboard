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
import { LOGIC_BINARY_OPS, LOGIC_FUNCTIONS, LOGIC_UNARY_OPS, LogicExpression, LogicTag,
  newExpression } from '@shared/models/inferrix-logic.models';

/**
 * One value in a logic program, edited in place.
 *
 * Recursive, because a value can be built out of other values — `oat < 18.0` is a comparison of a
 * tag and a number, and either side could itself be a sum. The alternative is a formula box, which
 * needs a parser, a grammar and error positions before it can reject anything; picking from lists
 * cannot express a syntax error at all.
 *
 * Editing mutates the bound object rather than emitting a new one. The program is one tree held by
 * the editor and sent whole to be compiled, so a change three levels down has nowhere to go but
 * into the object it belongs to.
 */
@Component({
  selector: 'tb-controller-expression',
  templateUrl: './controller-expression.component.html',
  styleUrls: ['./controller-expression.component.scss'],
  standalone: false
})
export class ControllerExpressionComponent {

  /** The node being edited. Mutated in place; see the class note. */
  @Input() expression: LogicExpression;

  /** Declared tags, so a value can be picked by name rather than typed. */
  @Input() tags: LogicTag[] = [];

  @Input() readonly = false;

  /** Narrows the kind picker when only one kind makes sense, e.g. a timer's preset. */
  @Input() only: LogicExpression['kind'][] | null = null;

  readonly kinds: {value: LogicExpression['kind']; labelKey: string}[] = [
    {value: 'TAG', labelKey: 'inferrix.logic-kind-tag'},
    {value: 'LITERAL', labelKey: 'inferrix.logic-kind-literal'},
    {value: 'BINARY', labelKey: 'inferrix.logic-kind-binary'},
    {value: 'UNARY', labelKey: 'inferrix.logic-kind-unary'},
    {value: 'CALL', labelKey: 'inferrix.logic-kind-call'}
  ];

  readonly binaryOps = LOGIC_BINARY_OPS;
  readonly unaryOps = LOGIC_UNARY_OPS;
  readonly functions = LOGIC_FUNCTIONS;

  get availableKinds() {
    return this.only ? this.kinds.filter(k => this.only.includes(k.value)) : this.kinds;
  }

  /**
   * Rebuilds the node when its kind changes.
   *
   * Keeping the old fields around would send `number` on a node that is now a tag reference, and
   * the compiler reads by kind — so the stale value would be silently ignored rather than flagged.
   */
  changeKind(kind: LogicExpression['kind']): void {
    const fresh = newExpression(kind);
    Object.keys(this.expression).forEach(key => delete (this.expression as any)[key]);
    Object.assign(this.expression, fresh);
  }

  /** Grows or trims the argument list when a function with a different arity is picked. */
  changeFunction(fn: string): void {
    const spec = this.functions.find(f => f.value === fn);
    this.expression.fn = fn;
    const args = this.expression.args ?? [];
    while (args.length < (spec?.arity ?? 0)) {
      args.push(newExpression('LITERAL'));
    }
    args.length = spec?.arity ?? 0;
    this.expression.args = args;
    if (!spec?.needsSlot) {
      delete this.expression.slot;
    } else if (this.expression.slot === undefined) {
      this.expression.slot = 0;
    }
  }

  needsSlot(fn: string): boolean {
    return !!this.functions.find(f => f.value === fn)?.needsSlot;
  }
}
