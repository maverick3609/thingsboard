// SPDX-FileCopyrightText: Copyright The Inferrix Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.service.security.permission;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registers the RBAC read gate. Additive: it contributes an interceptor and touches nothing else
 * in the MVC setup.
 */
@Configuration
@RequiredArgsConstructor
public class RoleReadGateConfiguration implements WebMvcConfigurer {

    @Lazy
    private final RoleReadGateInterceptor roleReadGateInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(roleReadGateInterceptor).addPathPatterns("/api/**");
    }

}
