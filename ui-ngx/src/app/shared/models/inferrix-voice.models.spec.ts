// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
import { placeholdersMatch } from '@shared/models/inferrix-voice.models';

describe('placeholdersMatch', () => {

  const english = 'Alarm on ${alarmOriginatorName}. Value ${details.data}.';

  it('accepts the same placeholders in another order', () => {
    expect(placeholdersMatch(english, '${details.data} मान, ${alarmOriginatorName} पर अलार्म')).toBeTrue();
  });

  it('refuses a lost placeholder', () => {
    expect(placeholdersMatch(english, '${alarmOriginatorName} पर अलार्म')).toBeFalse();
  });

  it('refuses a duplicated placeholder', () => {
    expect(placeholdersMatch(english, '${alarmOriginatorName} ${alarmOriginatorName} ${details.data}')).toBeFalse();
  });

  it('refuses a changed placeholder', () => {
    expect(placeholdersMatch(english, '${alarmOriginator} ${details.data}')).toBeFalse();
  });

  it('accepts text without placeholders', () => {
    expect(placeholdersMatch('Fire alarm', 'आग का अलार्म')).toBeTrue();
  });

});
