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

import { Component, OnDestroy, OnInit, ViewChild } from '@angular/core';
import { ChangeDetectorRef } from '@angular/core';
import { ActivatedRoute } from '@angular/router';
import { MatDialog } from '@angular/material/dialog';
import { Store, select } from '@ngrx/store';
import { TranslateService } from '@ngx-translate/core';
import { take } from 'rxjs/operators';
import moment from 'moment';
import $ from 'jquery';
import { from } from 'rxjs';

import { CalendarOptions, Calendar, EventClickArg, EventDropArg } from '@fullcalendar/core';
import { DateClickArg } from '@fullcalendar/interaction';
import { FullCalendarComponent } from '@fullcalendar/angular';
import interactionPlugin from '@fullcalendar/interaction';
import momentPlugin from '@fullcalendar/moment';
import dayGridPlugin from '@fullcalendar/daygrid';
import listPlugin from '@fullcalendar/list';
import timeGridPlugin from '@fullcalendar/timegrid';

import { AppState } from '@core/core.state';
import { selectAuthUser } from '@core/auth/auth.selectors';
import { Authority } from '@shared/models/authority.enum';
import { DialogService } from '@core/services/dialog.service';
import { EntityTableConfig } from '@home/models/entity/entities-table-config.models';
import { SchedulerEventService } from '@core/http/scheduler-event.service';
import { MatMenuTrigger } from '@angular/material/menu';
import {
  SchedulerEvent,
  SchedulerEventWithCustomerInfo,
  SchedulerRepeatType,
  schedulerEventConfigTypes
} from '@shared/models/scheduler-event.models';
import { SchedulerEventDialogComponent, SchedulerEventDialogData } from '@home/pages/scheduler/scheduler-event-dialog.component';

// Calendar view enum (PE `Nr`) — 9 views
export enum SchedulerCalendarView {
  month = 'month',
  week = 'week',
  day = 'day',
  listYear = 'listYear',
  listMonth = 'listMonth',
  listWeek = 'listWeek',
  listDay = 'listDay',
  agendaWeek = 'agendaWeek',
  agendaDay = 'agendaDay'
}

// view -> FullCalendar view name (PE `mE`) — note week/day use dayGrid; agenda* use timeGrid
const viewToFcView = new Map<SchedulerCalendarView, string>([
  [SchedulerCalendarView.month, 'dayGridMonth'],
  [SchedulerCalendarView.week, 'dayGridWeek'],
  [SchedulerCalendarView.day, 'dayGridDay'],
  [SchedulerCalendarView.listYear, 'listYear'],
  [SchedulerCalendarView.listMonth, 'listMonth'],
  [SchedulerCalendarView.listWeek, 'listWeek'],
  [SchedulerCalendarView.listDay, 'listDay'],
  [SchedulerCalendarView.agendaWeek, 'timeGridWeek'],
  [SchedulerCalendarView.agendaDay, 'timeGridDay']
]);

// view -> i18n key (PE `Ote`)
const viewToI18n = new Map<SchedulerCalendarView, string>([
  [SchedulerCalendarView.month, 'scheduler.month'],
  [SchedulerCalendarView.week, 'scheduler.week'],
  [SchedulerCalendarView.day, 'scheduler.day'],
  [SchedulerCalendarView.listYear, 'scheduler.list-year'],
  [SchedulerCalendarView.listMonth, 'scheduler.list-month'],
  [SchedulerCalendarView.listWeek, 'scheduler.list-week'],
  [SchedulerCalendarView.listDay, 'scheduler.list-day'],
  [SchedulerCalendarView.agendaWeek, 'scheduler.agenda-week'],
  [SchedulerCalendarView.agendaDay, 'scheduler.agenda-day']
]);

// TIMER timeUnit -> i18n key (PE `uT`)
const timeUnitToI18n: {[unit: string]: string} = {
  HOURS: 'scheduler.every-hour',
  MINUTES: 'scheduler.every-minute',
  SECONDS: 'scheduler.every-second'
};

// day-of-week i18n keys (PE `OG`)
const dayOfWeekI18n = [
  'scheduler.sunday', 'scheduler.monday', 'scheduler.tuesday', 'scheduler.wednesday',
  'scheduler.thursday', 'scheduler.friday', 'scheduler.saturday'
];

@Component({
  selector: 'tb-scheduler-events',
  templateUrl: './scheduler-events.component.html',
  styleUrls: ['./scheduler-events.component.scss'],
  standalone: false
})
export class SchedulerEventsComponent implements OnInit, OnDestroy {

  @ViewChild('calendarContainer', { static: false }) calendarContainer: any;
  @ViewChild('schedulerEventMenuTrigger', { static: true }) schedulerEventMenuTrigger: MatMenuTrigger;

  private calendarApi: Calendar;

  @ViewChild('calendar', { static: false })
  set calendarComponent(comp: FullCalendarComponent) {
    if (comp) {
      this.calendarApi = comp.getApi();
      this.calendarApi.render();
      this.cd.detectChanges();
    }
  }

  mode: 'list' | 'calendar' = 'list';
  entitiesTableConfig: EntityTableConfig<SchedulerEventWithCustomerInfo>;

  editEnabled = false;
  addEnabled = false;
  deleteEnabled = false;
  isCustomerUser = false;

  currentCalendarView = SchedulerCalendarView.month;
  schedulerCalendarViews = Object.values(SchedulerCalendarView);
  schedulerCalendarViewTranslations = viewToI18n;
  currentCalendarViewValue = viewToFcView.get(this.currentCalendarView);

  schedulerEventMenuPosition = { x: '0px', y: '0px' };
  schedulerContextMenuEvent: MouseEvent;

  schedulerEvents: SchedulerEventWithCustomerInfo[] = [];
  calendarOptions: CalendarOptions;

  constructor(private store: Store<AppState>,
              private translate: TranslateService,
              private schedulerEventService: SchedulerEventService,
              private dialog: MatDialog,
              private dialogService: DialogService,
              private route: ActivatedRoute,
              private cd: ChangeDetectorRef) {
  }

  ngOnInit(): void {
    this.entitiesTableConfig = this.route.snapshot.data.entitiesTableConfig;
    this.store.pipe(select(selectAuthUser), take(1)).subscribe(authUser => {
      const canManage = authUser.authority === Authority.TENANT_ADMIN
        || authUser.authority === Authority.CUSTOMER_USER;
      this.addEnabled = canManage;
      this.editEnabled = canManage;
      this.deleteEnabled = canManage;
      this.isCustomerUser = authUser.authority === Authority.CUSTOMER_USER;
    });
    this.setupCalendarOptions();
  }

  ngOnDestroy(): void {
    if (this.calendarApi) {
      this.calendarApi.destroy();
    }
  }

  private setupCalendarOptions(): void {
    this.calendarOptions = {
      plugins: [interactionPlugin, momentPlugin, dayGridPlugin, listPlugin, timeGridPlugin],
      height: '100%',
      fixedWeekCount: false,
      initialView: this.currentCalendarViewValue,
      allDaySlot: false,
      editable: this.editEnabled,
      headerToolbar: false,
      selectable: false,
      eventDisplay: 'block',
      eventDurationEditable: false,
      lazyFetching: false,
      events: this.eventSourceFunction.bind(this),
      eventClick: this.onEventClick.bind(this),
      dateClick: this.onDayClick.bind(this),
      eventDrop: this.onEventDrop.bind(this),
      eventDidMount: this.onEventDidMount.bind(this)
    };
  }

  // ===== event source: map backend-precomputed timestamps[] into FC events =====
  private eventSourceFunction(fetchInfo: { start: Date; end: Date },
                              success: (events: any[]) => void,
                              failure: (err: any) => void): void {
    this.schedulerEventService.getSchedulerEventsByTimeWindow(
      fetchInfo.start.getTime(), fetchInfo.end.getTime()
    ).subscribe({
      next: events => {
        this.schedulerEvents = events;
        const calEvents: any[] = [];
        if (this.schedulerEvents.length) {
          const startLocal = moment(fetchInfo.start);
          const endLocal = moment(fetchInfo.end);
          this.schedulerEvents.forEach(ev => {
            const evStart = moment(ev.schedule.startTime);
            if (endLocal.isSameOrAfter(evStart)) {
              if (ev.schedule.repeat) {
                if (ev.schedule.repeat.type === SchedulerRepeatType.TIMER && !ev.timestamps?.length) {
                  // sub-day TIMER with no precomputed times: one spanning event start..endsOn
                  const endsOn = moment(ev.schedule.repeat.endsOn);
                  calEvents.push(this.toCalendarEvent(ev, evStart, endsOn));
                } else if (ev.timestamps?.length) {
                  ev.timestamps.forEach(ts => calEvents.push(this.toCalendarEvent(ev, moment(ts))));
                }
              } else if (startLocal.isSameOrBefore(evStart)) {
                calEvents.push(this.toCalendarEvent(ev, evStart));
              }
            }
          });
        }
        success(calEvents);
      },
      error: err => {
        console.error('[Scheduler][calendar] event fetch failed', err);
        failure(err);
      }
    });
  }

  private toCalendarEvent(ev: SchedulerEventWithCustomerInfo, start: moment.Moment, end?: moment.Moment): any {
    let typeName = ev.type;
    const cfg = schedulerEventConfigTypes[ev.type];
    if (cfg) {
      typeName = this.translate.instant(cfg.name);
    }
    let repeatInterval: string;
    if (ev.schedule.repeat && ev.schedule.repeat.type === SchedulerRepeatType.TIMER) {
      repeatInterval = this.translate.instant(
        timeUnitToI18n[ev.schedule.repeat.timeUnit],
        { count: ev.schedule.repeat.repeatInterval });
    }
    return {
      id: ev.id.id,
      title: `${ev.name} - ${typeName}`,
      name: ev.name,
      type: typeName,
      info: this.getSchedulerEventInfo(ev.schedule, start),
      start: start.toDate(),
      end: end ? end.toDate() : null,
      extendedProps: {
        name: ev.name,
        type: typeName,
        info: this.getSchedulerEventInfo(ev.schedule, start),
        repeatInterval
      }
    };
  }

  // PE `Bte` — schedule description HTML for the tooltip
  private getSchedulerEventInfo(schedule: any, m: moment.Moment): string {
    let s = '';
    if (schedule.repeat) {
      const r = schedule.repeat;
      s += m.local().format('hh:mma') + '<br/>';
      s += this.translate.instant('scheduler.starting-from') + ' ' + m.local().format('MMM DD, YYYY') + ', ';
      if (r.type === SchedulerRepeatType.DAILY) {
        s += this.translate.instant('scheduler.daily') + ', ';
      } else if (r.type === SchedulerRepeatType.EVERY_N_DAYS) {
        s += this.translate.instant('scheduler.every-n-days-text', { days: r.days }) + ', ';
      } else if (r.type === SchedulerRepeatType.MONTHLY) {
        s += this.translate.instant('scheduler.monthly') + ', ';
      } else if (r.type === SchedulerRepeatType.EVERY_N_WEEKS) {
        s += this.translate.instant('scheduler.every-n-weeks-text', { weeks: r.weeks }) + ', ';
      } else if (r.type === SchedulerRepeatType.YEARLY) {
        s += this.translate.instant('scheduler.yearly') + ', ';
      } else if (r.type === SchedulerRepeatType.TIMER) {
        s += this.translate.instant(timeUnitToI18n[r.timeUnit], { count: r.repeatInterval }) + ', ';
      } else { // WEEKLY
        s += this.translate.instant('scheduler.weekly') + ' ' + this.translate.instant('scheduler.on') + ' ';
        (r.repeatOn || []).forEach((d: number) => { s += this.translate.instant(dayOfWeekI18n[d]) + ', '; });
      }
      s += this.translate.instant('scheduler.ending-on') + ' ' + moment(r.endsOn).local().format('MMM DD, YYYY');
    } else {
      s += m.local().format('hh:mma') + '<br/>' + m.local().format('MMM DD, YYYY');
    }
    return s;
  }

  // ===== interactions =====
  private onEventClick(info: EventClickArg): void {
    const ev = this.schedulerEvents.find(e => e.id.id === info.event.id);
    if (ev) {
      this.openSchedulerEventContextMenu(info.jsEvent as MouseEvent, ev);
    }
  }

  private openSchedulerEventContextMenu(e: MouseEvent, schedulerEvent: SchedulerEventWithCustomerInfo): void {
    e.preventDefault();
    e.stopPropagation();
    const off = $(this.calendarContainer.nativeElement).offset();
    this.schedulerContextMenuEvent = e;
    this.schedulerEventMenuPosition.x = (e.pageX - off.left) + 'px';
    this.schedulerEventMenuPosition.y = (e.pageY - off.top) + 'px';
    this.schedulerEventMenuTrigger.menuData = { schedulerEvent };
    this.schedulerEventMenuTrigger.openMenu();
  }

  onSchedulerEventContextMenuMouseLeave(): void {
    this.schedulerEventMenuTrigger.closeMenu();
  }

  private onDayClick(info: DateClickArg): void {
    if (!this.addEnabled) {
      return;
    }
    const skeleton = {
      schedule: { startTime: info.date.getTime() },
      configuration: { originatorId: null, msgType: null, msgBody: {}, metadata: {} }
    } as unknown as SchedulerEvent;
    this.openSchedulerEventDialog(info.jsEvent as MouseEvent, skeleton, false);
  }

  private onEventDrop(info: EventDropArg): void {
    const ev = this.schedulerEvents.find(e => e.id.id === info.event.id);
    if (ev) {
      this.moveEvent(ev, info);
    }
  }

  private moveEvent(event: SchedulerEventWithCustomerInfo, info: EventDropArg): void {
    const deltaMs = info.event.start.getTime() - info.oldEvent.start.getTime();
    this.schedulerEventService.getSchedulerEvent(event.id.id).subscribe({
      next: full => {
        full.schedule.startTime = full.schedule.startTime + deltaMs;
        this.schedulerEventService.saveSchedulerEvent(full).subscribe({
          next: () => this.reloadCalendar(),
          error: err => { console.error('[Scheduler][calendar] move save failed', err); info.revert(); }
        });
      },
      error: err => { console.error('[Scheduler][calendar] move fetch failed', err); info.revert(); }
    });
  }

  private onEventDidMount(arg: { el: HTMLElement; event: any }): void {
    const ext = arg.event.extendedProps;
    const $el = $(arg.el);
    from(import('tooltipster')).subscribe(() => {
      ($el as any).tooltipster({
        theme: 'tooltipster-shadow',
        delay: 100,
        trigger: 'hover',
        triggerOpen: { click: false, tap: false },
        triggerClose: { click: true, tap: true, scroll: true },
        side: 'top',
        trackOrigin: true
      });
      ($el as any).tooltipster('instance').content($(
        `<div class="tb-scheduler-tooltip-title">${ext.name}</div>` +
        `<div class="tb-scheduler-tooltip-content">` +
          `<b>${this.translate.instant('scheduler.event-type')}:&nbsp;</b>${ext.type}` +
        `</div>` +
        `<div class="tb-scheduler-tooltip-content">${ext.info}</div>`
      ));
    });
  }

  // ===== toolbar / view navigation =====
  changeCalendarView(): void {
    this.currentCalendarViewValue = viewToFcView.get(this.currentCalendarView);
    if (this.calendarApi) {
      this.calendarApi.changeView(this.currentCalendarViewValue);
    }
  }

  calendarViewTitle(): string {
    return this.calendarApi?.view.title ?? '';
  }

  gotoCalendarToday(): void {
    this.calendarApi?.today();
  }

  isCalendarToday(): boolean {
    return this.isDateInView(Date.now());
  }

  gotoCalendarPrev(): void {
    this.calendarApi?.prev();
  }

  gotoCalendarNext(): void {
    this.calendarApi?.next();
  }

  private isDateInView(marker: number): boolean {
    if (!this.calendarApi) {
      return false;
    }
    const v = this.calendarApi.view;
    return marker >= v.currentStart.valueOf() && marker < v.currentEnd.valueOf();
  }

  private reloadCalendar(): void {
    if (this.calendarApi) {
      this.calendarApi.refetchEvents();
    }
  }

  // ===== context-menu CRUD actions =====
  editSchedulerEvent(e: MouseEvent, node: SchedulerEventWithCustomerInfo): void {
    e?.stopPropagation();
    this.schedulerEventService.getSchedulerEvent(node.id.id)
      .subscribe(full => this.openSchedulerEventDialog(e, full, false));
  }

  viewSchedulerEvent(e: MouseEvent, node: SchedulerEventWithCustomerInfo): void {
    e?.stopPropagation();
    this.schedulerEventService.getSchedulerEvent(node.id.id)
      .subscribe(full => this.openSchedulerEventDialog(e, full, true));
  }

  deleteSchedulerEvent(e: MouseEvent, node: SchedulerEventWithCustomerInfo): void {
    e?.stopPropagation();
    const title = this.translate.instant('scheduler.delete-scheduler-event-title', { schedulerEventName: node.name });
    const text = this.translate.instant('scheduler.delete-scheduler-event-text');
    this.dialogService.confirm(title, text,
      this.translate.instant('action.no'), this.translate.instant('action.yes'))
      .subscribe(ok => {
        if (ok) {
          this.schedulerEventService.deleteSchedulerEvent(node.id.id).subscribe(() => this.reloadCalendar());
        }
      });
  }

  private openSchedulerEventDialog(e: MouseEvent, ev: SchedulerEvent | null, readonly: boolean): void {
    e?.stopPropagation();
    let isAdd = false;
    if (!ev || !ev.id) {
      isAdd = true;
    }
    this.dialog.open<SchedulerEventDialogComponent, SchedulerEventDialogData, SchedulerEvent>(
      SchedulerEventDialogComponent, {
        disableClose: true,
        panelClass: ['tb-dialog', 'tb-fullscreen-dialog'],
        data: { schedulerEvent: ev, isAdd, isCustomerUser: this.isCustomerUser, readonly }
      }).afterClosed().subscribe(res => { if (res) { this.reloadCalendar(); } });
  }
}
