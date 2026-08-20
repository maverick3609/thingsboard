///
/// Copyright © 2016-2026 The Thingsboard Authors
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

import { Component, forwardRef } from '@angular/core';
import { ControlValueAccessor, NG_VALUE_ACCESSOR } from '@angular/forms';
import { MatDialog } from '@angular/material/dialog';
import { ImageService } from '@core/http/image.service';
import { ImageResourceInfo, ResourceSubType } from '@shared/models/resource.models';
import {
  ImageGalleryDialogComponent,
  ImageGalleryDialogData
} from '@shared/components/image/image-gallery-dialog.component';

@Component({
  standalone: false,
  selector: 'tb-wl-image-input',
  templateUrl: './image-input.component.html',
  styleUrls: ['./image-input.component.scss'],
  providers: [{
    provide: NG_VALUE_ACCESSOR,
    useExisting: forwardRef(() => ImageInputComponent),
    multi: true
  }]
})
export class ImageInputComponent implements ControlValueAccessor {
  // The stored value is always an anonymously-fetchable image URL: a public-image
  // link (/api/images/public/{publicResourceKey}) produced by the gallery pick
  // below, or a legacy base64 data URL / external URL loaded from previously-saved
  // params. It is bound raw into an <img [src]> preview.
  value: string = null;
  disabled = false;
  makingPublic = false;
  imageName: string = null;
  // "1024x768 | 9.4 KB" - the byte size is only known for an image picked in this
  // session; a reloaded value carries the URL alone, so only the dimensions show
  imageMeta: string = null;

  private sizeBytes: number = null;
  private dimensions: string = null;

  private pickedName: string = null;
  private pickedSize: number = null;

  private onChange: (val: string) => void = () => {};
  private onTouched: () => void = () => {};

  constructor(private imageService: ImageService,
              private dialog: MatDialog) {}

  writeValue(val: string): void {
    this.value = val ?? null;
    this.imageName = this.value ? this.fileNameOf(this.value) : null;
    this.sizeBytes = null;
    this.dimensions = null;
    this.imageMeta = null;
  }

  onImageLoad(event: Event): void {
    const img = event.target as HTMLImageElement;
    this.dimensions = `${img.naturalWidth}x${img.naturalHeight}`;
    this.updateMeta();
  }

  registerOnChange(fn: (val: string) => void): void {
    this.onChange = fn;
  }

  registerOnTouched(fn: () => void): void {
    this.onTouched = fn;
  }

  setDisabledState(isDisabled: boolean): void {
    this.disabled = isDisabled;
  }

  browseGallery(event: Event): void {
    if (event) {
      event.stopPropagation();
    }
    this.dialog.open<ImageGalleryDialogComponent, ImageGalleryDialogData, ImageResourceInfo>(
      ImageGalleryDialogComponent, {
        autoFocus: false,
        disableClose: false,
        panelClass: ['tb-dialog', 'tb-fullscreen-dialog'],
        data: { imageSubType: ResourceSubType.IMAGE }
      }).afterClosed().subscribe((image) => {
        if (image) {
          this.pickedName = image.title;
          this.pickedSize = image.descriptor?.size;
          this.storePublicLink(image);
        }
      });
  }

  clear(): void {
    this.writeValue(null);
    this.onChange(null);
    this.onTouched();
  }

  // All three white-labeling image fields (login logo, general logo, favicon) render
  // on the pre-auth /login page — the logo via <tb-logo> ( raw <img [src]> ) and the
  // favicon via WhiteLabelingRuntimeService#applyFavicon ( raw <link href> ) — where
  // the browser holds no auth token. The platform GalleryImageInputComponent's value
  // accessor would emit the gated private reference tb-image;/api/images/{tenant|
  // system}/{key} (ImageController#downloadImage requires an authenticated principal),
  // which a logged-out browser cannot fetch. Instead we mark the picked image public
  // and persist its anonymous publicLink (/api/images/public/{publicResourceKey},
  // allow-listed in ThingsboardSecurityConfiguration and served by
  // ImageController#downloadPublicImage without @PreAuthorize) — the same mechanism
  // ThingsBoard / PE use for branding assets. Only the single image the admin
  // explicitly picks for this field is made public; nothing else in the gallery is
  // touched.
  private storePublicLink(image: ImageResourceInfo): void {
    if (image.public && image.publicLink) {
      this.setValue(image.publicLink);
    } else {
      // Making an image public requires SYS_ADMIN / TENANT_ADMIN. A CUSTOMER_USER
      // editing white labeling can therefore only reuse images that are already
      // public; on 403 the platform surfaces the error and the field is left as-is.
      this.makingPublic = true;
      this.imageService.updateImagePublicStatus(image, true).subscribe({
        next: (updated) => {
          this.makingPublic = false;
          this.setValue(updated.publicLink);
        },
        error: () => {
          this.makingPublic = false;
        }
      });
    }
  }

  private setValue(url: string): void {
    this.writeValue(url);
    this.imageName = this.pickedName || this.imageName;
    this.sizeBytes = this.pickedSize ?? null;
    this.updateMeta();
    this.onChange(url);
    this.onTouched();
  }

  private fileNameOf(url: string): string {
    const name = url.split('?')[0].split('/').pop();
    try {
      // a stored value can be any URL (or a legacy base64 data URL); a stray '%'
      // would make decodeURIComponent throw and blank the whole field
      return decodeURIComponent(name);
    } catch {
      return name;
    }
  }

  private updateMeta(): void {
    const parts = [this.dimensions, this.sizeBytes ? `${(this.sizeBytes / 1024).toFixed(1)} KB` : null];
    this.imageMeta = parts.filter(part => !!part).join(' | ');
  }
}
