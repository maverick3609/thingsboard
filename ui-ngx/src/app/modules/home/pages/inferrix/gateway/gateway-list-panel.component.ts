// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
import { ChangeDetectorRef, Directive } from '@angular/core';
import { PageEvent } from '@angular/material/paginator';
import { Sort } from '@angular/material/sort';
import { Observable, Subject } from 'rxjs';
import { debounceTime, distinctUntilChanged, takeUntil } from 'rxjs/operators';
import { GatewayPanelComponent } from '@home/pages/inferrix/gateway/gateway-panel.component';
import { GatewayListQuery, GatewayPage } from '@shared/models/inferrix-gateway-data.models';

/**
 * A gateway list that pages on the device.
 *
 * Every list in this feature is the same shape and the same hazard: a gateway in a building holds
 * hundreds of data sources, thousands of points and a rolling event log, it materialises a page
 * before answering, and the answer crosses a LAN into a shared platform node. So paging, sorting
 * and searching all happen on the device — and none of them as a query string, because the gateway
 * reads a raw query string as an RQL expression on every verb. They travel as typed parameters
 * that the platform turns into RQL itself.
 *
 * `total` counts every row matching the filter while ignoring limit and offset, so a short page is
 * the end of the filtered set and never the end of the data.
 */
@Directive()
export abstract class GatewayListPanelComponent<T> extends GatewayPanelComponent {

  rows: T[] = [];
  readonly pageSizeOptions = [10, 20, 50, 100];
  pageSize = 10;
  pageIndex = 0;
  total = 0;
  loading = false;
  error: string;
  textSearch = '';

  sortProperty: string;
  sortDirection: 'ASC' | 'DESC';

  private readonly searchChanged = new Subject<string>();

  protected constructor(protected cd: ChangeDetectorRef) {
    super();
    const initial = this.defaultSort();
    this.sortProperty = initial.property;
    this.sortDirection = initial.direction;
    this.searchChanged.pipe(
      debounceTime(400), distinctUntilChanged(), takeUntil(this.destroy$)
    ).subscribe(text => {
      this.textSearch = text;
      this.pageIndex = 0;
      this.reload();
    });
  }

  /** One page from the gateway. Return null to show an empty list without calling the device. */
  protected abstract fetch(query: GatewayListQuery): Observable<GatewayPage<T>>;

  /**
   * What an unsorted column falls back to. Never "no sort": see {@link sortChanged}.
   *
   * A method rather than a field because a subclass field initializer runs *after* this base
   * constructor body, so an overridden field would still be the base's value when the initial sort
   * is read — the list would open sorted by a column the subclass never chose.
   */
  protected defaultSort(): {property: string; direction: 'ASC' | 'DESC'} {
    return {property: 'name', direction: 'ASC'};
  }

  reload(): void {
    const request = this.fetch({
      pageSize: this.pageSize,
      page: this.pageIndex,
      textSearch: this.textSearch || undefined,
      sortProperty: this.sortProperty,
      sortOrder: this.sortDirection
    });
    if (!request) {
      this.rows = [];
      this.total = 0;
      this.loading = false;
      this.cd.markForCheck();
      return;
    }
    this.loading = true;
    this.error = null;
    request.subscribe({
      next: page => {
        this.rows = page?.items ?? [];
        this.total = page?.total ?? this.rows.length;
        this.loading = false;
        // An entity-details tab redraws only when something flips its loading flag, and every call
        // here is made with ignoreLoading so nothing did.
        this.cd.markForCheck();
      },
      error: error => {
        this.error = this.messageOf(error);
        this.rows = [];
        this.total = 0;
        this.loading = false;
        this.cd.markForCheck();
      }
    });
  }

  search(text: string): void {
    this.searchChanged.next(text ?? '');
  }

  pageChanged(event: PageEvent): void {
    this.pageIndex = event.pageIndex;
    this.pageSize = event.pageSize;
    this.reload();
  }

  sortChanged(sort: Sort): void {
    // An unsorted column means "back to the default", not "send no sort". The gateway's own order
    // is insertion order, and a list that silently reshuffled between pages would show some rows
    // twice and hide others.
    const fallback = this.defaultSort();
    this.sortProperty = sort.direction ? sort.active : fallback.property;
    this.sortDirection = sort.direction
      ? (sort.direction === 'desc' ? 'DESC' : 'ASC')
      : fallback.direction;
    this.pageIndex = 0;
    this.reload();
  }

  protected fail(error: any): void {
    this.error = this.messageOf(error);
    this.cd.markForCheck();
  }
}
