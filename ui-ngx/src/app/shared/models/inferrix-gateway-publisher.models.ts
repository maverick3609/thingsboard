// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
import { FormProperty, FormPropertyType } from '@shared/models/dynamic-form.models';

/**
 * A publisher — one outbound connection the gateway pushes point values through.
 *
 * `points` arrives inline on every read, which is the whole reason published points need no list
 * of their own: opening a publisher already has them. They are still *written* one at a time
 * through `/v2/published-points`, exactly as the gateway's own webapp does — a publisher save does
 * not carry its points, and sending them back inside one would be ignored.
 */
export interface GatewayPublisher {
  id?: number;
  xid?: string;
  name?: string;
  modelType?: string;
  enabled?: boolean;
  points?: GatewayPublishedPoint[];
  [field: string]: any;
}

/** One data point as one publisher sends it. `dataPointXid` is the point it reads. */
export interface GatewayPublishedPoint {
  id?: number;
  xid?: string;
  name?: string;
  modelType?: string;
  enabled?: boolean;
  dataPointXid?: string;
  publisherXid?: string;
  /** The gateway's own liveness flag. Computed there, never sent back. */
  status?: boolean;
  [field: string]: any;
}

/** `/v2/publisher-types`, same shape as `/v2/data-source-types`. */
export interface GatewayPublisherType {
  type: string;
  name?: string;
}

/**
 * Fields a published point form must not own.
 *
 * `dataPointXid` and `publisherXid` are the two links that place the row — both are chosen by
 * opening the publisher and picking the point, not typed — and `status` is the gateway's own
 * liveness flag, which it computes and never reads back.
 */
export const PUBLISHED_POINT_STRUCTURAL_FIELDS =
  ['id', 'xid', 'name', 'modelType', 'dataPointXid', 'publisherXid', 'status'];

const option = (value: string, label: string) => ({value, label});

/**
 * The per-type fields of a published point, written out rather than mapped from the schema.
 *
 * **This is a workaround for a gap in the gateway, not a design choice.** Every other form in this
 * feature is built from the schema document the gateway publishes, which is what lets it render
 * protocol modules nobody has written yet. Published points are the one family where that document
 * is not enough: the gateway declares all seven `AbstractPublishedPointModel*` components with the
 * same seven generic fields — `id`, `xid`, `name`, `modelType`, `enabled`, `dataPointXid`,
 * `publisherXid` — and none of the fields that actually configure a point. A live
 * `INTEGRATION_MQTT_SENDER.POINT` on this gateway carries `publishTopic`, `publishTopicType`,
 * `publishQosType`, `subscribeTopic` and `subscribeTopicType`; the schema mentions none of them.
 * A purely schema-driven form would therefore render five inputs, none of them the topic, and
 * quietly drop the rest on save.
 *
 * So these lists are transcribed from the gateway's own webapp, which hard-codes one form
 * component per point type for the same reason. Raised with the stack team as **D28**; when the
 * gateway declares these fields, delete this table and the forms keep working.
 *
 * Only types whose field list could be read out of the shipped webapp are listed. Anything else
 * falls back to the schema's generic fields — fewer inputs than the type really has, but never a
 * wrong one.
 *
 * Labels are plain text rather than translation keys, exactly as the schema mapper's `humanise`
 * produces for every other gateway form: `customTranslate` passes a string it cannot resolve
 * straight through, so a curated form and a schema-built one read the same on screen.
 */
export const PUBLISHED_POINT_PROPERTIES: {[modelType: string]: FormProperty[]} = {
  'INTEGRATION_MQTT_SENDER.POINT': [
    {id: 'publishTopic', name: 'Publish topic', type: FormPropertyType.text,
      default: ''},
    {id: 'publishTopicType', name: 'Publish topic type',
      type: FormPropertyType.select, default: 'THINGSBOARD', items: [
        option('NONE', 'None'), option('PLAIN', 'Plain'), option('JSON', 'Json'),
        option('JSON_WITH_TIMESTAMP', 'Json With Timestamp'),
        option('INFERRIX_JSON', 'Inferrix Json'),
        option('DATASOURCE_PUBLISHER', 'Generic Json'), option('THINGSBOARD', 'Platform Json')]},
    {id: 'publishQosType', name: 'Publish QOS type', type: FormPropertyType.select,
      default: 'EXACTLY_ONCE', items: [
        option('AT_MOST_ONCE', 'At Most Once'), option('ATLEAST_ONCE', 'Atleast Once'),
        option('EXACTLY_ONCE', 'Exactly Once'), option('FAILURE', 'Failure')]},
    {id: 'subscribeTopic', name: 'Subscribe topic', type: FormPropertyType.text,
      default: ''},
    {id: 'subscribeTopicType', name: 'Subscribe topic type',
      type: FormPropertyType.select, default: 'THINGSBOARD_SUBSCRIPTION', items: [
        option('INFERRIX_JSON', 'Inferrix Json'),
        option('LED_ASSET_TRACKING', 'Led Asset Tracking'),
        option('THINGSBOARD', 'Platform Json'),
        option('THINGSBOARD_SUBSCRIPTION', 'Platform Subscriber')]}
  ],
  'MESH_SENDER.POINT': [
    {id: 'attributeId', name: 'Attribute id', type: FormPropertyType.number,
      default: null},
    {id: 'type', name: 'Type', type: FormPropertyType.text, default: ''},
    {id: 'settable', name: 'Settable', type: FormPropertyType.switch,
      default: false}
  ],
  'BACNET_SENDER.POINT': [
    {id: 'objectType', name: 'Object type', type: FormPropertyType.text,
      default: ''},
    {id: 'objectName', name: 'Object name', type: FormPropertyType.text,
      default: ''},
    {id: 'instanceNumber', name: 'Instance number',
      type: FormPropertyType.number, default: null},
    {id: 'useIntrinsicAlarms', name: 'Use intrinsic alarms',
      type: FormPropertyType.switch, default: false}
  ]
};
