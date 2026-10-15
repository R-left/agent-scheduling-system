package com.leftrr.agentscheduling.mcp;

import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import org.apache.catalina.Context;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.startup.Tomcat;

/**
 * 内嵌Tomcat, 承载MCP服务
 */
public final class EmbeddedTomcatServer {

    private final Tomcat tomcat;
    private final int port;

    private EmbeddedTomcatServer(Tomcat tomcat, int port) {
        this.tomcat = tomcat;
        this.port = port;
    }

    /**
     * 装配 server与容器并启动
     */
    public static EmbeddedTomcatServer start() {
        HttpServletStreamableServerTransportProvider transport = CommonMcpServer.build().connect();

        Tomcat tomcat = new Tomcat();
        tomcat.setPort(0); // 随机空闲端口，start 后取实际值
        var connector = tomcat.getConnector();
        connector.setProperty("address", "127.0.0.1");
        Context context = tomcat.addContext("", System.getProperty("java.io.tmpdir"));
        Tomcat.addServlet(context, "mcp", transport).addMapping(McpCliArgs.MCP_ENDPOINT);
        try {
            tomcat.start();
        } catch (LifecycleException e) {
            throw new IllegalStateException("内嵌 MCP HTTP server 启动失败", e);
        }
        int port = tomcat.getConnector().getLocalPort();
        McpCliArgs.configure("http://127.0.0.1:" + port + McpCliArgs.MCP_ENDPOINT);
        return new EmbeddedTomcatServer(tomcat, port);
    }

    /** 实际监听的 streamable HTTP 端点 URL。 */
    public String url() {
        return "http://127.0.0.1:" + port + McpCliArgs.MCP_ENDPOINT;
    }

    /** 停机钩子用；失败不打断进程退出。 */
    public void stop() {
        try {
            tomcat.stop();
            tomcat.destroy();
        } catch (LifecycleException ignored) {
            // 进程终止即释放端口，清理失败无需处理
        }
    }
}
