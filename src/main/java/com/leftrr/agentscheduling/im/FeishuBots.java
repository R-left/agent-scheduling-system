package com.leftrr.agentscheduling.im;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.oapi.core.request.EventReq;
import com.lark.oapi.event.CustomEventHandler;
import com.lark.oapi.event.EventDispatcher;
import com.lark.oapi.event.cardcallback.P2CardActionTriggerHandler;
import com.lark.oapi.event.cardcallback.model.CallBackCard;
import com.lark.oapi.event.cardcallback.model.CallBackToast;
import com.lark.oapi.event.cardcallback.model.P2CardActionTrigger;
import com.lark.oapi.event.cardcallback.model.P2CardActionTriggerResponse;
import com.lark.oapi.service.im.ImService;
import com.lark.oapi.service.im.v1.model.P2MessageReceiveV1;

import java.io.IOException;
import java.util.Map;

/**
 * WS 装配薄壳：EventDispatcher 三种 handler 接线 + ws.Client daemon 线程承载。
 * 对齐 TS 版 src/im/lark.ts:359-405 的 startBot 装配部分（本类不做单元测试，
 * 由 M7 端到端验证）。
 *
 * <p><b>SDK 事件类名核实（2026-10-13，unzip -l + javap oapi-sdk-2.4.7.jar，
 * D:\Maven\repository\com\larksuite\oapi\oapi-sdk\2.4.7\）</b>：
 * <ul>
 *   <li>消息：{@code ImService.P2MessageReceiveV1Handler}（抽象类，匿名子类覆写
 *       {@code handle(P2MessageReceiveV1)}，非 lambda）</li>
 *   <li>卡片回调：{@code com.lark.oapi.event.cardcallback.P2CardActionTriggerHandler}，
 *       接线方法 {@code EventDispatcher.Builder.onP2CardActionTrigger(..)}，
 *       覆写 {@code handle(P2CardActionTrigger)} 返回 {@code P2CardActionTriggerResponse}</li>
 *   <li>文档评论：drive.notice.comment_add_v1 的 typed handler 在 2.4.7 完全缺失
 *       （unzip 全量核对无 CommentAdd 类）→ 按 R4 裁决走
 *       {@code EventDispatcher.Builder.onCustomizedEvent(String, CustomEventHandler)}，
 *       与设计裁决 3 的纯翻译层同构，EventReq body 手工解析</li>
 * </ul>
 *
 * <p><b>SDK 模型 → JsonNode 访问链（javap 字节码核实）</b>：ws.Client 收到帧后调
 * {@code EventDispatcher.doWithoutValidation(byte[])}，后者以原始信封字节构造
 * EventReq（setBody）并 {@code BaseEventV2.setEventReq} 挂到 typed 事件上——因此
 * {@code event.getEventReq().getBody()} 即完整 v2 信封 JSON
 * {@code {"schema":"2.0","header":{...},"event":{...}}}，其 {@code event} 子树
 * 与 TS 回调 data 同构；CustomEventHandler 收到的 EventReq 同理。
 *
 * <p><b>线程口径（R11 裁决）</b>：handler 内在 SDK WS 回调线程<b>同步直调</b>
 * {@code opts.onMessage().onMessage(msg, bot)}，不建线程池；编排层（M6）自行决定
 * 线程口径（自行切虚拟线程或同步处理均可）。
 */
public final class FeishuBots {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private FeishuBots() {
    }

    /** 对齐 TS startBot：装配 dispatcher、启动 WS daemon 线程，立即返回 bot。 */
    public static Bot start(BotOptions opts) {
        Bot bot = new LarkBot(new OapiLarkApi(opts.appId(), opts.appSecret()));

        // TODO(M7): verificationToken/encryptKey 传空串对齐 TS new Lark.EventDispatcher({})
        //  的无鉴权形态；若 SDK 侧要求非空，M7 组装时以配置传入。
        EventDispatcher dispatcher = EventDispatcher.newBuilder("", "")
                .onP2MessageReceiveV1(new ImService.P2MessageReceiveV1Handler() {
                    @Override
                    public void handle(P2MessageReceiveV1 event) throws Exception {
                        IncomingMessage msg = EventTranslator.toIncomingMessage(
                                readEventData(event.getEventReq()), MAPPER);
                        // R11：SDK WS 回调线程同步直调，不切线程
                        opts.onMessage().onMessage(msg, bot);
                    }
                })
                .onP2CardActionTrigger(new P2CardActionTriggerHandler() {
                    @Override
                    public P2CardActionTriggerResponse handle(P2CardActionTrigger event) throws Exception {
                        if (opts.onCardAction() == null) { // TS: if (!onCardAction) return undefined
                            return new P2CardActionTriggerResponse();
                        }
                        CardActionResponse response = opts.onCardAction()
                                .onCardAction(EventTranslator.toCardAction(readEventData(event.getEventReq())));
                        return toSdkResponse(response);
                    }
                })
                .onCustomizedEvent("drive.notice.comment_add_v1", new CustomEventHandler() {
                    @Override
                    public void handle(EventReq req) throws Exception {
                        if (opts.onDocumentComment() == null) { // TS: if (!onDocumentComment) return
                            return;
                        }
                        IncomingDocumentComment comment =
                                EventTranslator.toDocumentComment(readEventData(req));
                        if (comment == null) { // TS: 前置缺失 return（整事件跳过）
                            return;
                        }
                        opts.onDocumentComment().onDocumentComment(comment, bot);
                    }
                })
                .build();

        // 阻塞式 ws.Client.start() 由 daemon 线程承载
        Thread wsThread = Thread.ofPlatform().daemon().name("lark-ws").unstarted(() ->
                new com.lark.oapi.ws.Client.Builder(opts.appId(), opts.appSecret())
                        .eventHandler(dispatcher)
                        .autoReconnect(true)
                        .build()
                        .start());
        wsThread.start();
        return bot;
    }

    /** EventReq body（完整 v2 信封）→ event 子树，即 TS 回调 data。 */
    private static JsonNode readEventData(EventReq req) throws IOException {
        return MAPPER.readTree(req.getBody()).path("event");
    }

    /** CardActionResponse → SDK 卡片回调响应模型；TS undefined → 空响应。 */
    private static P2CardActionTriggerResponse toSdkResponse(CardActionResponse response) {
        P2CardActionTriggerResponse result = new P2CardActionTriggerResponse();
        if (response == null) {
            return result;
        }
        if (response.toast() != null) {
            CallBackToast toast = new CallBackToast();
            toast.setType(response.toast().type().id());
            toast.setContent(response.toast().content());
            result.setToast(toast);
        }
        if (response.card() != null) {
            // TS card: { type: 'raw', data: CardJson }
            CallBackCard card = new CallBackCard();
            card.setType("raw");
            card.setData(MAPPER.convertValue(response.card(), Map.class));
            result.setCard(card);
        }
        return result;
    }
}
