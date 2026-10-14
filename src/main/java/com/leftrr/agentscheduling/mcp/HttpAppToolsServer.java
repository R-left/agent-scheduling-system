package com.leftrr.agentscheduling.mcp;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.server.transport.ServerTransportSecurityValidator;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.catalina.Context;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.startup.Tomcat;
import org.apache.tomcat.util.descriptor.web.FilterDef;
import org.apache.tomcat.util.descriptor.web.FilterMap;

import java.util.UUID;

/**
 * agent_scheduling MCP 的长驻 HTTP 形态：内嵌 Tomcat + streamable HTTP transport，
 * Main 启动时拉起一次，全 bot 全 CLI 运行共享——不再按运行 spawn stdio 子进程。
 *
 * <p>防护：connector 只绑 127.0.0.1，全部请求过 Bearer token 过滤器（随机 token
 * 经 {@link McpCliArgs#configure} 进 CLI 启动参数）；SDK 的 origin/DNS-rebinding
 * 校验置 NOOP——客户端是 claude/codex 进程而非浏览器，loopback + token 下
 * 浏览器级防护无意义。</p>
 */
public final class HttpAppToolsServer {

    private final Tomcat tomcat;
    private final int port;

    private HttpAppToolsServer(Tomcat tomcat, int port) {
        this.tomcat = tomcat;
        this.port = port;
    }

    /** 装配 server 与容器并启动；端点与 token 经 {@link McpCliArgs#configure} 注册。 */
    public static HttpAppToolsServer start() {
        String token = UUID.randomUUID().toString();
        HttpServletStreamableServerTransportProvider transport =
                HttpServletStreamableServerTransportProvider.builder()
                        .jsonMapper(McpJsonDefaults.getMapper())
                        .mcpEndpoint(McpCliArgs.MCP_ENDPOINT)
                        // loopback + Bearer token 已覆盖本机场景，见类注释
                        .securityValidator(ServerTransportSecurityValidator.NOOP)
                        .build();
        AppToolsServer.build().connect(transport);

        Tomcat tomcat = new Tomcat();
        tomcat.setPort(0); // 随机空闲端口，start 后取实际值
        var connector = tomcat.getConnector();
        connector.setProperty("address", "127.0.0.1");
        Context context = tomcat.addContext("", System.getProperty("java.io.tmpdir"));
        // servlet 4+ 的 Filter 仅 doFilter 抽象，lambda 可直接充当
        FilterDef auth = new FilterDef();
        auth.setFilterName("bearerAuth");
        auth.setFilter((req, res, chain) -> {
            String header = ((HttpServletRequest) req).getHeader("Authorization");
            if (!("Bearer " + token).equals(header)) {
                ((HttpServletResponse) res).sendError(HttpServletResponse.SC_UNAUTHORIZED);
                return;
            }
            chain.doFilter(req, res);
        });
        context.addFilterDef(auth);
        FilterMap authMap = new FilterMap();
        authMap.setFilterName("bearerAuth");
        authMap.addURLPattern("/*");
        context.addFilterMap(authMap);
        Tomcat.addServlet(context, "mcp", transport).addMapping(McpCliArgs.MCP_ENDPOINT);
        try {
            tomcat.start();
        } catch (LifecycleException e) {
            throw new IllegalStateException("内嵌 MCP HTTP server 启动失败", e);
        }
        int port = tomcat.getConnector().getLocalPort();
        McpCliArgs.configure("http://127.0.0.1:" + port + McpCliArgs.MCP_ENDPOINT, token);
        return new HttpAppToolsServer(tomcat, port);
    }

    /** 实际监听的 streamable HTTP 端点 URL。 */
    public String url() {
        return "http://127.0.0.1:" + port + McpCliArgs.MCP_ENDPOINT;
    }

    /** 停机钩子用；尽力而为，失败不打断进程退出。 */
    public void stop() {
        try {
            tomcat.stop();
            tomcat.destroy();
        } catch (LifecycleException ignored) {
            // 进程终止即释放端口，清理失败无需处理
        }
    }
}
