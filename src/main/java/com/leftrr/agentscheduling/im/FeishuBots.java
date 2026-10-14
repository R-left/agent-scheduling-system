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
import com.leftrr.agentscheduling.dto.im.CardActionResponse;
import com.leftrr.agentscheduling.dto.im.IncomingDocumentComment;
import com.leftrr.agentscheduling.dto.im.IncomingMessage;
import java.io.IOException;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * WS 装配：EventDispatcher 接三种 handler，daemon 线程跑 ws.Client。
 * 不做单测，端到端冒烟验证。
 *
 * <p>SDK 口径（oapi-sdk 2.4.7）：
 * 消息用 {@code ImService.P2MessageReceiveV1Handler}（抽象类，只能匿名子类，不能 lambda）；
 * 卡片回调是 {@code event.cardcallback.P2CardActionTriggerHandler}，
 * 接 {@code EventDispatcher.Builder.onP2CardActionTrigger}；
 * 文档评论在 2.4.7 没有 typed handler，只能走 onCustomizedEvent 手工解析 EventReq。
 *
 * <p>EventReq body 就是完整 v2 信封（doWithoutValidation 把原始字节原样挂上去的），
 * 取 event 子树解析即可。handler 在 SDK WS 回调线程同步直调，不切线程，
 * 线程口径由编排层自己定。
 */
public final class FeishuBots {

    private static final Logger LOGGER = LoggerFactory.getLogger(FeishuBots.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private FeishuBots() {
    }

    /** 装配 dispatcher、启动 WS daemon 线程，立即返回 bot。 */
    public static Bot start(BotOptions opts) {
        Bot bot = new LarkBot(new OapiLarkApi(opts.appId(), opts.appSecret()));

        // TODO: verificationToken/encryptKey 传空串即无鉴权形态；若 SDK 侧要求非空，组装时以配置传入。
        EventDispatcher dispatcher = EventDispatcher.newBuilder("", "")
                .onP2MessageReceiveV1(new ImService.P2MessageReceiveV1Handler() {
                    @Override
                    public void handle(P2MessageReceiveV1 event) throws Exception {
                        IncomingMessage msg = EventTranslator.toIncomingMessage(
                                readEventData(event.getEventReq()), MAPPER);
                        // 收到事件即打点：区分「事件没推送过来」和「处理链路出错」
                        LOGGER.info("[消息] chat={} thread={} senderType={}",
                                msg.chatId(), msg.threadId(), msg.senderType());
                        opts.onMessage().onMessage(msg, bot);
                    }
                })
                .onP2CardActionTrigger(new P2CardActionTriggerHandler() {
                    @Override
                    public P2CardActionTriggerResponse handle(P2CardActionTrigger event) throws Exception {
                        if (opts.onCardAction() == null) {
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
                        if (opts.onDocumentComment() == null) {
                            return;
                        }
                        IncomingDocumentComment comment =
                                EventTranslator.toDocumentComment(readEventData(req));
                        if (comment == null) {
                            return;
                        }
                        opts.onDocumentComment().onDocumentComment(comment, bot);
                    }
                })
                .build();

        Thread wsThread = Thread.ofPlatform().daemon().name("lark-ws").unstarted(() ->
                new com.lark.oapi.ws.Client.Builder(opts.appId(), opts.appSecret())
                        .eventHandler(dispatcher)
                        .autoReconnect(true)
                        .build()
                        .start());
        wsThread.start();
        return bot;
    }

    /** EventReq body（完整 v2 信封）→ event 子树。 */
    private static JsonNode readEventData(EventReq req) throws IOException {
        return MAPPER.readTree(req.getBody()).path("event");
    }

    /** CardActionResponse → SDK 卡片回调响应模型；null → 空响应。 */
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
            CallBackCard card = new CallBackCard();
            card.setType("raw");
            card.setData(MAPPER.convertValue(response.card(), Map.class));
            result.setCard(card);
        }
        return result;
    }
}
