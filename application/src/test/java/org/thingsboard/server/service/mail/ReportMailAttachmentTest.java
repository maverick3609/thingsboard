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
package org.thingsboard.server.service.mail;

import com.google.common.util.concurrent.Futures;
import freemarker.template.Configuration;
import jakarta.mail.BodyPart;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.MessageSource;
import org.springframework.mail.javamail.JavaMailSender;
import org.thingsboard.rule.engine.api.TbEmail;
import org.thingsboard.server.cache.limits.RateLimitService;
import org.thingsboard.server.common.data.ApiUsageState;
import org.thingsboard.server.common.data.id.ReportId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.report.Report;
import org.thingsboard.server.common.data.report.TbReportFormat;
import org.thingsboard.server.common.stats.TbApiUsageReportClient;
import org.thingsboard.server.dao.report.ReportService;
import org.thingsboard.server.dao.settings.AdminSettingsService;
import org.thingsboard.server.service.apiusage.TbApiUsageStateService;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Task 17: the delivery half of the reporting pipeline. {@link
 * org.thingsboard.server.service.notification.channels.EmailNotificationChannel} copies {@code
 * NotificationRequest.additionalConfig.reports} (ledger row R24) onto the outgoing {@link TbEmail}
 * (ledger row R27); this test covers the other end — {@link DefaultMailService#send} must resolve
 * each {@link ReportId} via the dao {@link ReportService} and attach its bytes to the {@link
 * MimeMessage} (ledger row R26). No {@code DefaultMailServiceTest} pre-dates this file (only {@link
 * TbMailSenderTest} exists in this package, covering a different class), so {@link
 * DefaultMailService} is built by hand with {@code @InjectMocks} (constructor injection over its
 * {@code @RequiredArgsConstructor}) and the assertion inspects the produced {@link MimeMessage}
 * directly rather than mocking {@link org.springframework.mail.javamail.MimeMessageHelper}.
 *
 * <p>{@code mailExecutorService.submit(Runnable)} is stubbed to run the task inline and return an
 * already-completed future — {@link DefaultMailService} never receives an
 * {@code @PostConstruct}-initialized real executor here (the bean is hand-built, not
 * Spring-managed), so the unstubbed mock would otherwise return {@code null} and
 * {@code Futures.withTimeout} would NPE. Same idea as {@link org.thingsboard.common.util.DirectListeningExecutor}.
 */
@ExtendWith(MockitoExtension.class)
class ReportMailAttachmentTest {

    @Mock
    private MessageSource messages;
    @Mock
    private Configuration freemarkerConfig;
    @Mock
    private AdminSettingsService adminSettingsService;
    @Mock
    private TbApiUsageReportClient apiUsageClient;
    @Mock
    private TbApiUsageStateService apiUsageStateService;
    @Mock
    private MailSenderInternalExecutorService mailExecutorService;
    @Mock
    private PasswordResetExecutorService passwordResetExecutorService;
    @Mock
    private TbMailContextComponent ctx;
    @Mock
    private RateLimitService rateLimitService;
    @Mock
    private ReportService reportService;

    @InjectMocks
    private DefaultMailService mailService;

    @Mock
    private JavaMailSender javaMailSender;

    @Test
    void attachesReportBytesToOutgoingEmail() throws Exception {
        ReportId reportId = new ReportId(UUID.randomUUID());
        Report report = new Report(reportId);
        report.setName("r.pdf");
        report.setFormat(TbReportFormat.PDF);
        when(reportService.findReportById(any(), eq(reportId))).thenReturn(report);
        when(reportService.getReportData(any(), eq(reportId))).thenReturn("%PDF-x".getBytes(StandardCharsets.UTF_8));

        // ApiUsageState defaults emailExecState to null -> isEmailSendEnabled() is true.
        when(apiUsageStateService.getApiUsageState(any())).thenReturn(new ApiUsageState());

        MimeMessage mimeMessage = new MimeMessage((Session) null);
        when(javaMailSender.createMimeMessage()).thenReturn(mimeMessage);

        doAnswer(invocation -> {
            Runnable task = invocation.getArgument(0);
            task.run();
            return Futures.immediateFuture(null);
        }).when(mailExecutorService).submit(any(Runnable.class));

        TbEmail email = TbEmail.builder()
                .from("noreply@thingsboard.io")
                .to("a@b.c")
                .subject("s")
                .body("b")
                .html(true)
                .reports(List.of(reportId))
                .build();

        mailService.send(TenantId.SYS_TENANT_ID, null, email, javaMailSender, 5000);

        Object content = mimeMessage.getContent();
        assertThat(content).isInstanceOf(MimeMultipart.class);
        MimeMultipart multipart = (MimeMultipart) content;
        boolean attachmentFound = false;
        for (int i = 0; i < multipart.getCount(); i++) {
            BodyPart part = multipart.getBodyPart(i);
            if ("r.pdf".equals(part.getFileName())) {
                attachmentFound = true;
            }
        }
        assertThat(attachmentFound).isTrue();

        verify(reportService).getReportData(any(), eq(reportId));
    }

}
