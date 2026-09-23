// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
import { ChangeDetectorRef, Component } from '@angular/core';
import { forkJoin, Observable, of } from 'rxjs';
import { catchError } from 'rxjs/operators';
import { InferrixGatewayService } from '@core/http/inferrix-gateway.service';
import { GatewayPanelComponent } from '@home/pages/inferrix/gateway/gateway-panel.component';
import { GatewayAbout, GatewayLanguage, GatewayMonitorValue, GatewayNetworkInterface,
  gatewaySettingRows, gatewaySettingValue,
  gatewaySettingWithheld } from '@shared/models/inferrix-gateway-system.models';

/**
 * What the gateway says about itself.
 *
 * Read-only, and that is a decision rather than a phase boundary. `PUT /v2/system-setting/{key}` is
 * an arbitrary-key write over the gateway's entire configuration keyspace — mail relay, backup
 * paths, thread pools, licence — and it was removed from the platform's allowlist after adversarial
 * review. So this tab reports; the gateway's own interface is where its settings are changed.
 *
 * Five reads, run together and each allowed to fail on its own: a gateway that answers `about` and
 * refuses `system-setting` is the normal shape of a customer user's session, and one refusal must
 * not blank the other four cards.
 */
@Component({
  selector: 'tb-gateway-system',
  templateUrl: './gateway-system.component.html',
  styleUrls: ['./gateway-system.component.scss'],
  standalone: false
})
export class GatewaySystemComponent extends GatewayPanelComponent {

  readonly valueOf = gatewaySettingValue;

  about: GatewayAbout;
  interfaces: GatewayNetworkInterface[] = [];
  languages: GatewayLanguage[] = [];
  monitor: GatewayMonitorValue[] = [];
  settings: {key: string; value: string}[] = [];

  /** How many settings the gateway sent that this page deliberately does not paint. */
  withheldCount = 0;

  loading = false;
  error: string;

  constructor(private gatewayService: InferrixGatewayService,
              protected cd: ChangeDetectorRef) {
    super();
  }

  protected load(): void {
    this.refresh();
  }

  refresh(): void {
    if (!this.deviceId) {
      return;
    }
    this.loading = true;
    this.error = null;
    const config = {ignoreLoading: true};
    // `null` rather than a rethrow, so one refused read costs its own card and nothing else. The
    // first error still reaches the operator -- `about` is the one that fails when the gateway is
    // simply unreachable, and that is the message worth showing.
    const soft = <T>(source: Observable<T>): Observable<T> =>
      source.pipe(catchError((error: any) => {
        this.error = this.error ?? this.messageOf(error);
        return of(null as T);
      }));
    forkJoin({
      about: soft(this.gatewayService.getAbout(this.deviceId, config)),
      settings: soft(this.gatewayService.getSystemSettings(this.deviceId, config)),
      interfaces: soft(
        this.gatewayService.getNetworkInterfaces(this.deviceId, config)),
      languages: soft(this.gatewayService.getLanguages(this.deviceId, config)),
      monitor: soft(this.gatewayService.getMonitorValues(this.deviceId, config))
    }).subscribe(result => {
      this.about = result.about;
      this.settings = gatewaySettingRows(result.settings);
      this.withheldCount = Object.keys(result.settings ?? {}).filter(gatewaySettingWithheld).length;
      this.interfaces = result.interfaces ?? [];
      this.languages = result.languages ?? [];
      this.monitor = result.monitor ?? [];
      this.loading = false;
      // An entity-details tab only redraws when something flips its loading flag, and
      // ignoreLoading means nothing did.
      this.cd.markForCheck();
    });
  }

  /** The gateway's installed languages on one line — there are two or three, not a list worth paging. */
  get languageList(): string {
    return this.languages.map(language => language.value || language.key).join(', ');
  }
}
