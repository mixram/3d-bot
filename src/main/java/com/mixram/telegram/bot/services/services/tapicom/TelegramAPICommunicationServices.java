package com.mixram.telegram.bot.services.services.tapicom;

import com.fasterxml.jackson.core.type.TypeReference;
import com.google.common.collect.Lists;
import com.mixram.telegram.bot.services.domain.InputMedia;
import com.mixram.telegram.bot.services.domain.entity.*;
import com.mixram.telegram.bot.services.domain.ex.TelegramApiException;
import com.mixram.telegram.bot.services.services.bot.entity.MessageData;
import com.mixram.telegram.bot.services.services.tapicom.entity.SendMessageData;
import com.mixram.telegram.bot.utils.CommonHeadersBuilder;
import com.mixram.telegram.bot.utils.CustomMessageSource;
import com.mixram.telegram.bot.utils.META;
import com.mixram.telegram.bot.utils.databinding.JsonUtil;
import com.mixram.telegram.bot.utils.rest.RestClient;
import lombok.extern.log4j.Log4j2;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Validate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;

import javax.annotation.Nonnull;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

import static com.mixram.telegram.bot.services.services.tapicom.TelegramAPICommunicationComponent.SOMETHING_WRONG_MESSAGE;

/**
 * @author mixram on 2019-04-22.
 * @since 1.3.0.0
 */
@Log4j2
@Service
class TelegramAPICommunicationServices {

    // <editor-fold defaultstate="collapsed" desc="***API elements***">

    private static final String GET_ME_URL = "/getMe";
    private static final String GET_UPDATES_URL = "/getUpdates";
    private static final String SEND_MESSAGE_URL = "/sendMessage";
    private static final String FORWARD_MESSAGE_URL = "/forwardMessage";
    private static final String SEND_MEDIA_GROUP_URL = "/sendMediaGroup";
    private static final String LEAVE_CHAT_URL = "/leaveChat";
    private static final String KICK_CHAT_MEMBER_URL = "/kickChatMember";
    private static final String DELETE_MESSAGE_URL = "/deleteMessage";
    private static final String UNBAN_CHAT_MEMBER_URL = "/unbanChatMember";
    private static final String RESTRICT_CHAT_MEMBER_URL = "/restrictChatMember";
    private static final String CAS_CHECK_URL = "/check";

    private final String botName;
    private final String mainUrlPart;
    private final Integer secondsToBanUser;
    private final Set<Long> adminsPrime;
    private final String CASMainUrl;

    private final RestClient restClient;
    private final CustomMessageSource messageSource;
    private final META meta;

    /**
     * Identifier of the first update to be returned. Must be greater by one than the highest among the identifiers of previously received
     * updates. By default, updates starting with the earliest unconfirmed update are returned. An update is considered confirmed as soon as
     * getUpdates is called with an offset higher than its update_id. The negative offset can be specified to retrieve updates starting from
     * -offset update from the end of the updates queue. All previous updates will forgotten.
     */
    private AtomicLong offset;

    // </editor-fold>

    // <editor-fold defaultstate="collapsed" desc="***Util elements***">

    @Autowired
    TelegramAPICommunicationServices(@Value("${bot.settings.base-url}") String telegramUrl,
                                     @Value("${bot.settings.bot-token}") String botToken,
                                     @Value("${bot.settings.bot-name}") String botName,
                                     @Value("${bot.settings.admins-prime}") String adminsPrime,
                                     @Value("${bot.settings.time-to-ban-user-after-kick}") Integer secondsToBanUser,
                                     @Value("${service.cas.base-url}") String CASMainUrl,
                                     META meta,
                                     CustomMessageSource messageSource,
                                     RestClient restClient) {
        restClient.setAnchorForLog(this.getClass().getSimpleName());
        this.restClient = restClient;
        this.messageSource = messageSource;
        this.meta = meta;

        this.botName = botName;
        this.adminsPrime = JsonUtil.fromJson(adminsPrime, new TypeReference<Set<Long>>() {});
        this.mainUrlPart = telegramUrl + "/bot" + botToken;
        this.secondsToBanUser = secondsToBanUser;
        this.CASMainUrl = CASMainUrl;
    }

    // </editor-fold>


    /**
     * To send messageData to Telegram API.
     *
     * @param update      "update" entity from Telegram API.
     * @param messageData messageData to send to Telegram API.
     *
     * @since 0.1.3.0
     */
    protected void sendMessage(Update update,
                               MessageData messageData) {
        try {
            log.debug("sendMessage => : update={}, messageData={}",
                      () -> update,
                      () -> messageData);

            SendMessageData data = createSendMessageData(update, messageData.isUserResponse());

            doSendMessage(data.getChatId(), data.getMessageId(), messageData);
        } catch (Exception e) {
            log.warn("", e);

            try {
                SendMessageData data = createSendMessageData(update, messageData.isUserResponse());
                MessageData messageDataNew =
                        MessageData.builder()
                                   .toResponse(true)
                                   .message(messageSource.getMessage(SOMETHING_WRONG_MESSAGE,
                                                                     defineLocale(update.getMessage().getUser())))
                                   .userResponse(messageData.isUserResponse())
                                   .build();

                doSendMessage(data.getChatId(), data.getMessageId(), messageDataNew);
            } catch (TelegramApiException e1) {
                log.warn("", e);
            }
        }
    }

    /**
     * To send messageData to concrete chat.
     *
     * @param chatId      chat ID.
     * @param messageData messageData to send to Telegram API.
     *
     * @since 1.4.1.0
     */
    protected void sendMessageToChat(Long chatId,
                                     MessageData messageData) {
        try {
            log.debug("sendMessageToChat => : chatId={}, messageData={}",
                      () -> chatId,
                      () -> messageData);

            doSendMessage(chatId, null, messageData);
        } catch (Exception e) {
            log.warn("sendMessageToChat ==> ERROR!", e);
        }
    }

    /**
     * To send messageData to Telegram API for bot admin.
     *
     * @param message messageData to send to Telegram API.
     *
     * @since 0.1.3.0
     */
    protected void sendMessageToAdmin(MessageData message) {
        try {
            log.debug("sendMessageToAdmin => : messageData={}", () -> message);

            adminsPrime.forEach(id -> doSendMessage(id, null, message));

            if (message.isLeaveChat()) {
                log.warn("Can not 'leave chat' from admin messages sending logic!");
            }
        } catch (Exception e) {
            log.warn("", e);
        }
    }

    /**
     * To get all updates from Telegram API.
     *
     * @return a list of updates (may be empty) or exception.
     *
     * @since 0.1.3.0
     */
    protected List<Update> getUpdates() {
        List<Update> result;

        String url = mainUrlPart + GET_UPDATES_URL;
        HttpHeaders headers = CommonHeadersBuilder.newInstance()
                                                  .json()
                                                  .build();
        Map<String, String> params = new HashMap<>();
        if (offset != null) {
            params.put("offset", String.valueOf(offset.addAndGet(1)));
        }

        try {
            UpdateResponse updatesHolder = restClient.get(url, params, headers.toSingleValueMap(),
                                                          UpdateResponse.class);
            Validate.notNull(updatesHolder, "Empty answer!");
            Validate.isTrue(updatesHolder.getResult(), "An error in process of updates getting! %s", updatesHolder);

            List<Update> updates = Optional.ofNullable(updatesHolder.getData()).orElse(
                    Lists.newArrayListWithExpectedSize(0));
            offset = updates.stream()
                            .map(Update :: getUpdateId)
                            .max(Comparator.naturalOrder())
                            .map(AtomicLong :: new)
                            .orElse(null);

            result = updatesHolder.getData();
        } catch (Exception e) {
            log.warn("Exception in process of updates receiving!", e);

            result = Lists.newArrayListWithExpectedSize(0);
        }

        return result;
    }

    /**
     * To assert bot`s data.
     *
     * @since 0.1.3.0
     */
    protected void assertBot() {
        String url = mainUrlPart + GET_ME_URL;
        HttpHeaders headers = CommonHeadersBuilder.newInstance()
                                                  .json()
                                                  .build();

        WhoAmI whoAmI = restClient.get(url, headers.toSingleValueMap(), WhoAmI.class);

        Validate.isTrue(whoAmI != null && whoAmI.getResult() != null, "Empty answer!");
        Validate.isTrue(whoAmI.getResult(), "Error in service! %s", whoAmI);
        Validate.notNull(whoAmI.getResultData(), "No data about bot!");
        Validate.isTrue(botName.equals(whoAmI.getResultData().getUsername()), "Unexpected bot`s name: '%s'!",
                        whoAmI.getResultData().getUsername());

        log.info("BOT: {}", () -> whoAmI);
    }

    /**
     * To get admins IDs.
     *
     * @return IDs.
     *
     * @since 1.3.0.0
     */
    protected Set<Long> getAdmins() {
        return adminsPrime;
    }

    /**
     * To kick user from the chat.
     *
     * @param chatId chat ID.
     * @param userId user ID.
     *
     * @since 1.7.0.0
     */
    protected void kickUserFromChat(String chatId,
                                    String userId) {
        try {
            SendMessage sendMessage =
                    SendMessage.builder()
                               .chatId(chatId)
                               .userId(userId)
                               .untilDate(LocalDateTime.now()
                                                       .plusSeconds(secondsToBanUser)
                                                       .atZone(ZoneId.systemDefault()).toInstant()
                                                       .getEpochSecond())
                               .build();

            String url = mainUrlPart + KICK_CHAT_MEMBER_URL;
            HttpHeaders headers = CommonHeadersBuilder.newInstance()
                                                      .json()
                                                      .build();
            log.debug("kickUserFromChat => message={}", () -> sendMessage);

            Object answerResponse =
                    restClient.post(url, headers.toSingleValueMap(), sendMessage, Object.class);
            Validate.notNull(answerResponse, "Empty message!");

            log.debug("kickUserFromChat ==> answer on message: {}", () -> answerResponse);
        } catch (Exception e) {
            log.warn("", e);
        }
    }

    /**
     * To unban user in chat.
     *
     * @param chatId chat ID.
     * @param userId user ID.
     *
     * @since 1.7.0.0
     */
    protected void unbanUserInChat(String chatId,
                                   String userId) {
        try {
            SendMessage sendMessage =
                    SendMessage.builder()
                               .chatId(chatId)
                               .userId(userId)
                               .build();

            String url = mainUrlPart + UNBAN_CHAT_MEMBER_URL;
            HttpHeaders headers = CommonHeadersBuilder.newInstance()
                                                      .json()
                                                      .build();
            log.debug("unbanUserInChat => : message={}", () -> sendMessage);

            Object answerResponse =
                    restClient.post(url, headers.toSingleValueMap(), sendMessage, Object.class);
            Validate.notNull(answerResponse, "Empty message!");

            log.debug("unbanUserInChat ==> answer on message: {}", () -> answerResponse);
        } catch (Exception e) {
            log.warn("", e);
        }
    }

    /**
     * To delete message from the chat.
     *
     * @param chatId    chat ID.
     * @param messageId message ID.
     *
     * @since 1.7.0.0
     */
    protected void removeMessageFromChat(String chatId,
                                         String messageId) {
        try {
            SendMessage sendMessage = SendMessage.builder()
                                                 .chatId(chatId)
                                                 .messageId(messageId)
                                                 .build();

            String url = mainUrlPart + DELETE_MESSAGE_URL;
            HttpHeaders headers = CommonHeadersBuilder.newInstance()
                                                      .json()
                                                      .build();
            log.debug("removeMessageFromChat => message={}", () -> sendMessage);

            Object answerResponse =
                    restClient.post(url, headers.toSingleValueMap(), sendMessage, Object.class);
            Validate.notNull(answerResponse, "Empty message!");

            log.debug("removeMessageFromChat ==> answer on message: {}", () -> answerResponse);
        } catch (Exception e) {
            log.warn("", e);
        }
    }

    /**
     * To restrict user in the chat.
     *
     * @param chatId chat ID.
     * @param userId user ID.
     *
     * @since 1.8.5.0
     */
    protected void manageRightsChatMember(String chatId,
                                          String userId,
                                          boolean restrict) {
        try {
            SendMessage sendMessage =
                    SendMessage.builder()
                               .chatId(chatId)
                               .userId(userId)
                               .permissions(restrict ? ChatPermissions.RESTRICTED_ALL() : ChatPermissions.GRANTED_ALL())
                               .build();

            String url = mainUrlPart + RESTRICT_CHAT_MEMBER_URL;
            HttpHeaders headers = CommonHeadersBuilder.newInstance()
                                                      .json()
                                                      .build();
            log.debug("restrictChatMember => message={}", () -> sendMessage);

            Object answerResponse = restClient.post(url, headers.toSingleValueMap(), sendMessage, Object.class);
            Validate.notNull(answerResponse, "Empty message!");

            log.debug("restrictChatMember ==> answer on message: {}", () -> answerResponse);
        } catch (Exception e) {
            log.warn("", e);
        }
    }

    /**
     * To get data from <a href="https://cas.chat">CAS service</a>.
     *
     * @param id user ID.
     *
     * @return CAS data.
     *
     * @since 1.8.5.0
     */
    @Nonnull
    protected CASData checkCAS(@Nonnull Long id) {
        String url = CASMainUrl + CAS_CHECK_URL;
        Map<String, String> params = new HashMap<>(1);
        params.put("user_id", String.valueOf(id));

        CASData response = restClient.get(url, params, null, CASData.class);
        Validate.notNull(response, "Empty answer!");

        return response;
    }

    /**
     * To forward a message.
     *
     * @param baseChatId   chat, where message originally appeared.
     * @param targetChatId chat, where message should be forwarded.
     * @param messageId    message ID, that need to be forwarded.
     *
     * @since 1.8.8.0
     */
    protected void forwardMessage(@Nonnull String baseChatId,
                                  @Nonnull String targetChatId,
                                  @Nonnull String messageId) {
        try {
            String url = mainUrlPart + FORWARD_MESSAGE_URL;
            HttpHeaders headers = CommonHeadersBuilder.newInstance()
                                                      .json()
                                                      .build();
            SendMessage sendMessage =
                    SendMessage.builder()
                               .chatId(targetChatId)
                               .fromChatId(baseChatId)
                               .messageId(messageId)
                               .disableNotification(true)
                               .build();

            AnswerResponse<Message> answerResponse =
                    restClient.post(url, headers.toSingleValueMap(), sendMessage,
                                    new ParameterizedTypeReference<AnswerResponse<Message>>() {});
            Validate.notNull(answerResponse, "Empty message!");
            Validate.isTrue(answerResponse.getResult(), "An error in process of message sending! %s", answerResponse);
        } catch (Exception e) {
            log.warn("", e);
        }
    }

    /**
     * To send a media group.
     *
     * @param chatId chat ID.
     * @param media  a list with media to send.
     *
     * @since 1.8.8.0
     */
    protected void sendMediaGroup(@Nonnull String chatId,
                                  @Nonnull List<InputMedia> media) {
        try {
            String url = mainUrlPart + SEND_MEDIA_GROUP_URL;
            HttpHeaders headers = CommonHeadersBuilder.newInstance()
                                                      .json()
                                                      .build();
            SendMessage sendMessage =
                    SendMessage.builder()
                               .chatId(chatId)
                               .media(media)
                               .disableNotification(true)
                               .build();

            AnswerResponse<List<Message>> answerResponse =
                    restClient.post(url, headers.toSingleValueMap(), sendMessage,
                                    new ParameterizedTypeReference<AnswerResponse<List<Message>>>() {});
            Validate.notNull(answerResponse, "Empty message!");
            Validate.isTrue(answerResponse.getResult(), "An error in process of message sending! %s", answerResponse);
        } catch (Exception e) {
            log.warn("", e);
        }
    }


    // <editor-fold defaultstate="collapsed" desc="***Private elements***">

    /**
     * @since 1.4.1.0
     */
    private Locale defineLocale(User user) {
        String languageCode = user.getLanguageCode();

        return StringUtils.isNotBlank(languageCode) ? new Locale(languageCode) : META.DEFAULT_LOCALE;
    }

    /**
     * @since 1.4.0.0
     */
    private void leaveChat(String chatId) {
        SendMessage.SendMessageBuilder builder = SendMessage.builder()
                                                            .chatId(chatId);

        String url = mainUrlPart + LEAVE_CHAT_URL;
        HttpHeaders headers = CommonHeadersBuilder.newInstance()
                                                  .json()
                                                  .build();

        AnswerResponse<Boolean> answerResponse =
                restClient.post(url, headers.toSingleValueMap(), builder.build(),
                                new ParameterizedTypeReference<AnswerResponse<Boolean>>() {});
        Validate.notNull(answerResponse, "Empty message!");
        Validate.isTrue(answerResponse.getResult(), "An error in process of chat leaving! %s", answerResponse);
    }

    /**
     * @since 1.0.0.0
     */
    private SendMessageData createSendMessageData(Update update,
                                                  boolean userResponse) {
        Message mess = update.getMessage();
        if (mess == null) {
            mess = update.getCallbackQuery().getMessage();
        }

        Long messageId = mess.getMessageId();
        Long chatId;
        if (userResponse) {
            chatId = mess.getUser()
                         .getId();
        } else {
            chatId = mess.getChat()
                         .getChatId();
        }

        return new SendMessageData(chatId, messageId);
    }

    /**
     * @since 0.1.3.0
     */
    private void doSendMessage(Long chatId,
                               Long messageId,
                               MessageData message) {
        SendMessage sendMessage = SendMessage.builder()
                                             .chatId(chatId.toString())
                                             .text(message.getMessage())
                                             .replyMarkup(message.getReplyMarkup())
                                             .parseMode("HTML")
                                             .disableWebPagePreview(!message.isShowUrlPreview())
                                             .disableNotification(false)
                                             .build();
        if (message.isToResponse()) {
            sendMessage
                    .setReplyToMessageId(messageId);
        }

        String url = mainUrlPart + SEND_MESSAGE_URL;
        HttpHeaders headers = CommonHeadersBuilder.newInstance()
                                                  .json()
                                                  .build();

        log.debug("doSendMessage => message={}", () -> sendMessage);

        AnswerResponse<Message> answerResponse =
                restClient.post(url, headers.toSingleValueMap(), sendMessage,
                                new ParameterizedTypeReference<AnswerResponse<Message>>() {});
        Validate.notNull(answerResponse, "Empty message!");
        Validate.isTrue(answerResponse.getResult(), "An error in process of message sending! %s", answerResponse);

        doPostSendMessage(chatId, message, answerResponse);

        log.debug("doSendMessage => answer on message: {}", () -> answerResponse);
    }

    private void doPostSendMessage(Long chatId,
                                   MessageData messageData,
                                   AnswerResponse<Message> answerResponse) {
        Long messageId = answerResponse.getData().getMessageId();
        if (messageData.getDoIfAntiBot() != null) {
            try {
                messageData.getDoIfAntiBot().accept(messageId);
            } catch (Exception e) {
                log.warn("Anti-bot post routine error!", e);
            }
        }
        if (messageData.getDoIfLazyAction() != null) {
            try {
                messageData.getDoIfLazyAction().forEach(la -> la.accept(messageId));
            } catch (Exception e) {
                log.warn("Lazy-message post routine error!", e);
            }
        }
        if (messageData.isLeaveChat()) {
            try {
                leaveChat(chatId.toString());
            } catch (Exception e) {
                log.warn("Chat leaving error!", e);
            }
        }
    }

    // </editor-fold>
}
