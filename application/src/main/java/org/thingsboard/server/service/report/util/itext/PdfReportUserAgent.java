/**
 * Copyright © 2016-2026 The Inferrix Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.thingsboard.server.service.report.util.itext;

import com.lowagie.text.BadElementException;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.PdfReader;
import java.io.ByteArrayInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.logging.Level;
import org.apache.commons.lang3.StringUtils;
import org.thingsboard.server.service.report.util.ImageUtils;
import org.thingsboard.server.service.report.util.ThymeleafUtil;
import org.xhtmlrenderer.extend.FSImage;
import org.xhtmlrenderer.pdf.ITextFSImage;
import org.xhtmlrenderer.pdf.ITextOutputDevice;
import org.xhtmlrenderer.pdf.ITextUserAgent;
import org.xhtmlrenderer.pdf.PDFAsImage;
import org.xhtmlrenderer.resource.ImageResource;
import org.xhtmlrenderer.util.ContentTypeDetectingInputStreamWrapper;
import org.xhtmlrenderer.util.IOUtil;
import org.xhtmlrenderer.util.ImageUtil;
import org.xhtmlrenderer.util.XRLog;

public class PdfReportUserAgent
extends ITextUserAgent {
    private final PdfReportImageResolver _imageResolver;
    private final ITextOutputDevice _outputDevice;
    private final int dotsPerPixel;
    private final int usablePageWidthPx;

    public PdfReportUserAgent(PdfReportImageResolver imageResolver, ITextOutputDevice outputDevice, int dotsPerPixel, int usablePageWidthPx) {
        super(outputDevice, dotsPerPixel);
        this._imageResolver = imageResolver;
        this._outputDevice = outputDevice;
        this.dotsPerPixel = dotsPerPixel;
        this.usablePageWidthPx = usablePageWidthPx;
    }

    @Override
    public String resolveURI(String uri) {
        return uri;
    }

    @Override
    public ImageResource getImageResource(String uriStr) {
        ImageResource resource;
        String unresolvedUri = uriStr = StringUtils.removeStart(uriStr, "tb-image;");
        if (!(ImageUtil.isEmbeddedBase64Image(uriStr) || ImageUtils.isTbImage(uriStr) || ImageUtils.isEmptyImage(uriStr))) {
            uriStr = this.resolveURI(uriStr);
        }
        if ((resource = this._imageCache.get(unresolvedUri)) == null) {
            resource = this.loadImageResource(uriStr);
            this._imageCache.put(unresolvedUri, resource);
        }
        if (resource != null) {
            FSImage image = resource.getImage();
            return new ImageResource(resource.getImageUri(), image);
        }
        return new ImageResource(uriStr, null);
    }

    @Override
    protected InputStream resolveAndOpenStream(String uri) {
        InputStream is = null;
        URL url = PdfReportUserAgent.class.getResource(uri);
        if (url == null) {
            if (uri.startsWith("/assets/")) {
                url = PdfReportUserAgent.class.getResource("/public" + uri);
            }
            if (url == null) {
                // R2a policy: resolve ONLY bundled classpath assets here. Do NOT fetch arbitrary URLs
                // (http/https/file/ftp/...) via super.resolveAndOpenStream -> new URL(uri).openStream():
                // that would be an SSRF + local-file-read sink with no scheme/host allowlist, no timeout
                // and no byte cap. An unresolved URI returns null, so the caller substitutes the error
                // image (same outcome as any other unresolved image today).
                // If remote images are ever required, this is where an explicit https-only allowlist with
                // connect/read timeouts, a max-byte cap and a private/link-local IP-range block must go.
                XRLog.load(Level.FINE, "Refusing to resolve non-bundled resource URI (remote fetch is disabled): " + uri);
                return null;
            }
        }
        try {
            is = url.openStream();
        }
        catch (MalformedURLException e) {
            XRLog.exception("bad URL given: " + uri, e);
        }
        catch (FileNotFoundException e) {
            XRLog.exception("item at URI " + uri + " not found");
        }
        catch (IOException e) {
            XRLog.exception("IO problem for " + uri, e);
        }
        return is;
    }

    private ImageResource loadImageResource(String uriStr) {
        String originalUri = uriStr;
        uriStr = ImageUtil.isEmbeddedBase64Image(uriStr) ? "Base64 Data" : uriStr;
        try (InputStream is = this.resolveImageStream(originalUri)) {
            if (is == null) {
                return this.errorLoadImageFromUriResource(uriStr, null);
            }
            try (ContentTypeDetectingInputStreamWrapper cis = ContentTypeDetectingInputStreamWrapper.detectContentType(is)) {
                if (cis.isPdf()) {
                    URI uri = new URI(uriStr);
                    PdfReader reader = this._outputDevice.getReader(uri);
                    Rectangle rect = reader.getPageSizeWithRotation(1);
                    float initialWidth = rect.getWidth() * this._outputDevice.getDotsPerPoint();
                    float initialHeight = rect.getHeight() * this._outputDevice.getDotsPerPoint();
                    PDFAsImage image = new PDFAsImage(uri, initialWidth, initialHeight);
                    return new ImageResource(uriStr, image);
                }
                if (cis.isSvg()) {
                    PdfSvgImage image = this.readSvg(uriStr, cis);
                    return new ImageResource(uriStr, image);
                }
                byte[] imageBytes = IOUtil.readBytes(cis);
                ITextFSImage itextImage = new ITextFSImage(imageBytes, ImageUtils.getOriginalImageSize(imageBytes), uriStr);
                return new ImageResource(uriStr, itextImage);
            }
        }
        catch (BadElementException | IOException | URISyntaxException e) {
            XRLog.exception("Can't read image file; unexpected problem for URI '" + uriStr + "'", e);
            return this.errorImageResource(uriStr, "Can't read image file", null, e);
        }
        catch (PdfReportImageException e) {
            return this.errorImageResource(e);
        }
    }

    private InputStream resolveImageStream(String uriStr) throws PdfReportImageException {
        if (ImageUtil.isEmbeddedBase64Image(uriStr)) {
            try {
                byte[] buffer = ImageUtil.getEmbeddedBase64Image(uriStr);
                return new ByteArrayInputStream(buffer);
            }
            catch (BadElementException e) {
                XRLog.exception("Can't read XHTML embedded image.", e);
                throw this.errorLoadImageFromBase64Exception(e);
            }
        }
        if (ImageUtils.isTbImage(uriStr)) {
            try {
                byte[] imageData = null;
                if (ImageUtils.isInternalTbImage(uriStr)) {
                    imageData = this.loadInternalTbImage(uriStr);
                } else if (ImageUtils.isPublicTbImage(uriStr)) {
                    imageData = this.loadPublicTbImage(uriStr);
                }
                if (imageData != null) {
                    return new ByteArrayInputStream(imageData);
                }
            }
            catch (Exception e) {
                XRLog.exception("Can't read TB image.", e);
                throw this.errorLoadTbImageException(uriStr, e);
            }
            throw this.errorLoadTbImageException(uriStr, null);
        }
        if (ImageUtils.isEmptyImage(uriStr)) {
            throw this.errorEmptyImageException(uriStr);
        }
        return this.resolveAndOpenStream(uriStr);
    }

    private PdfSvgImage readSvg(String uri, InputStream in) throws IOException, PdfReportImageException {
        byte[] svgBytes = IOUtil.readBytes(in);
        PdfSvgDocument svgDocument = PdfSvgDocument.fromSvgBytes(svgBytes);
        if (svgDocument == null) {
            throw new PdfReportImageException(uri, "Could not load image from SVG.", null, null);
        }
        return new PdfSvgImage(svgDocument, this.dotsPerPixel, this.usablePageWidthPx);
    }

    private PdfReportImageException errorLoadImageFromBase64Exception(Exception exception) {
        return new PdfReportImageException(null, "Failed to load image from base64 data", "Base64 Data", exception);
    }

    private PdfReportImageException errorLoadTbImageException(String uri, Exception exception) {
        return new PdfReportImageException(uri, "Can't read TB image.", null, exception);
    }

    private PdfReportImageException errorEmptyImageException(String uri) {
        return new PdfReportImageException(uri, "Image uri is empty.", "URI is empty", null);
    }

    private ImageResource errorLoadImageFromUriResource(String uri, Exception exception) {
        return this.errorImageResource(uri, "Failed to load image.", null, exception);
    }

    private ImageResource errorImageResource(PdfReportImageException exception) {
        return this.errorImageResource(exception.uri, exception.getMessage(), exception.uriString, exception.getCause());
    }

    private ImageResource errorImageResource(String uri, String errorMessage, String uriString, Throwable exception) {
        HashMap<String, Object> errorImageVariables = new HashMap<>();
        errorImageVariables.put("errorMessage", errorMessage);
        if (uriString == null) {
            uriString = uri;
        }
        errorImageVariables.put("uri", uriString);
        if (exception != null) {
            errorImageVariables.put("exception", exception.getMessage());
            errorImageVariables.put("uriPos", 11);
        } else {
            errorImageVariables.put("uriPos", 16);
        }
        String errorImageSvg = ThymeleafUtil.renderFromSvgTemplate("svg/error-image", errorImageVariables);
        PdfSvgDocument svgDocument = PdfSvgDocument.fromSvgString(errorImageSvg);
        PdfSvgImage image = new PdfSvgImage(svgDocument, this.dotsPerPixel, this.usablePageWidthPx);
        return new ImageResource(uri, image);
    }

    private byte[] loadInternalTbImage(String uri) throws Exception {
        String[] parts;
        String imageType = null;
        if (uri.startsWith("/api/images/tenant/")) {
            imageType = "tenant";
        } else if (uri.startsWith("/api/images/system/")) {
            imageType = "system";
        }
        if (imageType != null && (parts = uri.split("/")).length >= 5) {
            String key = parts[4];
            key = URLDecoder.decode(key, StandardCharsets.UTF_8);
            return this._imageResolver.downloadImage(imageType, key);
        }
        return null;
    }

    private byte[] loadPublicTbImage(String uri) throws Exception {
        String[] parts = uri.split("/");
        if (parts.length >= 5) {
            String publicKey = parts[4];
            return this._imageResolver.downloadPublicImage(publicKey);
        }
        return null;
    }

    static class PdfReportImageException
    extends Exception {
        private final String uri;
        private final String uriString;

        PdfReportImageException(String uri, String errorMessage, String uriString, Exception exception) {
            super(errorMessage, exception);
            this.uri = uri;
            this.uriString = uriString;
        }
    }
}
