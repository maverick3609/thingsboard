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

/**
 * A logic program as the editor holds it.
 *
 * Mirrors the platform's `IlbBlock`, which compiles it to the bytecode the controller runs. The
 * editor works in statements rather than instructions because the controller is a stack machine:
 * assigning one comparison to one output is four instructions in an order that only makes sense if
 * you are already thinking about a stack.
 */

export type LogicDataType = 'BOOL' | 'INT' | 'REAL' | 'TIME';
export type LogicTagClass = 'INPUT' | 'OUTPUT' | 'MEMORY' | 'SYSTEM';
export type LogicBinding = 'NONE' | 'LOCAL_DI' | 'LOCAL_DO' | 'LOCAL_AI' | 'LOCAL_AO'
  | 'ICC_POINT' | 'SYSTEM_REGISTER';

export interface LogicTag {
  name: string;
  dataType: LogicDataType;
  cls: LogicTagClass;
  binding: LogicBinding;
  address?: number;
  initial?: number;
}

export interface LogicExpression {
  kind: 'LITERAL' | 'TAG' | 'BINARY' | 'UNARY' | 'CALL';
  dataType?: LogicDataType;
  number?: number;
  flag?: boolean;
  tag?: string;
  op?: string;
  fn?: string;
  slot?: number;
  args?: LogicExpression[];
}

export type LogicStatementKind = 'ASSIGN' | 'IF' | 'SET' | 'RESET' | 'TIMER' | 'COUNTER' | 'PID';

export interface LogicStatement {
  kind: LogicStatementKind;
  target?: string;
  value?: LogicExpression;
  condition?: LogicExpression;
  then?: LogicStatement[];
  otherwise?: LogicStatement[];
  timerKind?: 'TON' | 'TOF' | 'TP';
  counterKind?: 'CTU' | 'CTD';
  slot?: number;
  input?: LogicExpression;
  preset?: LogicExpression;
  reset?: LogicExpression;
  kp?: LogicExpression;
  ki?: LogicExpression;
  kd?: LogicExpression;
  setpoint?: LogicExpression;
  processValue?: LogicExpression;
}

export interface LogicProgram {
  programId: number;
  programVersion: number;
  profile: number;
  scanPeriodMs: number;
  tags: LogicTag[];
  statements: LogicStatement[];
}

export interface LogicCompileResult {
  ok: boolean;
  message?: string;
  line: number;
  image?: string;
  size: number;
  programId: number;
  programVersion: number;
  tagCount: number;
  codeLength: number;
}

/** What a tag can be wired to, and what the address means for each. */
export const LOGIC_BINDINGS: {value: LogicBinding; labelKey: string; needsAddress: boolean;
  addressLabelKey?: string; fromPoints?: boolean}[] = [
  {value: 'NONE', labelKey: 'inferrix.logic-binding-none', needsAddress: false},
  {value: 'LOCAL_DI', labelKey: 'inferrix.logic-binding-di', needsAddress: true,
    addressLabelKey: 'inferrix.logic-channel'},
  {value: 'LOCAL_DO', labelKey: 'inferrix.logic-binding-do', needsAddress: true,
    addressLabelKey: 'inferrix.logic-channel'},
  {value: 'LOCAL_AI', labelKey: 'inferrix.logic-binding-ai', needsAddress: true,
    addressLabelKey: 'inferrix.logic-channel'},
  {value: 'LOCAL_AO', labelKey: 'inferrix.logic-binding-ao', needsAddress: true,
    addressLabelKey: 'inferrix.logic-channel'},
  {value: 'ICC_POINT', labelKey: 'inferrix.logic-binding-point', needsAddress: true,
    addressLabelKey: 'inferrix.logic-point', fromPoints: true},
  {value: 'SYSTEM_REGISTER', labelKey: 'inferrix.logic-binding-register', needsAddress: true,
    addressLabelKey: 'inferrix.logic-register'}
];

/**
 * The controller's read-only metrics, exposed as bindable tags.
 *
 * Hard-coded rather than read from the device because the device offers no way to enumerate them —
 * they are a fixed table in the firmware (INTEGRATION-API §7.5). An unknown id freezes the tag
 * rather than failing, which is exactly why the operator should be picking from a list.
 */
export const LOGIC_SYSTEM_REGISTERS: {id: number; nameKey: string; dataType: LogicDataType}[] = [
  {id: 0x0000, nameKey: 'inferrix.register-uptime', dataType: 'TIME'},
  {id: 0x0001, nameKey: 'inferrix.register-scan-last', dataType: 'INT'},
  {id: 0x0002, nameKey: 'inferrix.register-scan-max', dataType: 'INT'},
  {id: 0x0003, nameKey: 'inferrix.register-scan-overruns', dataType: 'INT'},
  {id: 0x0004, nameKey: 'inferrix.register-epoch', dataType: 'INT'},
  {id: 0x0005, nameKey: 'inferrix.register-time-state', dataType: 'INT'},
  {id: 0x0006, nameKey: 'inferrix.register-peer-mask', dataType: 'INT'},
  {id: 0x0010, nameKey: 'inferrix.register-arith-faults', dataType: 'INT'}
];

/** Operators offered for a two-sided comparison or sum, grouped so the list stays readable. */
export const LOGIC_BINARY_OPS: {value: string; label: string; group: 'compare' | 'math' | 'logic'}[] = [
  {value: 'LT', label: '<', group: 'compare'},
  {value: 'LE', label: '≤', group: 'compare'},
  {value: 'GT', label: '>', group: 'compare'},
  {value: 'GE', label: '≥', group: 'compare'},
  {value: 'EQ', label: '=', group: 'compare'},
  {value: 'NE', label: '≠', group: 'compare'},
  {value: 'ADD', label: '+', group: 'math'},
  {value: 'SUB', label: '−', group: 'math'},
  {value: 'MUL', label: '×', group: 'math'},
  {value: 'DIV', label: '÷', group: 'math'},
  {value: 'MOD', label: 'mod', group: 'math'},
  {value: 'AND', label: 'AND', group: 'logic'},
  {value: 'OR', label: 'OR', group: 'logic'},
  {value: 'XOR', label: 'XOR', group: 'logic'}
];

export const LOGIC_UNARY_OPS = ['NOT', 'NEG', 'ABS', 'INT_TO_REAL', 'REAL_TO_INT'];

export const LOGIC_FUNCTIONS: {value: string; arity: number; needsSlot?: boolean}[] = [
  {value: 'MIN', arity: 2},
  {value: 'MAX', arity: 2},
  {value: 'LIMIT', arity: 3},
  {value: 'SCALE', arity: 4},
  {value: 'RISING', arity: 1, needsSlot: true},
  {value: 'FALLING', arity: 1, needsSlot: true}
];

/** A fresh expression of the given kind, with the right number of holes to fill in. */
export const newExpression = (kind: LogicExpression['kind']): LogicExpression => {
  switch (kind) {
    case 'LITERAL':
      return {kind: 'LITERAL', dataType: 'REAL', number: 0};
    case 'TAG':
      return {kind: 'TAG', tag: ''};
    case 'BINARY':
      return {kind: 'BINARY', op: 'LT', args: [{kind: 'TAG', tag: ''}, newExpression('LITERAL')]};
    case 'UNARY':
      return {kind: 'UNARY', op: 'NOT', args: [{kind: 'TAG', tag: ''}]};
    case 'CALL':
      return {kind: 'CALL', fn: 'MIN', args: [{kind: 'TAG', tag: ''}, newExpression('LITERAL')]};
  }
};

/** A fresh statement of the given kind. Defaults are the common case, not empty holes. */
export const newStatement = (kind: LogicStatementKind): LogicStatement => {
  switch (kind) {
    case 'ASSIGN':
      return {kind, target: '', value: newExpression('LITERAL')};
    case 'IF':
      return {kind, condition: newExpression('BINARY'), then: [], otherwise: []};
    case 'SET':
    case 'RESET':
      return {kind, target: '', condition: newExpression('BINARY')};
    case 'TIMER':
      return {kind, target: '', timerKind: 'TON', slot: 0, input: newExpression('TAG'),
        preset: {kind: 'LITERAL', dataType: 'TIME', number: 1000}};
    case 'COUNTER':
      return {kind, target: '', counterKind: 'CTU', slot: 0, input: newExpression('TAG'),
        preset: {kind: 'LITERAL', dataType: 'INT', number: 10},
        reset: {kind: 'LITERAL', dataType: 'BOOL', flag: false}};
    case 'PID':
      return {kind, target: '', slot: 0,
        kp: {kind: 'LITERAL', dataType: 'REAL', number: 1},
        ki: {kind: 'LITERAL', dataType: 'REAL', number: 0},
        kd: {kind: 'LITERAL', dataType: 'REAL', number: 0},
        setpoint: newExpression('LITERAL'), processValue: newExpression('TAG')};
  }
};
