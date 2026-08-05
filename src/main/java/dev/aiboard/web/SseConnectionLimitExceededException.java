package dev.aiboard.web;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * 事件串流已達連線上限。
 *
 * <p>回 503 而不是靜默接受：每條 SSE 連線都佔用一個永不結束的 Tomcat async
 * request，無上限地收下去只會讓行程慢慢被拖垮，而且沒有任何跡象。明確的 503
 * 至少會出現在瀏覽器 console 與伺服器日誌裡，看得出發生了什麼。
 *
 * <p>正常使用不可能碰到（單機看板，個位數分頁）；會碰到代表有東西在不斷開新連線
 * 卻沒有關掉舊的。
 */
@ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
public class SseConnectionLimitExceededException extends RuntimeException {

    public SseConnectionLimitExceededException(int maxConnections) {
        super("事件串流已達連線上限（" + maxConnections + "），請關閉未使用的看板分頁後重試。");
    }
}
