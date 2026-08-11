package dev.aiboard.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 阻擋 DNS rebinding 與跨站呼叫：驗證 {@code Host} 與 {@code Origin} 兩個 header
 * 都指向本機，否則直接回 403，不進入 MCP router 或 REST controller。
 *
 * <h2>為什麼綁定 127.0.0.1 還不夠</h2>
 * {@code server.address=127.0.0.1} 只保證「封包必須來自本機網卡」，擋不住本機
 * 瀏覽器：使用者只要開啟一個惡意網頁，該網頁的網域可以把 DNS 指向 127.0.0.1
 * （DNS rebinding），此後瀏覽器認定 {@code http://evil.example:8080} 與看板
 * 同源，同源政策與 CORS 完全不介入，該網頁即可讀走整個看板，甚至呼叫
 * {@code /mcp} 上的 {@code archive_project}。本服務沒有 server-side 認證，
 * 這條路等於無門檻，因此 MCP 規格明確要求 local server 驗證 {@code Origin}。
 *
 * <h2>兩個 header 各擋一半，缺一不可</h2>
 * <ul>
 *   <li>{@code Origin}：瀏覽器對所有非 GET/HEAD 請求（含同源 POST）都會送出，
 *       因此 rebinding 之後對 {@code /mcp} 的 POST 會帶著
 *       {@code Origin: http://evil.example:8080}，被此處攔下。</li>
 *   <li>{@code Host}：rebinding 之後的 GET（例如 {@code /api/projects}）被瀏覽器
 *       視為同源而<b>不送</b> {@code Origin}，但 {@code Host} 仍然是攻擊者的
 *       網域名稱，因此改由 Host 白名單攔下。</li>
 * </ul>
 * 真正的本機 client（curl、Claude Code、Codex 的 MCP client）連的是
 * {@code 127.0.0.1}/{@code localhost}，Host 天然落在白名單內；非瀏覽器 client
 * 不送 {@code Origin}，也不受第二項影響。
 *
 * <h2>刻意不做的事</h2>
 * 這裡<b>不是</b>身分驗證，也不取代它：任何本機行程仍可直接呼叫全部工具
 * （見 README「工具治理與使用者授權」）。這個 filter 只關掉「瀏覽器可被誘導
 * 成為跳板」這條路徑，成本低、不影響既有使用方式，屬於縱深防禦的第一層。
 *
 * <h2>要從其他機器或網域存取時</h2>
 * 預設白名單只有 loopback 與 {@code server.address}（若不是萬用位址）。刻意要
 * 讓區網其他裝置或反向代理連入時，用 {@code BOARD_ALLOWED_HOSTS} 逐一列出
 * （逗號分隔，可含埠號，埠號會被忽略），例如
 * {@code BOARD_ALLOWED_HOSTS=192.168.1.20,board.local}。這仍然不會產生認證，
 * 對外暴露前請自行在前面加上驗證層。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class LocalOriginGuardFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(LocalOriginGuardFilter.class);

    /** loopback 的各種等價寫法；瀏覽器與各家 client 使用哪一種都不該有差別。 */
    private static final Set<String> LOOPBACK_HOSTS = Set.of(
            "localhost",
            "127.0.0.1",
            "::1",
            "0:0:0:0:0:0:0:1");

    /** 監聽萬用位址時，server.address 本身不構成有意義的白名單項目。 */
    private static final Set<String> WILDCARD_ADDRESSES = Set.of("0.0.0.0", "::", "*");

    private final Set<String> allowedHosts;

    public LocalOriginGuardFilter(@Value("${server.address:127.0.0.1}") String serverAddress,
                                   @Value("${board.security.allowed-hosts:}") String configuredHosts) {
        Set<String> hosts = new LinkedHashSet<>(LOOPBACK_HOSTS);

        String normalizedServerAddress = normalizeHost(serverAddress);
        if (normalizedServerAddress != null && !WILDCARD_ADDRESSES.contains(normalizedServerAddress)) {
            hosts.add(normalizedServerAddress);
        }

        Arrays.stream(configuredHosts.split(","))
                .map(LocalOriginGuardFilter::normalizeHost)
                .filter(host -> host != null && !host.isBlank())
                .forEach(hosts::add);

        this.allowedHosts = Set.copyOf(hosts);
    }

    Set<String> allowedHosts() {
        return allowedHosts;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                     HttpServletResponse response,
                                     FilterChain filterChain) throws ServletException, IOException {
        String host = effectiveHost(request);
        if (!isHostAllowed(host)) {
            reject(request, response, "Host", host);
            return;
        }

        String originHeader = request.getHeader("Origin");
        if (originHeader != null && !isOriginAllowed(originHeader)) {
            reject(request, response, "Origin", originHeader);
            return;
        }

        filterChain.doFilter(request, response);
    }

    /**
     * 取得這個請求宣稱的主機。優先讀 {@code Host} header；沒有時退回容器解析出來的
     * {@code getServerName()}——它本來就是從 {@code Host} 推導，只有在 HTTP/1.0
     * 這種不帶 Host 的請求下才會退回本機位址。
     *
     * <p>這個退路不會削弱防護：rebinding 一定來自瀏覽器，而瀏覽器都是 HTTP/1.1
     * 以上、必定送出 {@code Host}，走的是第一條路徑。
     */
    private String effectiveHost(HttpServletRequest request) {
        String hostHeader = request.getHeader("Host");
        return (hostHeader == null || hostHeader.isBlank()) ? request.getServerName() : hostHeader;
    }

    private boolean isHostAllowed(String hostHeader) {
        if (hostHeader == null || hostHeader.isBlank()) {
            return false;
        }
        String host = normalizeHost(hostHeader);
        return host != null && allowedHosts.contains(host);
    }

    /**
     * {@code Origin: null}（sandboxed iframe、{@code file://} 頁面、部分重新導向
     * 情境）不可能是本機看板頁面，一律拒絕，不當成「沒有 Origin」處理。
     */
    private boolean isOriginAllowed(String originHeader) {
        String origin = originHeader.trim();
        if (origin.isEmpty() || "null".equalsIgnoreCase(origin)) {
            return false;
        }

        URI uri;
        try {
            uri = new URI(origin);
        } catch (URISyntaxException ex) {
            return false;
        }

        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            return false;
        }

        String host = normalizeHost(uri.getHost());
        return host != null && allowedHosts.contains(host);
    }

    /**
     * 回應內容刻意不含被拒絕的 Host/Origin 原文：那是攻擊者可控的字串，只寫進
     * 伺服器日誌供使用者排查，不反射回呼叫方。
     */
    private void reject(HttpServletRequest request, HttpServletResponse response,
                        String headerName, String headerValue) throws IOException {
        log.warn("[origin-guard] 拒絕請求：{} 不在允許清單內（{}={}, path={}）。"
                        + " 若這是刻意要開放的來源，請用 BOARD_ALLOWED_HOSTS 列出。允許清單目前為 {}",
                headerName, headerName, headerValue, request.getRequestURI(), allowedHosts);

        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"error\":\"forbidden_host\",\"message\":\""
                + "請求的 Host/Origin 不在允許清單內，已拒絕（防止 DNS rebinding）。"
                + "看板預設只接受 localhost / 127.0.0.1；"
                + "要開放其他來源請設定 BOARD_ALLOWED_HOSTS。\"}");
    }

    /**
     * 統一成「純主機名稱、小寫、不含埠號與中括號」的比較形式。
     *
     * <p>三種輸入都要正確處理，其中裸 IPv6 是最容易寫錯的一種：
     * <ul>
     *   <li>{@code 127.0.0.1:8080} / {@code board.local:8080} — 單一冒號，切掉埠號。</li>
     *   <li>{@code [::1]:8080} — 中括號包住的 IPv6，取中括號內容、丟掉埠號。</li>
     *   <li>{@code ::1} — 裸 IPv6 不帶埠號，多個冒號本身就是位址的一部分，
     *       整串保留；若照單一冒號的規則切，會得到空字串而讓 loopback 比對失敗。</li>
     * </ul>
     * 順帶讓 {@code BOARD_ALLOWED_HOSTS} 可以直接貼上含埠號的字串（例如從瀏覽器
     * 網址列複製過來），不會因為多了埠號就比對不到。
     */
    private static String normalizeHost(String host) {
        if (host == null) {
            return null;
        }
        String normalized = host.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            return normalized;
        }
        if (normalized.startsWith("[")) {
            int closing = normalized.indexOf(']');
            return closing < 0 ? normalized.substring(1) : normalized.substring(1, closing);
        }
        int firstColon = normalized.indexOf(':');
        if (firstColon < 0) {
            return normalized;
        }
        if (normalized.indexOf(':', firstColon + 1) >= 0) {
            return normalized;
        }
        return normalized.substring(0, firstColon);
    }
}
