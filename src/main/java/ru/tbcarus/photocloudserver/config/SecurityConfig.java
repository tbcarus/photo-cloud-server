package ru.tbcarus.photocloudserver.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import ru.tbcarus.photocloudserver.config.filter.JsonAccessDeniedHandler;
import ru.tbcarus.photocloudserver.config.filter.JsonAuthenticationEntryPoint;
import ru.tbcarus.photocloudserver.config.filter.JwtAuthenticationFilter;
import ru.tbcarus.photocloudserver.controller.ApiPaths;
import ru.tbcarus.photocloudserver.controller.AuthController;
import ru.tbcarus.photocloudserver.controller.PasswordController;
import ru.tbcarus.photocloudserver.controller.RegisterController;
import ru.tbcarus.photocloudserver.controller.RootController;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true, securedEnabled = true, jsr250Enabled = true)
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final JsonAuthenticationEntryPoint authenticationEntryPoint;
    private final JsonAccessDeniedHandler accessDeniedHandler;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests((requests) -> requests
                        .requestMatchers(
                                ApiPaths.API_V1 + RootController.TEST_URL,
                                RegisterController.BASE_URL + RegisterController.REGISTER_URL,
                                RegisterController.BASE_URL + RegisterController.CONFIRM_URL,
                                RegisterController.BASE_URL + RegisterController.RESEND_URL,
                                AuthController.BASE_URL + AuthController.LOGIN_URL,
                                AuthController.BASE_URL + AuthController.REFRESH_TOKEN_URL,
                                PasswordController.BASE_URL + PasswordController.RESET_REQUEST_URL,
                                PasswordController.BASE_URL + PasswordController.RESET_CONFIRM_URL,
                                PasswordController.BASE_URL + PasswordController.RESET_RESEND_URL,
                                PasswordController.BASE_URL + PasswordController.RESET_PAGE_URL,
                                "/swagger-ui/**", "/swagger-resources/*", "/v3/api-docs/**").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(ex -> ex
                .authenticationEntryPoint(authenticationEntryPoint) // 401
                .accessDeniedHandler(accessDeniedHandler)           // 403
        )
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .csrf(csrf -> csrf.disable());

        return http.build();
    }
}
