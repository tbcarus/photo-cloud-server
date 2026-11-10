package ru.tbcarus.photocloudserver.integration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import ru.tbcarus.photocloudserver.model.EmailRequest;
import ru.tbcarus.photocloudserver.model.EmailRequestType;
import ru.tbcarus.photocloudserver.model.User;
import ru.tbcarus.photocloudserver.service.EmailRequestService;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

// Login меняет только время входа: параллельно изменённые поля User (password hash после reset) не затираются.
class LoginUpdateIntegrationTest extends AbstractIntegrationTest {

    private static final String EMAIL = "login@test.local";

    @MockitoSpyBean
    PasswordEncoder spyEncoder;

    @Autowired
    EmailRequestService emailRequestService;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @AfterEach
    void stopExecutor() {
        executor.shutdownNow();
    }

    @Test
    void successfulLoginUpdatesBothTimesAndNothingElse() throws Exception {
        User user = createUser(EMAIL, "pass1");
        Map<String, Object> before = row(user.getId());
        LocalDateTime startedAt = LocalDateTime.now().minusSeconds(1);

        assertThat(login("pass1")).isEqualTo(200);

        Map<String, Object> after = row(user.getId());
        LocalDateTime lastLoginAt = ((java.sql.Timestamp) after.get("last_login_at")).toLocalDateTime();
        LocalDateTime lastUpdate = ((java.sql.Timestamp) after.get("last_update")).toLocalDateTime();
        assertThat(lastLoginAt).isAfter(startedAt).isEqualTo(lastUpdate);
        assertThat(lastUpdate).isAfter((java.sql.Timestamp.class.cast(before.get("last_update"))).toLocalDateTime());
        assertThat(withoutTimes(after)).isEqualTo(withoutTimes(before));
        assertThat(roles(user.getId())).containsExactly("USER");
    }

    @Test
    void wrongPasswordChangesNothing() throws Exception {
        User user = createUser(EMAIL, "pass1");
        Map<String, Object> before = row(user.getId());

        assertThat(login("wrong")).isEqualTo(401);

        assertThat(row(user.getId())).isEqualTo(before);
    }

    @Test
    void loginStartedBeforeResetDoesNotRestoreOldPassword() throws Exception {
        User user = createUser(EMAIL, "oldpass");
        EmailRequest reset = emailRequestService.generateEmailRequest(user, EmailRequestType.PASSWORD_RESET);

        // Login со старым паролем прочитал User и проверил пароль; reset фиксируется до того, как login сохранит время входа.
        CountDownLatch checked = new CountDownLatch(1);
        CountDownLatch resetDone = new CountDownLatch(1);
        AtomicBoolean armed = new AtomicBoolean(true);
        doAnswer(invocation -> {
            Object matches = invocation.callRealMethod();
            if (armed.compareAndSet(true, false)) {
                checked.countDown();
                assertThat(resetDone.await(10, TimeUnit.SECONDS)).isTrue();
            }
            return matches;
        }).when(spyEncoder).matches(any(), any());

        Future<Integer> racingLogin = executor.submit(() -> login("oldpass"));
        assertThat(checked.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(resetConfirm(reset.getCode(), "newpass")).isEqualTo(200);
        String resetHash = (String) row(user.getId()).get("password");
        Map<String, Object> afterReset = row(user.getId());
        resetDone.countDown();

        assertThat(racingLogin.get(10, TimeUnit.SECONDS)).isEqualTo(200);
        assertThat(row(user.getId()).get("password")).isEqualTo(resetHash);
        assertThat(withoutTimes(row(user.getId()))).isEqualTo(withoutTimes(afterReset));
        assertThat(login("newpass")).isEqualTo(200);
        assertThat(login("oldpass")).isEqualTo(401);
    }

    private int login(String password) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + EMAIL + "\",\"password\":\"" + password + "\"}"))
                .andReturn().getResponse().getStatus();
    }

    private int resetConfirm(String code, String password) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/password/reset/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + code + "\",\"password\":\"" + password + "\"}"))
                .andReturn().getResponse().getStatus();
    }

    private Map<String, Object> row(Long id) {
        return jdbcTemplate.queryForMap("SELECT * FROM users WHERE id = ?", id);
    }

    private java.util.List<String> roles(Long id) {
        return jdbcTemplate.queryForList("SELECT role FROM user_roles WHERE user_id = ?", String.class, id);
    }

    private static Map<String, Object> withoutTimes(Map<String, Object> row) {
        Map<String, Object> copy = new HashMap<>(row);
        copy.remove("last_login_at");
        copy.remove("last_update");
        return copy;
    }
}
