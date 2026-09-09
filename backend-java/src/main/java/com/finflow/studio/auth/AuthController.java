package com.finflow.studio.auth;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    public static final String COOKIE_NAME = "FINFLOW_SESSION";
    private final FixedAccountService accounts;
    private final SessionTokenService tokens;
    private final boolean secureCookie;

    public AuthController(FixedAccountService accounts, SessionTokenService tokens,
                          @Value("${finflow.auth.secure-cookie:false}") boolean secureCookie) {
        this.accounts = accounts;
        this.tokens = tokens;
        this.secureCookie = secureCookie;
    }

    @PostMapping("/login")
    UserResponse login(@Valid @RequestBody LoginRequest request, HttpServletResponse response) {
        var username = request.username().trim();
        if (!accounts.authenticate(username, request.password())) {
            throw new SecurityException("账号或密码不正确");
        }
        response.addHeader(HttpHeaders.SET_COOKIE, cookie(tokens.issue(username), -1).toString());
        return new UserResponse(username);
    }

    @GetMapping("/me")
    UserResponse me() {
        return new UserResponse(ActorContext.current());
    }

    @PostMapping("/logout")
    void logout(HttpServletResponse response) {
        response.addHeader(HttpHeaders.SET_COOKIE, cookie("", 0).toString());
    }

    private ResponseCookie cookie(String value, long maxAge) {
        var builder = ResponseCookie.from(COOKIE_NAME, value).httpOnly(true).secure(secureCookie)
                .sameSite("Lax").path("/");
        if (maxAge >= 0) builder.maxAge(maxAge);
        return builder.build();
    }

    record LoginRequest(@NotBlank String username, @NotBlank String password) { }
    public record UserResponse(String username) { }
}
