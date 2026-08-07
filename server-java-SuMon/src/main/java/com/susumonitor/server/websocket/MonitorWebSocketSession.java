package com.susumonitor.server.websocket;

import com.susumonitor.server.security.AuthenticatedUser;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.web.socket.handler.SessionLimitExceededException;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.TextMessage;
import java.io.IOException;

/** 保存 Monitor WebSocket 的用户身份和服务器订阅集合。 */
public final class MonitorWebSocketSession {

    private final WebSocketSession socketSession;
    private final AuthenticatedUser user;
    private final Set<Long> subscribedServerIds = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean terminationStarted = new AtomicBoolean();

    /** 创建已通过握手 ticket 验证的 Monitor 会话。 */
    public MonitorWebSocketSession(WebSocketSession socketSession, AuthenticatedUser user) {
        this.socketSession = socketSession;
        this.user = user;
    }

    /** 返回底层 Spring WebSocket 会话。 */
    public WebSocketSession socketSession() {
        return socketSession;
    }

    /** 返回已认证的 Monitor 用户身份。 */
    public AuthenticatedUser user() {
        return user;
    }

    /** 返回当前 Monitor 已订阅的服务器 ID 集合。 */
    public Set<Long> subscribedServerIds() {
        return subscribedServerIds;
    }

    /**
     * 标记当前连接已开始收口，确保并发的背压、I/O 失败和关闭回调只执行一次资源释放。
     *
     * @return 当前调用是否取得收口执行权
     */
    public boolean beginTermination() {
        return terminationStarted.compareAndSet(false, true);
    }

    /**
     * 通过 Spring 装饰器写入单个浏览器连接，使慢发送期间的后续消息进入受限缓冲而非阻塞调用线程。
     *
     * <p>不能在此方法外加 synchronized；装饰器本身负责串行写入，外层锁会阻止后续发送进入缓冲和超额终止逻辑。</p>
     */
    public boolean send(TextMessage message) throws IOException {
        if (!socketSession.isOpen()) {
            return false;
        }
        try {
            socketSession.sendMessage(message);
        } catch (SessionLimitExceededException exception) {
            throw new MonitorBackpressureException(exception);
        }
        return true;
    }
}
