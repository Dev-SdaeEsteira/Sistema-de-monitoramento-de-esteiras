package com.cba.sdae.config;

import com.cba.sdae.service.LoginAttemptService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
public class CustomAuthenticationFailureHandler implements AuthenticationFailureHandler {

    private final LoginAttemptService loginAttemptService;

    public CustomAuthenticationFailureHandler(LoginAttemptService loginAttemptService) {
        this.loginAttemptService = loginAttemptService;
    }

    @Override
    public void onAuthenticationFailure(HttpServletRequest request, 
                                        HttpServletResponse response,
                                        AuthenticationException exception) throws IOException {
        String username = request.getParameter("email");
        String normalizedUser = (username != null && !username.isBlank()) ? username.trim().toLowerCase() : "unknown";
        String clientIP = request.getRemoteAddr();
        String blockKey = clientIP + ":" + normalizedUser;

        loginAttemptService.loginFailed(blockKey);

        response.sendRedirect("/login?erro=true");
    }
}
