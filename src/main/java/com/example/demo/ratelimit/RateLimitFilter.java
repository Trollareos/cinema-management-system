package com.example.demo.ratelimit;

import com.example.demo.error.ApiError;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.regex.Pattern;

@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private final RateLimitService rateLimit;
    private final ObjectMapper objectMapper;

    private static final Pattern SCREENING_SUBMIT = Pattern.compile("^/api/screenings/\\d+/submit$");
    private static final Pattern SCREENING_SEARCH_IN_PROGRAM = Pattern.compile("^/api/programs/\\d+/screenings$");
    private static final Pattern PUBLIC_SCREENING_SEARCH = Pattern.compile("^/api/public/programs/\\d+/screenings$");

    public RateLimitFilter(RateLimitService rateLimit, ObjectMapper objectMapper) {
        this.rateLimit = rateLimit;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        // μην βάζουμε rate limit σε H2 console
        return path.startsWith("/h2");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        Rule rule = ruleFor(request);

        if (rule == null) {
            filterChain.doFilter(request, response);
            return;
        }

        String clientId = clientId(request);
        String key = rule.name + ":" + clientId;

        RateLimitService.Result res = rateLimit.tryConsume(key, rule.limit, rule.windowMs);

        if (res.allowed) {
            filterChain.doFilter(request, response);
            return;
        }

        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader("Retry-After", String.valueOf(res.retryAfterSeconds));
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");

        ApiError body = new ApiError(
                HttpStatus.TOO_MANY_REQUESTS.value(),
                HttpStatus.TOO_MANY_REQUESTS.getReasonPhrase(),
                "RATE_LIMITED",
                "Too many requests. Retry after " + res.retryAfterSeconds + " seconds.",
                request.getRequestURI()
        );

        response.getWriter().write(objectMapper.writeValueAsString(body));
    }

    private static final class Rule {
        final String name;
        final int limit;
        final long windowMs;

        Rule(String name, int limit, long windowMs) {
            this.name = name;
            this.limit = limit;
            this.windowMs = windowMs;
        }
    }

    private Rule ruleFor(HttpServletRequest req) {
        String path = req.getRequestURI();
        String method = req.getMethod();

        // 1) Screening create (POST /api/screenings)
        if (path.equals("/api/screenings") && method.equals("POST")) {
            return new Rule("screening_create", 10, 60_000L); // 10/min
        }

        // 2) Screening submit (POST /api/screenings/{id}/submit)
        if (SCREENING_SUBMIT.matcher(path).matches() && method.equals("POST")) {
            return new Rule("screening_submit", 5, 60_000L); // 5/min
        }

        // 3) Program search authenticated (GET /api/programs)
        if (path.equals("/api/programs") && method.equals("GET")) {
            return new Rule("program_search_auth", 120, 60_000L); // 120/min
        }

        // 4) Program search public (GET /api/public/programs)
        if (path.equals("/api/public/programs") && method.equals("GET")) {
            return new Rule("program_search_public", 60, 60_000L); // 60/min per IP
        }

        // 5) Screening search in program (auth)
        if (SCREENING_SEARCH_IN_PROGRAM.matcher(path).matches() && method.equals("GET")) {
            return new Rule("screening_search_auth", 120, 60_000L);
        }

        // 6) Screening search in program (public)
        if (PUBLIC_SCREENING_SEARCH.matcher(path).matches() && method.equals("GET")) {
            return new Rule("screening_search_public", 60, 60_000L);
        }

        return null;
    }

    private String clientId(HttpServletRequest req) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();

        if (auth != null && auth.isAuthenticated() && auth.getName() != null
                && !"anonymousUser".equalsIgnoreCase(auth.getName())) {
            return "user:" + auth.getName().toLowerCase();
        }

        // Visitor -> by IP
        String xff = req.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            String first = xff.split(",")[0].trim();
            if (!first.isBlank()) return "ip:" + first;
        }
        return "ip:" + req.getRemoteAddr();
    }
}
