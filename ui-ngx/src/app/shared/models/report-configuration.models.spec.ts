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

import {
  PageSize,
  PageSizeDimensions,
  ReportComponentType,
  newReportComponent,
  newPdfReportTemplateConfig,
  HeadingComponent
} from './report-configuration.models';

describe('report-configuration.models', () => {
  it('newPdfReportTemplateConfig has PE-shape defaults', () => {
    const c = newPdfReportTemplateConfig();
    expect(c.format).toBe('PDF');
    expect(c.components).toEqual([]);
    expect(c.pageSize).toBe(PageSize.A4);
  });

  it('newReportComponent stamps the discriminator', () => {
    const h = newReportComponent(ReportComponentType.HEADING) as HeadingComponent;
    expect(h.type).toBe('HEADING');
  });

  it('PageSizeDimensions matches the engine table', () => {
    expect(PageSizeDimensions[PageSize.A4]).toEqual({ width: 595, height: 842 });
  });
});
