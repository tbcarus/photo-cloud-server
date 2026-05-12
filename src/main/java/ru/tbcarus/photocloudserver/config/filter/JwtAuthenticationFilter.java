package ru.tbcarus.photocloudserver.config.filter;

import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import ru.tbcarus.photocloudserver.model.TokenType;
import ru.tbcarus.photocloudserver.service.JwtService;
import ru.tbcarus.photocloudserver.service.UserService;

import java.io.IOException;

@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {
    public static final String BEARER_PREFIX = "Bearer ";
    private final JwtService jwtService;
    private final UserService userService;
    private final JsonAuthenticationEntryPoint entryPoint;

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response, @NonNull FilterChain filterChain) throws ServletException, IOException {

        // Получаем токен из заголовка
        String token = resolveBearer(request);
        if (token == null) {
            filterChain.doFilter(request, response);
            return;
        }

        try {
            String tokenType = jwtService.extractTokenType(token);
            if(!TokenType.ACCESS.getValue().equals(tokenType)) {
                filterChain.doFilter(request, response);
                return;
            }
            String username = jwtService.extractUserName(token);
            if (StringUtils.isNotEmpty(username) && SecurityContextHolder.getContext().getAuthentication() == null) {
                UserDetails userDetails = userService.loadUserByUsername(username);

                // Если токен валиден, то аутентифицируем пользователя
                if (jwtService.isTokenValid(token, userDetails)) {
                    UsernamePasswordAuthenticationToken authToken = new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());
                    authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                    SecurityContextHolder.getContext().setAuthentication(authToken);
                }
            }
        } catch (ExpiredJwtException ex) {
            SecurityContextHolder.clearContext();
            // Делегируем 401 в entry point
            entryPoint.commence(request, response, new InsufficientAuthenticationException("JWT expired", ex));
            return;

        } catch (JwtException | IllegalArgumentException ex) {
            SecurityContextHolder.clearContext();
            entryPoint.commence(request, response, new InsufficientAuthenticationException("Invalid JWT", ex));
            return;
        }

        filterChain.doFilter(request, response);
    }

    private String resolveBearer(HttpServletRequest request) {
        String authHeader = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authHeader != null && authHeader.startsWith(BEARER_PREFIX)) {
            return authHeader.substring(BEARER_PREFIX.length());
        }
        return null;
    }
}
