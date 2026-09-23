// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
import { Component, EventEmitter, Input, Output } from '@angular/core';
import { buildRecipient, GATEWAY_RECIPIENT_TYPES, GatewayRecipient,
  recipientValue } from '@shared/models/inferrix-gateway-event.models';

/**
 * Who gets told: the recipient list of an event handler or of an alert list.
 *
 * Hand-written rather than schema-driven, and it has to be. The gateway declares
 * `RecipientEntryModel` as `{recipientType}` with a Jackson discriminator and publishes none of
 * its five subtypes, so a form built from that schema would render the discriminator and drop the
 * address — and because the array renderer writes its value back over the whole list, saving such
 * a form would erase every recipient on the handler.
 *
 * The five kinds are closed and core-owned, so a table of them is final rather than a guess that
 * ages.
 */
@Component({
  selector: 'tb-gateway-recipients',
  templateUrl: './gateway-recipients.component.html',
  styleUrls: [],
  standalone: false
})
export class GatewayRecipientsComponent {

  @Input() recipients: GatewayRecipient[] = [];
  @Input() label: string;
  @Input() disabled = false;
  @Output() recipientsChange = new EventEmitter<GatewayRecipient[]>();

  readonly types = GATEWAY_RECIPIENT_TYPES;
  readonly valueOf = recipientValue;

  add(): void {
    this.emit([...(this.recipients ?? []), buildRecipient('EMAIL_ADDRESS', '')]);
  }

  remove(index: number): void {
    const next = [...(this.recipients ?? [])];
    next.splice(index, 1);
    this.emit(next);
  }

  typeChanged(index: number, recipientType: string): void {
    // Rebuilt, not mutated: the value field differs per type, so assigning over the old one would
    // leave a stale `address` beside a new `number` and the gateway would deserialise by
    // discriminator with the wrong field still present.
    this.replace(index, buildRecipient(recipientType, ''));
  }

  valueChanged(index: number, value: string): void {
    const recipient = this.recipients[index];
    this.replace(index, buildRecipient(recipient?.recipientType, value));
  }

  private replace(index: number, recipient: GatewayRecipient): void {
    const next = [...(this.recipients ?? [])];
    next[index] = recipient;
    this.emit(next);
  }

  private emit(next: GatewayRecipient[]): void {
    this.recipients = next;
    this.recipientsChange.emit(next);
  }
}
