// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
import { CustomTranslatePipe } from '@shared/pipe/custom-translate.pipe';
import {
  FormProperty,
  FormPropertyContainerType,
  FormPropertyType,
  toPropertyGroups
} from '@shared/models/dynamic-form.models';
import { DomSanitizer } from '@angular/platform-browser';

/**
 * `stackedLabels` is opt-in, and the reason it is opt-in is that every widget settings panel in
 * ThingsBoard renders through the same function. So the case that matters here is not the one the
 * gateway forms use — it is the default, which has to keep producing exactly the rows it did
 * before. That half cannot be caught by looking at the gateway UI, because the gateway UI never
 * takes it.
 */
describe('toPropertyGroups stackedLabels', () => {

  const translate = {transform: (value: string) => value} as CustomTranslatePipe;
  const sanitizer = {bypassSecurityTrustHtml: (html: string) => html} as unknown as DomSanitizer;

  const properties: FormProperty[] = [
    {id: 'name', name: 'Connection description', type: FormPropertyType.text, default: ''},
    {id: 'period', name: 'Time period', type: FormPropertyType.number, default: 1},
    {id: 'enabled', name: 'Enabled', type: FormPropertyType.switch, default: true}
  ];

  const containersFor = (stackedLabels: boolean) =>
    toPropertyGroups(properties, false, translate, sanitizer, stackedLabels)[0].containers;

  it('leaves short-labelled fields as rows by default', () => {
    const types = containersFor(false).map(c => c.type);
    expect(types).toEqual([FormPropertyContainerType.row, FormPropertyContainerType.row,
      FormPropertyContainerType.row]);
  });

  it('stacks every input field when asked', () => {
    const containers = containersFor(true);
    expect(containers[0].type).toBe(FormPropertyContainerType.field);
    expect(containers[1].type).toBe(FormPropertyContainerType.field);
  });

  // A switch has no label of its own inside the control, so stacking one would leave the toggle
  // captionless. It stays a row in both modes.
  it('keeps a switch as a row even when stacked', () => {
    expect(containersFor(true)[2].type).toBe(FormPropertyContainerType.row);
  });
});
