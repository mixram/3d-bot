package com.mixram.telegram.bot.services.services.bot;


import com.fasterxml.jackson.core.type.TypeReference;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.mixram.telegram.bot.services.domain.entity.*;
import com.mixram.telegram.bot.services.domain.enums.Command;
import com.mixram.telegram.bot.services.domain.enums.PlasticType;
import com.mixram.telegram.bot.services.domain.enums.Shop3D;
import com.mixram.telegram.bot.services.domain.enums.WorkType;
import com.mixram.telegram.bot.services.modules.Module3DPlasticDataSearcher;
import com.mixram.telegram.bot.services.services.bot.entity.MessageData;
import com.mixram.telegram.bot.services.services.bot.enums.PlasticPresenceState;
import com.mixram.telegram.bot.services.services.tapicom.TelegramAPICommunicationComponent;
import com.mixram.telegram.bot.utils.AsyncHelper;
import com.mixram.telegram.bot.utils.CustomMessageSource;
import com.mixram.telegram.bot.utils.META;
import com.mixram.telegram.bot.utils.databinding.JsonUtil;
import com.mixram.telegram.bot.utils.htmlparser.ParseData;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Validate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import java.util.*;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * @author mixram on 2019-04-10.
 * @since 1.4.1.0
 */
@Log4j2
@Component
public class Bot3DComponentImpl implements Bot3DComponent {

    // <editor-fold defaultstate="collapsed" desc="***API elements***">

    private static final String PRIVATE_CHAT_NAME = "private";
    private static final String GROUP_CHAT_NAME = "group";
    private static final String SUPER_GROUP_CHAT_NAME = "supergroup";

    private static final String SALES_PATTERN_STRING = "^/SALES_.*";
    private static final String OTHER_COMMANDS_PATTERN_STRING = "^/START.*|^/INFO.*";
    private static final Pattern SALES_PATTERN = Pattern.compile(SALES_PATTERN_STRING);
    private static final Pattern OTHER_PATTERN = Pattern.compile(OTHER_COMMANDS_PATTERN_STRING);

    private static final String NO_WORK_WITH_SHOP = "telegram.bot.message.no-work-with-shop";
    private static final String NO_DATA_FOR_SHOP = "telegram.bot.message.no-data-for-shop";
    private static final String NO_DISCOUNTS = "telegram.bot.message.no-discounts";
    private static final List<String> MISUNDERSTANDING_MESSAGES = ImmutableList.of(
            "telegram.bot.message.misunderstanding-1",
            "telegram.bot.message..misunderstanding-2",
            "telegram.bot.message..misunderstanding-3",
            "telegram.bot.message..misunderstanding-4"
    );
    private static final String NO_PRIVATE_CHAT_MESSAGE = "telegram.bot.message.no-private-chat";
    private static final String NO_GROUP_CHAT_MESSAGE = "telegram.bot.message.no-group-chat";
    private static final String CONCRETE_GROUP_MESSAGE = "telegram.bot.message.concrete-group";
    private static final String START_ANSWER_MESSAGE = "telegram.bot.message.start-answer";
    private static final String INFO_ANSWER_MESSAGE = "telegram.bot.message.info-answer";
    private static final String INFO_ANSWER_ALL_MESSAGE = "telegram.bot.message.info-answer.all";
    private static final String USER_CALL_MESSAGE = "telegram.bot.message.user-call";
    public static final String SHOP_MESSAGE_PART_MESSAGE = "telegram.bot.message.shop-message.part";
    private static final String SHORT_DISCOUNT_PART_MESSAGE = "telegram.bot.message.discount.short.part";
    private static final String FULL_DISCOUNT_PART_MESSAGE = "telegram.bot.message.discount.full.part";
    private static final String FULL_DISCOUNT_OTHER_MESSAGE = "telegram.bot.message.discount.full.other";
    private static final String SHORT_MESSAGE_LEGEND_MESSAGE = "telegram.bot.message.short-message-legend";
    private static final String NEW_CHAT_MEMBERS_HALLOW_MESSAGE = "telegram.bot.message.new-chat-members-hallow";

    private final Integer maxQuantity;
    private final Random random;
    private final WorkType workType;
    private final List<Long> allowedGroups;
    private final String adminEmail;
    private final String fleaMarket;
    private final String pinnedMessage;

    private final Module3DPlasticDataSearcher searcher;
    private final TelegramAPICommunicationComponent communicationComponent;
    private final AsyncHelper asyncHelper;
    private final CustomMessageSource messageSource;


    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    private static class CommandHolder {

        /**
         * Command to execute.
         */
        private Command command;
        /**
         * true - need full message content, false - need short message content.
         */
        private boolean full;

        @Override
        public String toString() {
            return JsonUtil.toJson(this);
        }
    }

    // </editor-fold>

    // <editor-fold defaultstate="collapsed" desc="***Util elements***">

    @Autowired
    public Bot3DComponentImpl(@Value("${bot.settings.other.max-quantity-for-full-view}") Integer maxQuantity,
                              @Value("${bot.settings.work-with}") WorkType workType,
                              @Value("${bot.settings.work-with-groups}") String allowedGroups,
                              @Value("${bot.settings.admin-email}") String adminEmail,
                              @Value("${bot.settings.flea-market}") String fleaMarket,
                              @Value("${bot.settings.pinned-message}") String pinnedMessage,
                              @Qualifier("discountsOn3DPlasticDataCacheComponent") Module3DPlasticDataSearcher searcher,
                              TelegramAPICommunicationComponent communicationComponent,
                              AsyncHelper asyncHelper,
                              CustomMessageSource messageSource) {
        this.maxQuantity = maxQuantity;
        this.workType = workType;
        this.allowedGroups = JsonUtil.fromJson(allowedGroups, new TypeReference<List<Long>>() {});
        this.adminEmail = adminEmail;
        this.fleaMarket = fleaMarket;
        this.pinnedMessage = pinnedMessage;

        this.searcher = searcher;
        this.communicationComponent = communicationComponent;
        this.asyncHelper = asyncHelper;
        this.messageSource = messageSource;

        this.random = new Random();
    }


    // </editor-fold>

    @Override
    public MessageData proceedUpdate(Update update) {
        Validate.notNull(update, "Update is not specified!");

        Message message = update.getMessage();

        Locale locale = message.getUser() == null || message.getUser().getLanguageCode() == null ? META.DEFAULT_LOCALE :
                        new Locale(message.getUser().getLanguageCode());

        MessageData workCheckMessage = checkMayWorkWith(message, locale);
        if (workCheckMessage != null) {
            return workCheckMessage;
        }

        MessageData newChatMembersMessage = checkNewChatMembers(message, META.DEFAULT_LOCALE);
        if (newChatMembersMessage != null) {
            return newChatMembersMessage;
        }

        if (noNeedToAnswer(message)) {
            return null;
        }

        final CommandHolder command;
        try {
            command = defineCommand(message.getText());
        } catch (Exception e) {
            log.warn(String.format("Error in command defining: %s!", message.getText()), e);

            return prepareMisunderstandingMessage(locale);
        }

        infoAdmin(update);

        return prepareAnswerWithCommand(command.getCommand(), command.isFull(), message.getChat().getType(), locale);
    }

    /**
     * @since 1.4.1.0
     */
    public String prepareMessageForShopToSendString(Data3DPlastic plastic,
                                                    Shop3D shop,
                                                    Command command,
                                                    boolean full,
                                                    boolean onlyDiscounts,
                                                    Locale locale) {
        return plastic == null || CollectionUtils.isEmpty(plastic.getData()) ?
               messageSource.getMessage(NO_DATA_FOR_SHOP, locale) :
               doPrepareMessageToSendString(command, full, onlyDiscounts, plastic, shop, locale);
    }

    /**
     * @since 1.4.1.0
     */
    public String prepareMessageForShopsToSendString(boolean full,
                                                     boolean onlyDiscounts,
                                                     Locale locale) {
        StringBuilder builder = new StringBuilder();
        for (Shop3D shop : Shop3D.values()) {
            Data3DPlastic plastic = searcher.search(shop);
            String messageToSendStringTemp =
                    prepareMessageForShopToSendString(plastic, shop, Command.getByShop(shop), full, onlyDiscounts, locale);

            builder.append(messageSource.getMessage(SHOP_MESSAGE_PART_MESSAGE, locale, shop.getName(),
                                                    messageToSendStringTemp));
        }

        builder.append(messageSource.getMessage(SHORT_MESSAGE_LEGEND_MESSAGE, locale,
                                                getDiscountText(PlasticPresenceState.DISCOUNT),
                                                getDiscountText(PlasticPresenceState.IN_STOCK),
                                                getDiscountText(PlasticPresenceState.NOT_IN_STOCK)));

        return builder.toString();
    }


    // <editor-fold defaultstate="collapsed" desc="***Private elements***">

    /**
     * @since 1.4.1.0
     */
    private MessageData checkNewChatMembers(Message message,
                                            Locale locale) {
        List<User> newChatMembers =
                Optional.ofNullable(message.getNewChatMembers()).orElse(Lists.newArrayListWithExpectedSize(0)).stream()
                        .filter(u -> !u.getIsBot())
                        .collect(Collectors.toList());
        if (CollectionUtils.isEmpty(newChatMembers)) {
            return null;
        }

        StringBuilder builder = new StringBuilder();
        newChatMembers.forEach(u -> builder.append("@").append(u.getUsername()).append(", "));

        return MessageData.builder()
                          .message(messageSource.getMessage(NEW_CHAT_MEMBERS_HALLOW_MESSAGE, locale, builder.toString(),
                                                            fleaMarket, pinnedMessage))
                          .build();
    }

    /**
     * @since 1.4.0.0
     */
    private MessageData checkMayWorkWith(Message message,
                                         Locale locale) {
        Chat chat = message.getChat();
        switch (workType) {
            case P:
                return isPrivate(chat.getType()) ? null : prepareNoPrivateChatMessage(locale);
            case G:
                if (isGroup(chat.getType())) {
                    if (allowedGroups.contains(chat.getChatId())) {
                        return null;
                    }
                    return prepareConcreteGroupChatMessage(locale);
                } else {
                    return prepareNoGroupChatMessage(locale);
                }
            case B:
                if (isGroup(chat.getType())) {
                    if (allowedGroups.contains(chat.getChatId())) {
                        return null;
                    }
                    return prepareConcreteGroupChatMessage(locale);
                } else {
                    return null;
                }
            default:
                throw new UnsupportedOperationException(String.format("Unexpected work type: '%s'!", workType));
        }
    }

    /**
     * @since 1.4.0.0
     */
    private boolean isPrivate(String type) {
        return PRIVATE_CHAT_NAME.equalsIgnoreCase(type);
    }

    /**
     * @since 1.4.0.0
     */
    private boolean isGroup(String type) {
        return GROUP_CHAT_NAME.equalsIgnoreCase(type) || SUPER_GROUP_CHAT_NAME.equalsIgnoreCase(type);
    }

    /**
     * @since 1.4.0.0
     */
    private MessageData prepareNoPrivateChatMessage(Locale locale) {
        return MessageData.builder()
                          .message(messageSource.getMessage(NO_PRIVATE_CHAT_MESSAGE, locale))
                          .toResponse(false)
                          .toAdmin(false)
                          .leaveChat(true)
                          .build();
    }

    /**
     * @since 1.4.0.0
     */
    private MessageData prepareNoGroupChatMessage(Locale locale) {
        return MessageData.builder()
                          .message(messageSource.getMessage(NO_GROUP_CHAT_MESSAGE, locale))
                          .toResponse(false)
                          .toAdmin(false)
                          .leaveChat(true)
                          .build();
    }

    /**
     * @since 1.4.0.0
     */
    private MessageData prepareConcreteGroupChatMessage(Locale locale) {
        return MessageData.builder()
                          .message(messageSource.getMessage(CONCRETE_GROUP_MESSAGE, locale, adminEmail))
                          .toResponse(false)
                          .toAdmin(false)
                          .leaveChat(true)
                          .build();
    }

    /**
     * @since 1.3.2.0
     */
    private MessageData prepareStartAnswer(Locale locale) {
        return MessageData.builder()
                          .message(messageSource.getMessage(START_ANSWER_MESSAGE, locale))
                          .toAdmin(false)
                          .toResponse(false)
                          .userResponse(true)
                          .build();
    }

    /**
     * @since 1.4.0.0
     */
    private MessageData prepareInfoAnswer(Locale locale) {
        return MessageData.builder()
                          .message(messageSource.getMessage(INFO_ANSWER_MESSAGE, locale))
                          .toAdmin(false)
                          .toResponse(false)
                          .userResponse(false)
                          .build();
    }

    /**
     * @since 1.4.1.0
     */
    private MessageData prepareInfoAnswerAll(Locale locale) {
        return MessageData.builder()
                          .message(messageSource.getMessage(INFO_ANSWER_ALL_MESSAGE, locale))
                          .toAdmin(false)
                          .toResponse(false)
                          .userResponse(false)
                          .build();
    }

    /**
     * @since 1.3.0.0
     */
    private void infoAdmin(Update update) {
        asyncHelper.doAsync((Supplier<Void>) () -> {
            doInfoAdmin(update);

            return null;
        });
    }

    /**
     * @since 1.3.0.0
     */
    private void doInfoAdmin(Update update) {
        try {
            Message message = update.getMessage();

            User user = message.getUser();
            if (user != null && communicationComponent.getAdminName().equals(user.getId().toString())) {
                return;
            }
            Long adminName = message.getChat().getChatId();
            if (communicationComponent.getAdminName().equals(adminName.toString())) {
                return;
            }

            Locale locale = user == null ? META.DEFAULT_LOCALE : new Locale(user.getLanguageCode());
            MessageData messageData = MessageData.builder()
                                                 .message(messageSource.getMessage(USER_CALL_MESSAGE, locale,
                                                                                   JsonUtil.toPrettyJson(message.getChat()),
                                                                                   user == null ? "---" :
                                                                                   JsonUtil.toPrettyJson(user)))
                                                 .build();

            communicationComponent.sendMessageToAdmin(messageData);
        } catch (Exception e) {
            log.warn("Error ==> infoAdmin", e);
        }
    }

    /**
     * @since 1.0.0.0
     */
    private MessageData prepareMisunderstandingMessage(Locale locale) {
        return MessageData.builder()
                          .toAdmin(false)
                          .toResponse(false)
                          .message(messageSource.getMessage(
                                  MISUNDERSTANDING_MESSAGES.get(random.nextInt(MISUNDERSTANDING_MESSAGES.size())), locale))
                          .build();
    }

    /**
     * @since 0.1.3.0
     */
    private boolean noNeedToAnswer(Message message) {
        User user = message.getUser();
        if (user.getIsBot()) {
            log.debug("Message from bot - ignore.");

            return true;
        }

        Chat chat = message.getChat();
        if ((workType == WorkType.G && isPrivate(chat.getType())) || (workType == WorkType.P && isGroup(chat.getType()))) {
            log.debug("Chat type '{}' does not correspond to allowed type '{}'.",
                      chat :: getType,
                      () -> workType);

            return true;
        }

        List<MessageEntity> entities = message.getEntities();
        if (entities != null && entities.get(0).getType().equalsIgnoreCase("bot_command") && entities.get(
                0).getOffset() == 0) {
            return false;
        }

        log.debug("noNeedToAnswer method ==> deafault behaviour ==> ignore.");

        return true;
    }

    /**
     * @since 0.1.3.0
     */
    private MessageData prepareAnswerWithCommand(Command command,
                                                 boolean full,
                                                 String chatType,
                                                 Locale locale) {
        Validate.notNull(command, "Command is not specified!");

        //TODO: private bot should answer only to "tet-a-tet" questions + only to allowed commands

        switch (workType) {
            case G:
                if (Command.INFO == command) {
                    if (isGroup(chatType)) {
                        return prepareInfoAnswer(locale);
                    } else {
                        log.debug("Command '{}' is allowed in group chats bot only!",
                                  () -> command);

                        return null;
                    }
                } else {
                    log.debug("Command '{}' is not allowed in group chats bot!",
                              () -> command);

                    return null;
                }
            case P:
                if (Command.START == command) {
                    if (isPrivate(chatType)) {
                        return prepareStartAnswer(locale);
                    } else {
                        log.debug("Command '{}' is allowed in \"tet-a-tet\" bot only!",
                                  () -> command);

                        return null;
                    }
                } else if (Command.INFO == command) {
                    log.debug("Command '{}' is allowed in group chats bot only!",
                              () -> command);

                    return null;
                }
            case B:
                String messageToSendString;

                if (Command.INFO == command) {
                    return prepareInfoAnswerAll(locale);
                } else if (Command.START == command) {
                    return prepareStartAnswer(locale);
                } else if (Command.D_ALL == command) {
                    messageToSendString = prepareMessageForShopsToSendString(full, false, locale);
                } else {
                    Shop3D shop = command.getShop();
                    Data3DPlastic plastic = searcher.search(shop);

                    messageToSendString = prepareMessageForShopToSendString(plastic, shop, command, full, false, locale);
                }

                if (StringUtils.isBlank(messageToSendString)) {
                    messageToSendString = messageSource.getMessage(NO_DISCOUNTS, locale);
                }

                return MessageData.builder()
                                  .toAdmin(false)
                                  .toResponse(false)
                                  .userResponse(WorkType.P == workType)
                                  .showUrlPreview(false)
                                  .message(messageToSendString)
                                  .build();
            default:
                throw new UnsupportedOperationException(String.format("Unexpected work type: '%s'!", workType));
        }
    }

    /**
     * @since 0.1.3.0
     */
    private String doPrepareMessageToSendString(Command command,
                                                boolean full,
                                                boolean onlyDiscounts,
                                                Data3DPlastic plastic,
                                                Shop3D shop,
                                                Locale locale) {
        String messageToSendString;

        switch (command) {
            case D_3DP:
            case D_3DUA:
            case D_MF:
            case D_U3DF:
                messageToSendString =
                        full ? prepareAnswerText(plastic, shop, locale) :
                        prepareAnswerTextShort(plastic, onlyDiscounts, locale);

                break;
            //            case D_DAS:
            //            case D_PLEX:
            default:
                messageToSendString = messageSource.getMessage(NO_WORK_WITH_SHOP, locale);
        }

        return messageToSendString;
    }

    /**
     * @since 0.1.3.0
     */
    private String prepareAnswerTextShort(Data3DPlastic plastic,
                                          boolean onlyDiscounts,
                                          Locale locale) {
        Map<PlasticType, List<ParseData>> byName =
                plastic.getData().stream()
                       //                       .filter(ParseData :: isInStock)
                       .collect(Collectors.groupingBy(ParseData :: getType, HashMap ::new,
                                                      Collectors.toCollection(ArrayList ::new)));
        Map<PlasticType, PlasticPresenceState> discountsState =
                byName.entrySet().stream()
                      .collect(Collectors.toMap(Map.Entry :: getKey,
                                                e -> definePlasticState(e.getValue())));

        if (onlyDiscounts) {
            discountsState = discountsState.entrySet().stream()
                                           .filter(e -> PlasticPresenceState.DISCOUNT == e.getValue())
                                           .collect(Collectors.toMap(Map.Entry :: getKey, Map.Entry :: getValue));
        }

        StringBuilder answer = new StringBuilder();
        discountsState.entrySet().stream()
                      .sorted(Comparator.comparing(Map.Entry :: getValue))
                      .forEach(s -> {
                          answer.append(messageSource.getMessage(SHORT_DISCOUNT_PART_MESSAGE, locale,
                                                                 alignText(s.getKey().getName() + ":"),
                                                                 getDiscountText(s.getValue())))
                          //TODO: link need to be added not by appender, but into SHORT_DISCOUNT_PART_MESSAGE!!!
                          //                    .append("<a href=\"").append("https://www.ebay.com").append("\">")
                          //                    .append("🔗")
                          //                    .append("</a>")
                          ;
                      });

        return answer.toString();
    }

    /**
     * @since 1.0.0.0
     */
    private String alignText(String text) {
        return String.format("%-12s", text);
    }

    /**
     * @since 0.1.3.0
     */
    private String getDiscountText(PlasticPresenceState presenceState) {
        switch (presenceState) {
            case DISCOUNT:
                return "✅";
            case IN_STOCK:
                return "❌";
            case NOT_IN_STOCK:
                return "⛔️";
            default:
                throw new UnsupportedOperationException(
                        String.format("Unexpected plastic presence state: '%s'!", presenceState));
        }
    }

    /**
     * @since 0.1.3.0
     */
    private String prepareAnswerText(Data3DPlastic plastic,
                                     Shop3D shop,
                                     Locale locale) {
        List<ParseData> data = plastic.getData();

        int counter = 0;
        StringBuilder answer = new StringBuilder();
        for (ParseData datum : data) {
            if (mayUsePlastic(datum)) {
                answer.append(messageSource.getMessage(FULL_DISCOUNT_PART_MESSAGE, locale, datum.getProductName(),
                                                       datum.getProductOldPrice(),
                                                       datum.getProductSalePrice(), datum.getProductUrl()));

                counter++;
            }

            if (counter == maxQuantity) {
                answer.append(messageSource.getMessage(FULL_DISCOUNT_OTHER_MESSAGE, locale, shop.getUrl()));

                break;
            }
        }

        return answer.toString();
    }

    /**
     * @since 1.2.0.0
     */
    private boolean mayUsePlastic(ParseData datum) {
        return datum.isInStock() && datum.getProductOldPrice() != null && datum.getProductSalePrice() != null;
    }

    /**
     * @since 1.4.0.0
     */
    private PlasticPresenceState definePlasticState(List<ParseData> data) {
        Set<PlasticPresenceState> states = new HashSet<>(PlasticPresenceState.values().length);
        data.forEach(d -> states.add(plasticState(d)));

        if (states.contains(PlasticPresenceState.DISCOUNT)) {
            return PlasticPresenceState.DISCOUNT;
        } else if (states.contains(PlasticPresenceState.IN_STOCK)) {
            return PlasticPresenceState.IN_STOCK;
        } else {
            return PlasticPresenceState.NOT_IN_STOCK;
        }
    }

    /**
     * @since 1.4.0.0
     */
    private PlasticPresenceState plasticState(ParseData datum) {
        if (!datum.isInStock()) {
            return PlasticPresenceState.NOT_IN_STOCK;
        }

        return datum.getProductOldPrice() != null && datum.getProductSalePrice() != null ? PlasticPresenceState.DISCOUNT :
               PlasticPresenceState.IN_STOCK;
    }

    /**
     * @since 0.1.3.0
     */
    private boolean hasNoCommand(List<MessageEntity> entities) {
        return entities == null || !entities.get(0).getType().equalsIgnoreCase("bot_command") || entities.get(
                0).getOffset() != 0;
    }

    /**
     * @since 0.1.3.0
     */
    private CommandHolder defineCommand(String text) {
        text = text.toUpperCase();
        if (OTHER_PATTERN.matcher(text).matches()) {
            String commandDataString = parseCommandDataString(text);
            commandDataString = commandDataString.replaceAll("/", "");
            Command command = Command.getByName(commandDataString);
            if (command == null) {
                throw new UnsupportedOperationException(String.format("Unexpected command! '%s'", text));
            }

            return CommandHolder.builder()
                                .command(command)
                                .full(false)
                                .build();
        }
        if (SALES_PATTERN.matcher(text).matches()) {
            String commandDataString = parseCommandDataString(text);
            String[] commandElements = commandDataString.split("_");
            Command command = Command.getByName(commandElements[1].toUpperCase());
            boolean full = commandElements.length == 3 && "f".equalsIgnoreCase(commandElements[2]);

            if (command == null) {
                throw new UnsupportedOperationException(String.format("Unexpected command! '%s'", text));
            }

            return CommandHolder.builder()
                                .command(command)
                                .full(full)
                                .build();
        }

        throw new UnsupportedOperationException(String.format("Unexpected pattern! '%s'", text));
    }

    /**
     * 1.4.0.0
     */
    private String parseCommandDataString(String text) {
        String commandDataString = text.split(" ")[0];
        if (commandDataString.contains("@")) {
            commandDataString = commandDataString.substring(0, commandDataString.indexOf("@"));
        }

        return commandDataString;
    }

    // </editor-fold>
}

