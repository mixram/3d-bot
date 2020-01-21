package com.mixram.telegram.bot.services.services.antibot;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.mixram.telegram.bot.config.cache.RedisTemplateHelper;
import com.mixram.telegram.bot.services.domain.entity.CallbackQuery;
import com.mixram.telegram.bot.services.domain.entity.InlineKeyboard;
import com.mixram.telegram.bot.services.domain.entity.User;
import com.mixram.telegram.bot.services.services.bot.entity.MessageData;
import com.mixram.telegram.bot.services.services.bot.entity.NewMemberTempData;
import com.mixram.telegram.bot.services.services.tapicom.TelegramAPICommunicationComponent;
import com.mixram.telegram.bot.utils.META;
import com.mixram.telegram.bot.utils.databinding.JsonUtil;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.log4j.Log4j2;
import org.apache.commons.lang3.Validate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * @author mixram on 2020-01-19.
 * @since 1.7.0.0
 */
@Log4j2
@Component
public class AntiBotImpl implements AntiBot {

    // <editor-fold defaultstate="collapsed" desc="***API elements***">

    private static final String NEW_MEMBERS_TEMP_DATA = "new_members_temp_data";
    private static final String ID_SEPARATOR = "_";

    private final RedisTemplateHelper redisTemplateHelper;
    private final TelegramAPICommunicationComponent telegramAPICommunicationComponent;


    @Data
    @AllArgsConstructor
    private class NewMessageAdder implements Consumer<Long> {

        private Long userId;
        private Long chatId;

        @Override
        public void accept(Long aLong) {
            addMessageToExistingDeleteList(userId, chatId, aLong);
        }

    }

    // </editor-fold>

    // <editor-fold defaultstate="collapsed" desc="***Util elements***">

    @Autowired
    public AntiBotImpl(RedisTemplateHelper redisTemplateHelper,
                       TelegramAPICommunicationComponent telegramAPICommunicationComponent) {
        this.redisTemplateHelper = redisTemplateHelper;
        this.telegramAPICommunicationComponent = telegramAPICommunicationComponent;
    }

    // </editor-fold>


    @Override
    public MessageData checkUser(List<User> newChatMembers,
                                 Long chatId,
                                 Long userIncomeMessageId,
                                 Locale locale) {
        Validate.notEmpty(newChatMembers, "A list of members is not specified or empty!");
        Validate.notNull(locale, "Locale is not specified!");

        //TODO: need to rebuild in order to be able to check more then one new user at the same time
        Validate.isTrue(newChatMembers.size() == 1, "Can not check more then one user at the same time!");

        Map<String, NewMemberTempData> membersData = getMembersTempDataFromRedis();
        List<MessageData> messages = Lists.newArrayListWithExpectedSize(newChatMembers.size());
        newChatMembers.forEach(u -> {
            NewMemberTempData newMember =
                    NewMemberTempData.builder()
                                     .user(u)
                                     .added(LocalDateTime.now())
                                     .messagesToDelete(Lists.newArrayList())
                                     .userIncomeMessageId(userIncomeMessageId)
                                     .build();
            membersData.put(prepareId(chatId, u.getId()), newMember);
            messages.add(MessageData.builder()
                                    .message(defineRandomMessage(u))
                                    .replyMarkup(defineRandomKey())
                                    .doIfAntiBot(new NewMessageAdder(u.getId(), chatId))
                                    .build());
        });

        storeNewMembersTempDataToRedis(membersData);

        return messages.get(0);
    }

    @Override
    public void checkUsers() {
        LocalDateTime checkTime = LocalDateTime.now().minusMinutes(1);
        Map<String, NewMemberTempData> membersTempDataFromRedis = getMembersTempDataFromRedis();

        List<String> removeFromTemp = Lists.newArrayList();
        membersTempDataFromRedis.forEach((key, value) -> {
            LocalDateTime added = value.getAdded();
            if (added.isBefore(checkTime)) {
                String[] dataArray = key.split(ID_SEPARATOR);
                String chatId = dataArray[0];
                String userId = dataArray[1];

                telegramAPICommunicationComponent.kickUserFromGroup(chatId, userId);
                log.info("User {} has been kicked from chat {}!",
                         () -> userId,
                         () -> chatId);

                telegramAPICommunicationComponent.unbanUserInChat(chatId, userId);
                log.info("User {} has been unbaned in chat {}!",
                         () -> userId,
                         () -> chatId);

                telegramAPICommunicationComponent.removeMessageFromChat(chatId,
                                                                        String.valueOf(value.getUserIncomeMessageId()));
                value.getMessagesToDelete().forEach(
                        m -> telegramAPICommunicationComponent.removeMessageFromChat(chatId, String.valueOf(m)));

                MessageData messageData = MessageData.builder()
                                                     .toAdmin(true)
                                                     .message(String.format("<b>User has been kicked from chat %s!</b>\n%s",
                                                                            userId,
                                                                            JsonUtil.toPrettyJson(value)))
                                                     .build();
                telegramAPICommunicationComponent.sendMessageToAdmin(messageData);

                removeFromTemp.add(key);
            }
        });

        if (!CollectionUtils.isEmpty(removeFromTemp)) {
            removeFromTemp.forEach(membersTempDataFromRedis :: remove);
            storeNewMembersTempDataToRedis(membersTempDataFromRedis);
        }
    }

    @Override
    public void proceedCallBack(CallbackQuery callbackQuery) {
        Validate.notNull(callbackQuery, "Callback is not specified!");
        Validate.isTrue(META.NOT_A_BOT_TEXT.equalsIgnoreCase(callbackQuery.getData()), "The callback is not for AntiBot!");

        //TODO: need to rebuild logic when "not_a_bot_text" will not be the one

        User user = callbackQuery.getUser();
        Validate.notNull(user, "User is not specified!");

        Long chatId = callbackQuery.getMessage().getChat().getChatId();
        String key = prepareId(chatId, user.getId());
        Map<String, NewMemberTempData> membersTempDataFromRedis = getMembersTempDataFromRedis();
        NewMemberTempData newMemberTempData = membersTempDataFromRedis.get(key);
        if (newMemberTempData == null) {
            throw new UnsupportedOperationException(String.format("User %s not found!", key));
        }

        newMemberTempData.getMessagesToDelete().forEach(
                message -> telegramAPICommunicationComponent.removeMessageFromChat(String.valueOf(chatId),
                                                                                   String.valueOf(message)));

        membersTempDataFromRedis.remove(key);

        storeNewMembersTempDataToRedis(membersTempDataFromRedis);
    }

    // <editor-fold defaultstate="collapsed" desc="***Private elements***">

    /**
     * @since 1.7.0.0
     */
    private void addMessageToExistingDeleteList(Long userId,
                                                Long chatId,
                                                Long messageId) {
        String key = prepareId(chatId, userId);
        Map<String, NewMemberTempData> membersData = getMembersTempDataFromRedis();
        NewMemberTempData data = membersData.get(key);
        if (data == null) {
            log.warn("No data in 'MembersTempDataFromRedis' by key={}", () -> key);

            return;
        }

        data.getMessagesToDelete().add(messageId);

        storeNewMembersTempDataToRedis(membersData);
    }

    /**
     * @since 1.7.0.0
     */
    private void storeNewMembersTempDataToRedis(Map<String, NewMemberTempData> data) {
        redisTemplateHelper.storeNewMembersTempDataToRedis(data, NEW_MEMBERS_TEMP_DATA);
    }

    /**
     * @since 1.7.0.0
     */
    private Map<String, NewMemberTempData> getMembersTempDataFromRedis() {
        Map<String, NewMemberTempData> membersData = redisTemplateHelper.getMembersTempDataFromRedis(NEW_MEMBERS_TEMP_DATA);

        return membersData == null ? Maps.newHashMap() : membersData;
    }

    /**
     * @since 1.7.0.0
     */
    private InlineKeyboard defineRandomKey() {
        List<List<InlineKeyboard.Key>> keyboard = new ArrayList<>(1);
        keyboard.add(Lists.newArrayList(new InlineKeyboard.Key(META.NOT_A_BOT_TEXT, "Я не бот 🤟")));

        return InlineKeyboard.builder()
                             .inlineKeyboard(keyboard)
                             .build();
    }

    /**
     * @since 1.7.0.0
     */
    private String defineRandomMessage(User user) {
        return String.format("<a href=\"tg://user?id=%s\">%s</a>, якщо Ви людина - натисніть на кнопку 😊", user.getId(),
                             user.getFirstName());
    }

    /**
     * @since 1.7.0.0
     */
    private List<Long> createDeleteList(List<User> newChatMembers,
                                        Long userIncomeMessageId) {
        return newChatMembers.size() == 1 ? Lists.newArrayList(userIncomeMessageId) : Lists.newArrayList();
    }

    /**
     * @since 1.7.0.0
     */
    private String prepareId(Long chatId,
                             Long id) {
        return chatId + ID_SEPARATOR + id;
    }

    // </editor-fold>

}
