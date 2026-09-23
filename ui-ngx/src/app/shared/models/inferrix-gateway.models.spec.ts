// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
import { Authority } from '@shared/models/authority.enum';
import {
  gatewayReachabilityTone,
  gatewayTableAccess,
  GATEWAY_COLUMN_VALUES,
  INFERRIX_GATEWAY_PROFILE,
  isAdoptable
} from '@shared/models/inferrix-gateway.models';

describe('inferrix-gateway.models', () => {

  describe('escaping', () => {

    it('escapes every column a gateway reports about itself', () => {
      // ThingsBoard renders table cells through bypassSecurityTrustHtml, and the address in these
      // columns is published by the device over MQTT -- it is not something an operator typed.
      // A gateway that reports its address as an <img onerror> would otherwise run script in a
      // tenant admin's browser.
      const hostile = '<img src=x onerror="alert(1)">';
      const row: any = {
        name: hostile, reportedAddress: hostile, label: hostile,
        stackVersion: hostile, type: hostile
      };
      for (const [column, value] of Object.entries(GATEWAY_COLUMN_VALUES)) {
        const rendered = (value as (r: any) => string)(row);
        // What makes it inert is that no markup-significant character survives -- the literal
        // text "onerror=" is harmless once it cannot sit inside a tag. Asserting on the payload
        // text instead would pass for an escaper that only stripped the word.
        expect(rendered).withContext(column).not.toMatch(/[<>"']/);
        expect(rendered).withContext(column).toContain('&lt;img');
      }
    });

    it('renders an absent value as empty rather than the string undefined', () => {
      for (const [column, value] of Object.entries(GATEWAY_COLUMN_VALUES)) {
        expect((value as (r: any) => string)({})).withContext(column).toBe('');
      }
    });
  });

  describe('who sees what', () => {

    it('scopes a customer user to their own customer', () => {
      // A customer user has no access to the tenant-wide device listing, so the filter carries
      // their customer id and ThingsBoard's own query object picks the right URL. Extracted from
      // the resolver purely so this branch can be tested at all -- a resolver spec drags in the
      // DialogService circular import that crashes the karma bundle.
      const access = gatewayTableAccess(Authority.CUSTOMER_USER, 'customer-uuid');

      expect(access.filter.type).toBe(INFERRIX_GATEWAY_PROFILE);
      expect(access.filter.customerId?.id).toBe('customer-uuid');
      expect(access.readonly).toBeTrue();
      expect(access.canAdopt).toBeFalse();
    });

    it('lets a tenant admin see every gateway and adopt', () => {
      const access = gatewayTableAccess(Authority.TENANT_ADMIN, 'ignored');

      expect(access.filter.type).toBe(INFERRIX_GATEWAY_PROFILE);
      expect(access.filter.customerId).toBeUndefined();
      expect(access.readonly).toBeFalse();
      expect(access.canAdopt).toBeTrue();
    });

    it('treats every other authority as read-only', () => {
      // A sys admin has no tenant context here, so there is nothing for them to adopt into.
      // Defaulting to read-only means a new authority cannot silently gain write.
      for (const authority of [Authority.SYS_ADMIN, Authority.REFRESH_TOKEN, undefined]) {
        const access = gatewayTableAccess(authority as Authority, 'x');
        expect(access.readonly).withContext(String(authority)).toBeTrue();
        expect(access.canAdopt).withContext(String(authority)).toBeFalse();
      }
    });
  });

  describe('reachability', () => {

    it('does not paint an under-privileged token as a broken gateway', () => {
      // FORBIDDEN is the expected steady state until stack ask A10 lands: the service account is
      // deliberately not an administrator, so the platform-link routes answer 403 while
      // everything else works. Red here would have operators chasing a healthy device.
      expect(gatewayReachabilityTone('FORBIDDEN')).toBe('warn');
      expect(gatewayReachabilityTone('NO_ADDRESS')).toBe('warn');
      expect(gatewayReachabilityTone('NO_CREDENTIAL')).toBe('warn');
    });

    it('paints a changed certificate as an error, not a warning', () => {
      // The device is not the device that was adopted. That is the one reachability failure an
      // operator must not scroll past.
      expect(gatewayReachabilityTone('CERTIFICATE_CHANGED')).toBe('error');
      expect(gatewayReachabilityTone('UNAUTHORIZED')).toBe('error');
      expect(gatewayReachabilityTone('UNREACHABLE')).toBe('error');
      expect(gatewayReachabilityTone('NO_SEALING_KEY')).toBe('error');
    });

    it('is green only when the gateway actually answered', () => {
      expect(gatewayReachabilityTone('OK')).toBe('ok');
      // A reason this build has never heard of must not render as healthy. The backend is free to
      // add one, and an unknown failure is still a failure.
      expect(gatewayReachabilityTone('SOMETHING_NEW' as any)).toBe('error');
      expect(gatewayReachabilityTone(undefined as any)).toBe('error');
    });
  });

  describe('the pending list', () => {

    it('offers a one-click adopt only when the gateway said where it is', () => {
      // Without a reported address the operator has to type one, so the row opens the form rather
      // than pretending it can adopt straight away.
      expect(isAdoptable({deviceId: {id: 'a'}, name: 'gw', reportedAddress: '10.0.0.5'} as any)).toBeTrue();
      expect(isAdoptable({deviceId: {id: 'a'}, name: 'gw'} as any)).toBeFalse();
      expect(isAdoptable({deviceId: {id: 'a'}, name: 'gw', reportedAddress: '  '} as any)).toBeFalse();
    });
  });
});
