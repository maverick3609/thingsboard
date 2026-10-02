// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { defaultHttpOptionsFromConfig, RequestConfig } from '@core/http/http-utils';
import { VoiceTranslateResponse } from '@shared/models/inferrix-voice.models';

/** Translation for voice templates. It runs offline, in inferrix-dialer on the Cortex host; nothing is stored. */
@Injectable({
  providedIn: 'root'
})
export class InferrixVoiceService {

  constructor(private http: HttpClient) {}

  public translate(text: string, config?: RequestConfig): Observable<VoiceTranslateResponse> {
    return this.http.post<VoiceTranslateResponse>('/api/inferrix/voice/translate', {text},
      defaultHttpOptionsFromConfig(config));
  }

}
