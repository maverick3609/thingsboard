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

import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NO_ERRORS_SCHEMA } from '@angular/core';
import { MatDialog } from '@angular/material/dialog';
import { of, throwError } from 'rxjs';
import { ImageService } from '@core/http/image.service';
import { ImageResourceInfo } from '@shared/models/resource.models';
import { ImageInputComponent } from './image-input.component';

// Covers the pre-auth-safe value contract of the white-labeling gallery picker: it must
// persist an anonymously-fetchable public-image link (/api/images/public/{key}), never the
// gated tb-image;/api/images/{tenant|system}/{key} reference, so the login/general logos and
// favicon render on the logged-out /login page.
describe('ImageInputComponent (white-labeling gallery picker)', () => {
  let component: ImageInputComponent;
  let fixture: ComponentFixture<ImageInputComponent>;
  let imageService: jasmine.SpyObj<ImageService>;
  let dialog: jasmine.SpyObj<MatDialog>;
  let emitted: string[];

  const publicImage = {
    resourceKey: 'logo.png',
    public: true,
    publicResourceKey: 'abc123',
    publicLink: '/api/images/public/abc123'
  } as ImageResourceInfo;

  const privateImage = {
    resourceKey: 'logo2.png',
    tenantId: { entityType: 'TENANT', id: '11111111-1111-1111-1111-111111111111' },
    public: false,
    publicResourceKey: 'def456',
    link: '/api/images/tenant/logo2.png'
  } as unknown as ImageResourceInfo;

  const madePublic = {
    ...privateImage,
    public: true,
    publicLink: '/api/images/public/def456'
  } as ImageResourceInfo;

  const openReturns = (image: ImageResourceInfo | null): void => {
    dialog.open.and.returnValue({ afterClosed: () => of(image) } as any);
  };

  beforeEach(async () => {
    imageService = jasmine.createSpyObj('ImageService', ['updateImagePublicStatus']);
    dialog = jasmine.createSpyObj('MatDialog', ['open']);
    await TestBed.configureTestingModule({
      declarations: [ImageInputComponent],
      providers: [
        { provide: ImageService, useValue: imageService },
        { provide: MatDialog, useValue: dialog }
      ],
      schemas: [NO_ERRORS_SCHEMA]
    }).compileComponents();
    fixture = TestBed.createComponent(ImageInputComponent);
    component = fixture.componentInstance;
    emitted = [];
    component.registerOnChange((v: string) => emitted.push(v));
    fixture.detectChanges();
  });

  it('writeValue stores the value for the preview', () => {
    component.writeValue('/api/images/public/abc123');
    expect(component.value).toBe('/api/images/public/abc123');
  });

  it('clear resets the value and emits null', () => {
    component.writeValue('data:image/png;base64,AAAA');
    component.clear();
    expect(component.value).toBeNull();
    expect(emitted).toEqual([null]);
  });

  it('picking an already-public image stores its publicLink without a make-public call', () => {
    openReturns(publicImage);
    component.browseGallery(new Event('click'));
    expect(imageService.updateImagePublicStatus).not.toHaveBeenCalled();
    expect(component.value).toBe('/api/images/public/abc123');
    expect(emitted).toEqual(['/api/images/public/abc123']);
  });

  it('picking a private image makes it public and stores the returned publicLink', () => {
    imageService.updateImagePublicStatus.and.returnValue(of(madePublic));
    openReturns(privateImage);
    component.browseGallery(new Event('click'));
    expect(imageService.updateImagePublicStatus).toHaveBeenCalledWith(privateImage, true);
    expect(component.value).toBe('/api/images/public/def456');
    expect(emitted).toEqual(['/api/images/public/def456']);
  });

  it('never emits the gated private tb-image reference', () => {
    imageService.updateImagePublicStatus.and.returnValue(of(madePublic));
    openReturns(privateImage);
    component.browseGallery(new Event('click'));
    emitted.forEach(v => {
      expect(v).not.toContain('/api/images/tenant/');
      expect(v).not.toContain('tb-image;');
    });
  });

  it('cancelling the gallery dialog emits nothing and marks no image public', () => {
    openReturns(null);
    component.browseGallery(new Event('click'));
    expect(emitted).toEqual([]);
    expect(imageService.updateImagePublicStatus).not.toHaveBeenCalled();
  });

  it('leaves the value unchanged and resets the loading flag when make-public fails (e.g. CUSTOMER_USER 403)', () => {
    component.writeValue('/api/images/public/existing');
    emitted = [];
    imageService.updateImagePublicStatus.and.returnValue(throwError(() => ({ status: 403 })));
    openReturns(privateImage);
    component.browseGallery(new Event('click'));
    expect(imageService.updateImagePublicStatus).toHaveBeenCalledWith(privateImage, true);
    expect(component.value).toBe('/api/images/public/existing');
    expect(emitted).toEqual([]);
    expect(component.makingPublic).toBeFalse();
  });

  it('writeValue surfaces an existing base64 config for display without emitting (round-trip lock)', () => {
    const base64 = 'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAAAAAA6fptVAAAACklEQVR4nGNgAAAAAgAB4iG8MwAAAABJRU5ErkJggg==';
    component.writeValue(base64);
    expect(component.value).toBe(base64);
    expect(emitted).toEqual([]);
  });
});
