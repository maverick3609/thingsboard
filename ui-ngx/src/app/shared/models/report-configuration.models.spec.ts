// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
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
