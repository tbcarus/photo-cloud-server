package ru.tbcarus.photocloudserver.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.PostConstruct;
import jakarta.validation.Valid;
import jakarta.validation.Validator;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import ru.tbcarus.photocloudserver.exception.BadRegistrationRequest;
import ru.tbcarus.photocloudserver.model.EmailRequestType;
import ru.tbcarus.photocloudserver.model.dto.PasswordResetConfirmRequest;
import ru.tbcarus.photocloudserver.service.EmailRequestService;
import ru.tbcarus.photocloudserver.service.UserService;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;

@Slf4j
@RestController
@Validated
@RequiredArgsConstructor
@RequestMapping(PasswordController.BASE_URL)
@Tag(name = "Password management")
public class PasswordController {
    // Handles password recovery and future password-management entrypoints.
    public static final String BASE_URL = ApiPaths.API_V1 + "/auth/password";
    public static final String RESET_REQUEST_URL = "/reset/request";
    public static final String RESET_CONFIRM_URL = "/reset/confirm";
    public static final String RESET_RESEND_URL = "/reset/resend";
    public static final String RESET_PAGE_URL = "/reset/page";
    public static final String RESET_PAGE_TEMPLATE = "page/passwordReset";

    static final String PAGE_FORM = "FORM";
    static final String PAGE_SUCCESS = "SUCCESS";
    static final String PAGE_INVALID_LINK = "INVALID_LINK";
    static final String PAGE_FAILED = "FAILED";
    static final String PASSWORDS_DIFFER = "Пароли не совпадают";

    private static final MediaType HTML = new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8);
    private static final SecureRandom NONCE_RANDOM = new SecureRandom();

    private final UserService userService;
    private final EmailRequestService emailRequestService;
    private final SpringTemplateEngine templateEngine;
    private final Validator validator;

    private int passwordMin;
    private int passwordMax;

    // Длина пароля для подсказки страницы берётся из правил PasswordResetConfirmRequest, а не задаётся повторно.
    @PostConstruct
    void readPasswordRules() {
        Size size = validator.getConstraintsForClass(PasswordResetConfirmRequest.class)
                .getConstraintsForProperty("password").getConstraintDescriptors().stream()
                .map(descriptor -> descriptor.getAnnotation())
                .filter(Size.class::isInstance)
                .map(Size.class::cast)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("PasswordResetConfirmRequest.password has no @Size"));
        passwordMin = size.min();
        passwordMax = size.max();
    }

    @Operation(summary = "Password reset request")
    @PostMapping(RESET_REQUEST_URL)
    public ResponseEntity<Map<String, String>> forgotPassword(@RequestParam @NotBlank(message = "Email must not be blank") String email) {
        userService.forgotPassword(email);
        return ResponseEntity.status(HttpStatus.OK).body(Map.of("message", "Email was sent"));
    }

    @Operation(summary = "Password reset confirmation")
    @PostMapping(RESET_CONFIRM_URL)
    public ResponseEntity<Map<String, String>> resetPassword(@RequestBody @Valid PasswordResetConfirmRequest request) {
        userService.resetPassword(request.password(), request.code());
        return ResponseEntity.status(HttpStatus.OK).body(Map.of("message", "Password was reset"));
    }

    @Operation(summary = "Reserved endpoint for resending password reset link")
    @PostMapping(RESET_RESEND_URL)
    public ResponseEntity<Map<String, String>> resendResetPassword() {
        // TODO: Resend password reset link with cooldown, attempt limit, and code expiration checks.
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).body(Map.of("message", "Password reset resend is not implemented yet"));
    }

    @Operation(summary = "Password reset HTML page opened from the email link")
    @GetMapping(RESET_PAGE_URL)
    public ResponseEntity<String> getResetPasswordPage(@RequestParam(required = false) String code) {
        // Предварительная проверка только выбирает страницу: код окончательно проверяется при отправке формы.
        try {
            if (code == null || code.isBlank()) {
                return page(HttpStatus.BAD_REQUEST, PAGE_INVALID_LINK, null, null);
            }
            emailRequestService.checkEmailRequest(emailRequestService.getEmailRequestByCode(code), EmailRequestType.PASSWORD_RESET);
            return page(HttpStatus.OK, PAGE_FORM, code, null);
        } catch (BadRegistrationRequest e) {
            return page(HttpStatus.BAD_REQUEST, PAGE_INVALID_LINK, null, null);
        } catch (RuntimeException e) {
            return failed(e);
        }
    }

    @Operation(summary = "Password reset HTML page form submit")
    @PostMapping(RESET_PAGE_URL)
    public ResponseEntity<String> submitResetPasswordPage(@RequestParam(required = false) String code,
                                                          @RequestParam(required = false) String password,
                                                          @RequestParam(required = false) String passwordConfirm) {
        // Ответ — всегда HTML-страница: введённый пароль в неё не возвращается.
        try {
            if (code == null || code.isBlank()) {
                return page(HttpStatus.BAD_REQUEST, PAGE_INVALID_LINK, null, null);
            }
            if (password == null || !password.equals(passwordConfirm)) {
                return page(HttpStatus.BAD_REQUEST, PAGE_FORM, code, PASSWORDS_DIFFER);
            }
            if (!validator.validate(new PasswordResetConfirmRequest(password, code)).isEmpty()) {
                return page(HttpStatus.BAD_REQUEST, PAGE_FORM, code, passwordRuleMessage());
            }
            // Та же операция, что у JSON reset/confirm: проверка кода, новый пароль и отзыв сессий.
            userService.resetPassword(password, code);
            return page(HttpStatus.OK, PAGE_SUCCESS, null, null);
        } catch (BadRegistrationRequest e) {
            return page(HttpStatus.BAD_REQUEST, PAGE_INVALID_LINK, null, null);
        } catch (RuntimeException e) {
            return failed(e);
        }
    }

    private String passwordRuleMessage() {
        return "Пароль должен содержать от %d до %d символов и не может состоять только из пробелов"
                .formatted(passwordMin, passwordMax);
    }

    private ResponseEntity<String> failed(RuntimeException e) {
        // Пользователь видит общий текст без деталей; код и пароль в сообщение журнала не добавляются.
        log.error("Password reset page failed", e);
        return page(HttpStatus.INTERNAL_SERVER_ERROR, PAGE_FAILED, null, null);
    }

    /**
     * Страница без внешних ресурсов: CSP разрешает только собственные стиль и скрипт по nonce и отправку формы
     * на тот же адрес. Ссылки формы относительные, поэтому страница работает под базовым путём reverse proxy.
     * no-store и no-referrer не дают сохранить страницу с кодом в кеше и передать адрес с кодом дальше.
     */
    private ResponseEntity<String> page(HttpStatus status, String state, String code, String error) {
        byte[] nonceBytes = new byte[16];
        NONCE_RANDOM.nextBytes(nonceBytes);
        String nonce = Base64.getEncoder().encodeToString(nonceBytes);
        Context context = new Context();
        context.setVariable("state", state);
        context.setVariable("code", code);
        context.setVariable("error", error);
        context.setVariable("passwordMin", passwordMin);
        context.setVariable("passwordMax", passwordMax);
        context.setVariable("nonce", nonce);
        String html = templateEngine.process(RESET_PAGE_TEMPLATE, context);
        return ResponseEntity.status(status)
                .contentType(HTML)
                .cacheControl(CacheControl.noStore())
                .header("Referrer-Policy", "no-referrer")
                .header("X-Robots-Tag", "noindex, nofollow")
                .header("Content-Security-Policy", "default-src 'none'; script-src 'nonce-" + nonce + "'; "
                        + "style-src 'nonce-" + nonce + "'; form-action 'self'; base-uri 'none'; frame-ancestors 'none'")
                .body(html);
    }
}
