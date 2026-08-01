package dev.aiboard.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 為所有回應（UI、REST、SSE、/mcp）加上同源導向的安全 response headers。
 *
 * CSP 說明：
 * - {@code script-src 'self' 'unsafe-eval'}：Vue 3 CDN 版（vue.global.prod.js）
 *   是 runtime+compiler 版本，{@code app.js} 用字串 {@code template:} 撰寫元件樣板，
 *   Vue 在瀏覽器端以 {@code Function(...)} 動態編譯樣板為 render function。
 *   這是 Vue 官方 runtime compiler 的既有機制，並非本專案寫死的 inline script，
 *   因此需要 {@code unsafe-eval}；改用 {@code unsafe-inline} 或拿掉兩者都會讓
 *   Vue 元件無法渲染。若要移除 unsafe-eval，需改用 Vue 的 runtime-only 版本
 *   並在建置期預先編譯樣板（引入建置流程），超出本任務範圍（AGENTS.md 規定
 *   「維持零建置 Vue 3 CDN」），故此處明確保留並記錄原因，不默默處理。
 * - 未使用 {@code unsafe-inline}：#109 已把所有 inline `<style>` 移到
 *   layout.css/tokens.css/fonts.css，index.html 沒有 inline script/style。
 * - {@code connect-src 'self'}：REST fetch 與 {@code EventSource('/api/events')}
 *   皆為同源，/mcp 亦同源，故同源即可涵蓋，不需額外白名單。
 * - {@code font-src 'self'}：字型已全部離線化至 /vendor/fonts。
 * - {@code frame-ancestors 'none'} + {@code X-Frame-Options: DENY}：禁止被嵌入 iframe。
 * - 未加入任何登入/OAuth 相關 header 或導向。
 */
@Component
public class SecurityHeadersFilter extends OncePerRequestFilter {

    private static final String CSP =
            "default-src 'self'; "
                    + "script-src 'self' 'unsafe-eval'; "
                    + "style-src 'self'; "
                    + "img-src 'self' data:; "
                    + "font-src 'self'; "
                    + "connect-src 'self'; "
                    + "frame-ancestors 'none'; "
                    + "base-uri 'self'; "
                    + "form-action 'self'; "
                    + "object-src 'none'";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                     HttpServletResponse response,
                                     FilterChain filterChain) throws ServletException, IOException {
        response.setHeader("Content-Security-Policy", CSP);
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader("Permissions-Policy", "geolocation=(), camera=(), microphone=()");

        filterChain.doFilter(request, response);
    }
}
