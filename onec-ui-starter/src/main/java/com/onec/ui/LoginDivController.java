package com.onec.ui;

import com.onec.auth.spi.AuthMethods;
import com.onec.auth.spi.AuthMethodsProvider;
import com.onec.ui.divkit.LoginDivBuilder;
import com.onec.ui.divkit.Palette;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Serves the server-driven (DivKit) login screen at {@code GET /api/divkit/login}. Public — it
 * renders before sign-in. The available methods come from the auth module via the
 * {@link AuthMethodsProvider} SPI; when no provider bean is present (the auth starter is absent),
 * it degrades to a password-only screen.
 */
@RestController
@RequestMapping("/api/divkit")
public class LoginDivController {

    private final ObjectProvider<AuthMethodsProvider> authMethods;

    public LoginDivController(ObjectProvider<AuthMethodsProvider> authMethods) {
        this.authMethods = authMethods;
    }

    @GetMapping("/login")
    public Map<String, Object> login(@RequestParam(required = false) String theme) {
        return LoginDivBuilder.login(resolveMethods(), Palette.of(theme));
    }

    private AuthMethods resolveMethods() {
        AuthMethodsProvider provider = authMethods.getIfAvailable();
        if (provider != null) {
            return provider.authMethods();
        }
        // Auth starter absent — fall back to a plain password screen.
        return new AuthMethods(true, List.of(), null, "in-memory");
    }
}
