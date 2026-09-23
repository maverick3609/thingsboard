// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
import { FormProperty, FormPropertyType, FormSelectItem } from '@shared/models/dynamic-form.models';
import { escapeCell } from '@shared/models/inferrix-controller.models';

/**
 * What `GET /rest/v2/model-schemas` returns.
 *
 * Schemas are **OpenAPI 3.0**, produced by swagger's own `ModelConverters` against the stack's
 * model classes — so they carry `$ref`, `allOf`, `discriminator`, `format` and `example`, not the
 * plain JSON Schema a reader might assume. Nested types are `$ref`s into `components.schemas` so a
 * schema shared by many types serialises once.
 */
export interface GatewaySchemaDocument {
  families: {[family: string]: {[modelType: string]: any}};
  components: {schemas: {[name: string]: any}};
}

/**
 * How deep a schema may nest before the mapper stops.
 *
 * The shared-components layout makes `A -> B -> A` easy to reach without anyone being malicious, so
 * this is what terminates a cycle. It is not a taste limit: the real document nests about four
 * deep.
 */
export const SCHEMA_MAX_DEPTH = 8;

/** How many fields one schema level may contribute, so a huge document cannot lock the browser. */
export const SCHEMA_MAX_PROPERTIES = 200;

const REF_PREFIX = '#/components/schemas/';

/**
 * A property name this mapper will render.
 *
 * The gateway's models are Java classes serialised by Jackson, so every real field name is plain
 * camelCase — nothing in the stack's whole schema document falls outside this.
 *
 * What falls outside is markup, which the rest of the mapper escapes but which has no business in
 * an identifier position at all, and `__proto__`, which is the one name that behaves differently
 * from every other key: `JSON.parse` makes it a real own property, so a gateway can send one, and
 * assigning it onto a value object walks the prototype setter instead of adding a key. The field
 * then silently does not exist and the object acquires a prototype of the gateway's choosing.
 * Neither is an escalation on its own, but a mapper declared to be this feature's security
 * boundary should not have a class of input it merely survives.
 */
const PROPERTY_NAME = /^[A-Za-z][A-Za-z0-9_]{0,63}$/;

/**
 * Components a gateway declares as an object while putting a plain string on the wire.
 *
 * `MessageTranslation` is the only one, and it is **legacy handling kept on purpose**. It was
 * declared `{key, args}` because that is the Java class swagger introspected, while
 * `StackRestJacksonModule` registers a serializer and no deserializer — so the wire carries a
 * translated string in both directions, a form built from that schema offered two inputs for one
 * line of text, and a value written back became the *key*.
 *
 * Stack ask A12 fixed it: a current gateway declares these as `{"type": "string", "readOnly": true}`
 * and {@link isReadOnly} handles them with no type name involved. But A12 landed **after** 5.1.0
 * shipped, and a 5.1.0 gateway from before it is still adoptable and still sends the `$ref`. So this
 * stays until the oldest adoptable gateway carries the fix, and not one release longer — a generic
 * mapper carrying a list of model class names is exactly the debt A12 was raised to remove.
 */
const WIRE_STRING_COMPONENTS = new Set<string>(['MessageTranslation']);

/**
 * A field the gateway computes and will not accept back.
 *
 * `readOnly` is the gateway's own word for it, and there are 436 of them in a real 5.1.0 document —
 * a data source's connection description, a point's runtime state, every derived display string.
 * Rendering one as an editable input invites an operator to change a value that is silently
 * discarded, and leaves them with no way to tell which fields their edit will actually reach.
 *
 * It is also how a current gateway declares the fields {@link WIRE_STRING_COMPONENTS} names by hand,
 * which is why that set is legacy and has a removal condition rather than a future.
 */
const isReadOnly = (schema: any): boolean => schema?.readOnly === true;

/**
 * A field the gateway accepts but never returns.
 *
 * `writeOnly` marks exactly the secrets: an MQTT broker's `userPassword` and `privateKey`, an OPC
 * server's `password`. It is the **only** signal for them — a real document carries no
 * `format: password` at all, so a mapper that keyed on format alone would render a broker
 * credential as a plain text input.
 *
 * Two consequences, and the second is the one that bites. Rendering: password-typed, never in the
 * clear. Saving: the field comes back absent on read, so the form holds an empty value for it, and
 * a save that sent that empty value would **blank the stored secret**. Emptiness therefore has to
 * mean "unchanged" — see {@link GatewayModelDialogComponent.save}.
 */
const isWriteOnly = (schema: any): boolean => schema?.writeOnly === true;

/**
 * Turns one model type's schema into ThingsBoard form properties.
 *
 * **This is the security boundary of the whole gateway feature.** The schema arrives from a device
 * on a LAN and is rendered into a tenant administrator's browser, and TB's form renderer compiles
 * `FormProperty.condition` to executable JavaScript. So the mapper is an allowlist in both
 * directions: it only ever *constructs* properties, never copies an object out of the schema, and
 * the type it assigns comes from a fixed table rather than from anything the gateway said.
 *
 * Returns an empty array for a family or type that is not present, and never throws — one bad
 * schema costs a field, not the page.
 */
export const schemaToFormProperties = (doc: GatewaySchemaDocument, family: string,
                                       modelType: string): FormProperty[] => {
  const schema = doc?.families?.[family]?.[modelType];
  if (!schema) {
    return [];
  }
  return propertiesOf(schema, doc, 0, new Set<string>());
};

/**
 * The same mapping, for a schema that lives in `components.schemas` rather than in a family.
 *
 * A data point is the case that needs it: there is one point model for every protocol — the
 * variation is all in its nested locator — so the gateway publishes `DataPointModel` as a shared
 * component and gives it no family of its own.
 */
export const componentToFormProperties = (doc: GatewaySchemaDocument,
                                          componentName: string): FormProperty[] => {
  const schema = doc?.components?.schemas?.[componentName];
  return schema ? propertiesOf(schema, doc, 0, new Set<string>()) : [];
};

const propertiesOf = (schema: any, doc: GatewaySchemaDocument, depth: number,
                      seen: Set<string>): FormProperty[] => {
  if (depth >= SCHEMA_MAX_DEPTH) {
    return [];
  }
  const merged = flatten(schema, doc, depth, seen);
  const properties = merged.properties ?? {};
  const required: string[] = Array.isArray(merged.required) ? merged.required : [];

  return Object.keys(properties)
    .filter(key => PROPERTY_NAME.test(key))
    .slice(0, SCHEMA_MAX_PROPERTIES)
    .map(key => toProperty(key, properties[key], required.includes(key), doc, depth, seen));
};

/**
 * Collapses `allOf` into one schema.
 *
 * swagger emits `allOf: [{$ref: Base}, {properties: …}]` for a subclass, which is how the stack
 * expresses inheritance — eight times in the real document. A mapper that only read `properties`
 * would silently drop every inherited field and render a form missing half its inputs.
 */
const flatten = (schema: any, doc: GatewaySchemaDocument, depth: number, seen: Set<string>): any => {
  const resolved = deref(schema, doc, seen);
  if (!resolved || !Array.isArray(resolved.allOf)) {
    return resolved ?? {};
  }
  const merged: any = {properties: {...(resolved.properties ?? {})},
                       required: [...(resolved.required ?? [])]};
  for (const member of resolved.allOf) {
    if (depth >= SCHEMA_MAX_DEPTH) {
      break;
    }
    const part = flatten(member, doc, depth + 1, seen);
    Object.assign(merged.properties, part.properties ?? {});
    if (Array.isArray(part.required)) {
      merged.required.push(...part.required);
    }
  }
  return merged;
};

/**
 * Follows a `$ref` into `components.schemas`.
 *
 * Returns null for a ref that does not resolve, which the caller turns into a disabled placeholder.
 * `seen` is scoped per branch rather than globally: the same shared schema legitimately appears in
 * many places, and a global set would render it once and blank it everywhere else.
 */
const deref = (schema: any, doc: GatewaySchemaDocument, seen: Set<string>): any => {
  if (!schema || typeof schema !== 'object') {
    return null;
  }
  const ref = schema.$ref;
  if (typeof ref !== 'string') {
    return schema;
  }
  if (!ref.startsWith(REF_PREFIX)) {
    return null;
  }
  const name = ref.substring(REF_PREFIX.length);
  if (seen.has(name)) {
    return null;
  }
  return doc?.components?.schemas?.[name] ?? null;
};

const toProperty = (key: string, raw: any, required: boolean, doc: GatewaySchemaDocument,
                    depth: number, seen: Set<string>): FormProperty => {
  const base: FormProperty = {
    id: escapeCell(key),
    name: humanise(key),
    type: FormPropertyType.text,
    default: null,
    required
  } as FormProperty;

  const refName = typeof raw?.$ref === 'string' && raw.$ref.startsWith(REF_PREFIX)
    ? raw.$ref.substring(REF_PREFIX.length) : null;
  if (refName && WIRE_STRING_COMPONENTS.has(refName)) {
    return {...base, disabled: true, hint: 'Reported by the gateway'};
  }

  const resolved = deref(raw, doc, seen);
  if (!resolved) {
    // A ref the gateway sent but did not include. Disabled rather than dropped, so the operator
    // can see that a field exists and is not editable instead of wondering what is missing.
    return {...base, disabled: true,
      hint: refName ? 'Unresolved schema reference: ' + escapeCell(refName) : undefined};
  }

  if (resolved.description) {
    base.hint = escapeCell(resolved.description);
  }

  // Checked on both the property's own schema and the resolved one: swagger writes `readOnly`
  // beside a `$ref` as often as inside the target, and either spelling means the same thing.
  if (isReadOnly(raw) || isReadOnly(resolved)) {
    return {...base, disabled: true};
  }
  if (isWriteOnly(raw) || isWriteOnly(resolved)) {
    return {...base, type: FormPropertyType.password};
  }

  const branch = refName ? new Set<string>([...seen, refName]) : seen;
  const flat = flatten(resolved, doc, depth, branch);

  if (Array.isArray(resolved.enum)) {
    const items = selectItems(resolved.enum);
    // Every option was refused, so there is nothing to choose from. A select with no items is a
    // dead control; a text field at least lets the operator type what the gateway wants.
    return items.length
      ? {...base, type: FormPropertyType.select, items}
      : base;
  }
  if (resolved.type === 'boolean') {
    return {...base, type: FormPropertyType.switch, default: false};
  }
  if (resolved.type === 'integer' || resolved.type === 'number') {
    const numeric: FormProperty = {...base, type: FormPropertyType.number};
    if (typeof resolved.minimum === 'number') {
      numeric.min = resolved.minimum;
    }
    if (typeof resolved.maximum === 'number') {
      numeric.max = resolved.maximum;
    }
    return numeric;
  }
  if (resolved.type === 'array') {
    const item = toProperty(key, resolved.items ?? {type: 'string'}, false, doc, depth + 1, branch);
    const array: FormProperty = {...base, type: FormPropertyType.array, arrayItemType: item.type,
      arrayItemName: base.name};
    // An array of objects needs the item's own fields carried up. TB builds each row by cloning
    // the ARRAY property and swapping its type for arrayItemType (`toPropertyGroups`), so a
    // fieldset row renders whatever `properties` sits on the array itself. Dropping them here
    // produced a row with no inputs -- which does not merely look empty: the renderer writes its
    // value back over the whole array, so opening an event handler and saving it would have
    // reduced every recipient and every event-type matcher to a bare discriminator.
    if (item.type === FormPropertyType.fieldset) {
      (array as any).properties = (item as any).properties ?? [];
    }
    return array;
  }
  if (resolved.type === 'string') {
    if (resolved.format === 'date-time' || resolved.format === 'date') {
      return {...base, type: FormPropertyType.datetime,
        dateTimeType: resolved.format === 'date' ? 'date' : 'datetime'};
    }
    // Never rendered in the clear where the schema says it is a secret.
    if (resolved.format === 'password') {
      return {...base, type: FormPropertyType.password};
    }
    return base;
  }
  if (flat.properties && Object.keys(flat.properties).length) {
    return {...base, type: FormPropertyType.fieldset,
      properties: propertiesOf(resolved, doc, depth + 1, branch)};
  }
  // Everything else — including a type this build has never heard of, and including the names of
  // real FormPropertyTypes that render executable or markup content (javascript, html, markdown).
  // A gateway naming one of those must not be given it.
  return base;
};

/**
 * A select option's **value** is submitted back to the gateway verbatim, so it must not be escaped
 * — a legitimate value containing `&` would be sent as `&amp;` and rejected. That leaves it as the
 * one string in this mapper that reaches the form model unescaped, so instead of sanitising it,
 * anything that is not plausibly an enum constant is refused outright.
 *
 * These are Java enum names (`DO_NOT_ALLOW`) or numbers. A value carrying markup is not a value the
 * stack can have produced, so dropping it costs nothing real and removes the only path by which
 * device-supplied markup could reach a renderer that trusts it.
 */
const ENUM_VALUE = /^[A-Za-z0-9_.\- ]{1,128}$/;

const selectItems = (values: any[]): FormSelectItem[] =>
  values
    .slice(0, SCHEMA_MAX_PROPERTIES)
    .filter(value => typeof value === 'number'
      || (typeof value === 'string' && ENUM_VALUE.test(value)))
    .map(value => ({value, label: escapeCell(value)}));

/**
 * `userPassword` becomes "User password".
 *
 * Stack ask A1 ships no i18n labels and no help text, and will not: the schema is generated from
 * Java classes. Blocking on a dictionary lookup that never answers would leave every form showing
 * raw identifiers, so the field name is derived from the property name and escaped like any other
 * device-supplied string.
 */
const humanise = (key: string): string => {
  const words = escapeCell(key)
    .replace(/([a-z0-9])([A-Z])/g, '$1 $2')
    .replace(/[_-]+/g, ' ')
    .trim()
    .toLowerCase();
  return words.charAt(0).toUpperCase() + words.slice(1);
};
