/*
 * Copyright (c) 2021 xjunz. 保留所有权利
 */
package xjunz.tool.werecord.impl.model.message;

import android.content.ContentValues;
import android.os.Parcel;
import android.text.Spanned;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.text.HtmlCompat;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

import xjunz.tool.werecord.impl.model.account.Contact;
import xjunz.tool.werecord.impl.model.account.User;
import xjunz.tool.werecord.impl.repo.ContactRepository;
import xjunz.tool.werecord.impl.repo.GroupRepository;
import xjunz.tool.werecord.impl.repo.RepositoryFactory;
import xjunz.tool.werecord.util.Utils;

import static xjunz.tool.werecord.impl.model.message.MessageFactory.TYPE_SYSTEM_JOIN_GROUP;
import static xjunz.tool.werecord.impl.model.message.MessageFactory.TYPE_SYSTEM_PAT;

/**
 * 系统消息类
 */
public class SystemMessage extends Message {
    private Spanned html;

    public SystemMessage(ContentValues values) {
        super(values, MessageFactory.Type.SYSTEM);
    }

    @Override
    protected boolean needParseSenderId() {
        return getRawType() == TYPE_SYSTEM_JOIN_GROUP;
    }

    private Spanned getHtml() {
        return html = html == null ? HtmlCompat.fromHtml(escapeTag().replace("\n", "<br>"), HtmlCompat.FROM_HTML_MODE_LEGACY) : html;
    }

    /**
     * 去除img TAG，因为我们没必要显示系统消息里的图片
     * 去除scene TAG，某些消息里的场景TAG
     *
     * @return 去除了img TAG 的内容
     */
    @NotNull
    private String escapeTag() {
        //我们仅做一个简单的替换
        return content.replace("<img", "<img_escaped").replaceAll("<scene>.*?</scene>", "");
    }

    @NonNull
    @Override
    public String getParsedContent() {
        if (parsedContent == null) {
            if (isRevokeMsgContent(content)) {
                //撤回消息：显示“xxx 撤回了一条消息”
                parseRevokeMessage();
            } else if (isPatMsgContent(content)) {
                //新版微信把拍一拍放进appmsg的patMsg节点，与rawType无关
                parsePatMessage();
            } else {
                switch (getRawType()) {
                    case TYPE_SYSTEM_JOIN_GROUP:
                        parseJoinGroupMessage();
                        break;
                    case TYPE_SYSTEM_PAT:
                        parsePatMessage();
                        break;
                    default:
                        parsedContent = getHtml().toString();
                        break;
                }
            }
        }
        return parsedContent;
    }

    @NonNull
    @Override
    public CharSequence getSpannedContent() {
        if (spannedContent == null) {
            if (isRevokeMsgContent(content) || isPatMsgContent(content)) {
                spannedContent = getParsedContent();
            } else {
                switch (getRawType()) {
                    case TYPE_SYSTEM_JOIN_GROUP:
                    case TYPE_SYSTEM_PAT:
                        spannedContent = getParsedContent();
                        break;
                    default:
                        spannedContent = getHtml();
                        break;
                }
            }
        }
        return spannedContent;
    }


    private void parseJoinGroupMessage() {
        SAXParserFactory factory = SAXParserFactory.newInstance();
        try {
            SAXParser parser = factory.newSAXParser();
            JoinGroupMessageHandler handler = new JoinGroupMessageHandler();
            parser.parse(new ByteArrayInputStream(content.getBytes()), handler);
        } catch (ParserConfigurationException | SAXException | IOException e) {
            e.printStackTrace();
        }
    }

    private void parsePatMessage() {
        SAXParserFactory factory = SAXParserFactory.newInstance();
        try {
            SAXParser parser = factory.newSAXParser();
            PatMessageHandler handler = new PatMessageHandler();
            parser.parse(new ByteArrayInputStream(content.getBytes()), handler);
        } catch (ParserConfigurationException | SAXException | IOException e) {
            e.printStackTrace();
        }
    }


    private class JoinGroupMessageHandler extends DefaultHandler {
        private boolean inTemplate;
        private boolean inTarget;
        private boolean inLink;
        private String template;
        private String currentPattern;
        private List<String> patterns = new ArrayList<>();
        private HashMap<String, String> matchedMap;


        @Override
        public void startElement(String uri, String localName, String qName, Attributes attributes) throws SAXException {
            super.startElement(uri, localName, qName, attributes);
            switch (qName) {
                case "template":
                    inTemplate = true;
                    break;
                case "link":
                    inLink = true;
                    currentPattern = attributes.getValue("name");
                    break;
                case "plain":
                case "nickname":
                    inTarget = true;
                default:
                    break;
            }
        }

        @Override
        public void endElement(String uri, String localName, String qName) throws SAXException {
            super.endElement(uri, localName, qName);
            switch (qName) {
                case "template":
                    inTemplate = false;
                    break;
                case "link":
                    inLink = false;
                    break;
                case "plain":
                case "nickname":
                    inTarget = false;
                    break;
            }
        }

        @Override
        public void endDocument() throws SAXException {
            super.endDocument();
            for (String pattern : patterns) {
                String matched = matchedMap.get(pattern);
                if (matched == null) {
                    matched = "";
                }
                template = template.replace("$" + pattern + "$", matched);
            }
            parsedContent = template;
        }

        @Override
        public void characters(char[] ch, int start, int length) throws SAXException {
            super.characters(ch, start, length);
            String text = new String(ch, start, length);
            if (text.length() == 0) {
                text = "";
            }
            if (inTemplate) {
                template = text;
                patterns = Utils.extract(text, "\\$(.+?)\\$");
                matchedMap = new HashMap<>();
            } else if (inLink && inTarget) {
                String matched = matchedMap.get(currentPattern);
                if (matched == null) {
                    matched = text;
                } else {
                    matched = matched + "、" + text;
                }
                matchedMap.put(currentPattern, matched);
            }
        }
    }

    /**
     * 判断消息content是否为撤回消息（微信写入的sysmsg/revokemsg节点）
     */
    public static boolean isRevokeMsgContent(@Nullable String content) {
        if (content == null) {
            return false;
        }
        return content.contains("revokemsg") || content.contains("<replacemsg>");
    }

    private static final Pattern REVOKE_REPLACE_PATTERN =
            Pattern.compile("<replacemsg>(?:<!\\[CDATA\\[)?(.*?)(?:\\]\\]>)?</replacemsg>", Pattern.DOTALL);

    /**
     * 解析撤回消息，提取replacemsg中的可读文本（如“xxx 撤回了一条消息”），提取失败时回退到“消息已撤回”。
     */
    private void parseRevokeMessage() {
        Matcher matcher = REVOKE_REPLACE_PATTERN.matcher(content);
        if (matcher.find()) {
            String text = matcher.group(1).trim();
            if (text.length() > 0) {
                parsedContent = text;
                return;
            }
        }
        parsedContent = "消息已撤回";
    }

    /**
     * 判断消息content是否为真正的拍一拍消息：
     * 新版微信在appmsg中总是携带空的patMsg节点（recordNum=0），只有含record元素（recordNum&gt;0）
     * 或旧版直接含template的才是真正的拍一拍消息。
     */
    public static boolean isPatMsgContent(@Nullable String content) {
        if (content == null) {
            return false;
        }
        //新版：patMsg内含record元素（有拍一拍记录）
        //旧版：patMsg直接含template
        return content.contains("<record>") || (content.contains("<patMsg>") && content.contains("<template>"));
    }

    /**
     * 将拍一拍消息XML中的template节点文本更新为newText，保持XML结构完整。
     * 同时兼容CDATA形式（<template><![CDATA[...]]></template>）和普通文本形式。
     *
     * @return 更新后的XML，若未找到template节点则原样返回
     */
    @NotNull
    public static String updatePatTemplate(@NotNull String xml, @NotNull String newText) {
        //CDATA形式
        String escaped = newText.replace("]]>", "]]]]><![CDATA[>");
        String replaced = xml.replaceFirst("(<template><!\\[CDATA\\[)(.*?)(\\]\\]></template>)", "$1" + escaped + "$3");
        if (!replaced.equals(xml)) {
            return replaced;
        }
        //普通文本形式
        return xml.replaceFirst("(<template>)(.*?)(</template>)", "$1" + newText + "$3");
    }


    private class PatMessageHandler extends DefaultHandler {
        private boolean inPatMsg;
        private boolean inRecords;
        private boolean inRecord;
        private boolean inTemplate;
        private boolean inFromUser;
        private boolean inPattedUser;
        private final StringBuilder message;
        private final ContactRepository repo;
        //当前record的字段
        private String curFromUser;
        private String curPattedUser;
        private StringBuilder curTemplate;
        //正在累积的文本节点
        private StringBuilder curText;
        private List<String> patList;

        public PatMessageHandler() {
            super();
            message = new StringBuilder();
            repo = RepositoryFactory.get(ContactRepository.class);
            patList = new ArrayList<>();
        }

        @Override
        public void startElement(String uri, String localName, String qName, Attributes attributes) throws SAXException {
            super.startElement(uri, localName, qName, attributes);
            switch (qName) {
                case "patMsg":
                    inPatMsg = true;
                    break;
                case "records":
                    inRecords = true;
                    break;
                case "record":
                    inRecord = true;
                    curFromUser = null;
                    curPattedUser = null;
                    curTemplate = new StringBuilder();
                    break;
                case "template":
                    inTemplate = true;
                    break;
                case "fromUser":
                    inFromUser = true;
                    curText = new StringBuilder();
                    break;
                case "pattedUser":
                    inPattedUser = true;
                    curText = new StringBuilder();
                    break;
            }
        }

        @Override
        public void endElement(String uri, String localName, String qName) throws SAXException {
            super.endElement(uri, localName, qName);
            switch (qName) {
                case "patMsg":
                    inPatMsg = false;
                    break;
                case "records":
                    inRecords = false;
                    break;
                case "record":
                    inRecord = false;
                    //本record解析完成，处理模板占位符
                    patList.add(resolvePatTemplate(curTemplate.toString()));
                    break;
                case "template":
                    inTemplate = false;
                    break;
                case "fromUser":
                    inFromUser = false;
                    curFromUser = curText == null ? null : curText.toString();
                    curText = null;
                    break;
                case "pattedUser":
                    inPattedUser = false;
                    curPattedUser = curText == null ? null : curText.toString();
                    curText = null;
                    break;
            }
        }

        @Override
        public void endDocument() throws SAXException {
            super.endDocument();
            if (patList.size() == 0 && message.length() > 0) {
                //旧格式：template直接包含文本
                parsedContent = message.toString();
            } else {
                parsedContent = String.join("\n", patList);
            }
        }

        @Override
        public void characters(char[] ch, int start, int length) throws SAXException {
            super.characters(ch, start, length);
            String text = new String(ch, start, length);
            if (text.length() == 0) {
                return;
            }
            if (inTemplate && curTemplate != null) {
                curTemplate.append(text);
            } else if (inFromUser && curText != null) {
                curText.append(text);
            } else if (inPattedUser && curText != null) {
                curText.append(text);
            } else if (inTemplate) {
                //旧格式：template直接位于patMsg下（无record），解析其中的${wxid}占位符
                List<String> wxids = Utils.extract(text, "\\$\\{(.+?)\\}");
                for (String wxid : wxids) {
                    Contact contact = repo.get(wxid);
                    if (contact != null) {
                        text = text.replace("${" + wxid + "}", contact.getName());
                    }
                }
                if (message.length() == 0) {
                    message.append(text);
                } else {
                    message.append("\n").append(text);
                }
            }
        }

        /**
         * 解析一条拍一拍记录模板，替换新版占位符：
         * 1. 关键字占位符（${fromusername@xxx} / ${pattedusername@xxx}）：用record的fromUser/pattedUser查名字；
         *    模板已预填名字时替换为空；模板以“我”开头且fromUser是当前用户时替换为空。
         * 2. wxid占位符（${qq_xxx}等）：直接用wxid查联系人，查不到保留wxid原文。
         */
        private String resolvePatTemplate(String template) {
            if (template == null || template.length() == 0) {
                //模板缺失时回退为“xxx拍了拍yyy”
                return resolveName(curFromUser) + "拍了拍" + resolveName(curPattedUser);
            }
            List<String> placeholders = Utils.extract(template, "\\$\\{(.+?)\\}");
            if (placeholders.size() == 0) {
                return template;
            }
            //先把所有占位符替换为哨兵字符，避免去重判断把占位符本身误认为预填名字
            String masked = template;
            List<String> sentinels = new ArrayList<>();
            for (String placeholder : placeholders) {
                String sentinel = "\uE000" + sentinels.size();
                sentinels.add(sentinel);
                masked = masked.replace("${" + placeholder + "}", sentinel);
            }
            boolean startsWithMe = masked.startsWith("我");
            for (int i = 0; i < placeholders.size(); i++) {
                String name = resolvePlaceholderName(placeholders.get(i), startsWithMe);
                String sentinel = sentinels.get(i);
                if (name == null || name.length() == 0) {
                    masked = masked.replace(sentinel, "");
                } else if (masked.contains(name)) {
                    //模板中已直接写了名字（如“宋婧敏”），占位符替换为空避免重复
                    masked = masked.replace(sentinel, "");
                } else {
                    masked = masked.replace(sentinel, name);
                }
            }
            return masked;
        }

        /**
         * 解析单个占位符对应的显示名
         */
        private String resolvePlaceholderName(String placeholder, boolean startsWithMe) {
            String key = placeholder;
            int atIndex = key.indexOf('@');
            if (atIndex >= 0) {
                key = key.substring(0, atIndex);
            }
            if ("fromusername".equalsIgnoreCase(key)) {
                if (startsWithMe && isSelf(curFromUser)) {
                    //模板以“我”开头且fromUser是当前用户，“我”已表达发送者，占位符替换为空
                    return "";
                }
                return resolveName(curFromUser);
            } else if ("pattedusername".equalsIgnoreCase(key)) {
                return resolveName(curPattedUser);
            } else {
                //wxid占位符（旧格式或新格式的其它占位符）
                return resolveName(key);
            }
        }

        /**
         * 判断wxid是否为当前登录用户
         */
        private boolean isSelf(String wxid) {
            if (wxid == null || wxid.length() == 0) {
                return false;
            }
            try {
                User me = getCurrentUser();
                return me != null && me.id != null && me.id.equals(wxid);
            } catch (Exception e) {
                return false;
            }
        }

        /**
         * 将wxid解析为可显示的名字，查不到时尝试从群信息解析群昵称，仍无则保留wxid原文
         */
        private String resolveName(String wxid) {
            if (wxid == null || wxid.length() == 0) {
                return "";
            }
            Contact contact = repo.get(wxid);
            if (contact != null) {
                return contact.getName();
            }
            //查不到联系人时，尝试从消息所在群的roomdata解析群昵称（群成员可能不在通讯录）
            String groupNick = resolveGroupNickName(wxid);
            if (groupNick != null && groupNick.length() > 0) {
                return groupNick;
            }
            return wxid;
        }

        /**
         * 从消息所在群的chatroom.roomdata解析成员在群内的昵称
         */
        private String resolveGroupNickName(String wxid) {
            try {
                String talker = getTalkerId();
                if (talker != null && talker.endsWith("@chatroom")) {
                    GroupRepository groupRepository = RepositoryFactory.get(GroupRepository.class);
                    String name = groupRepository.getMemberNickName(talker, wxid);
                    if (name != null && name.length() > 0) {
                        return name;
                    }
                }
            } catch (Exception ignored) {
                //群信息查询失败时忽略，回退到wxid
            }
            return null;
        }
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NotNull Parcel dest, int flags) {
        super.writeToParcel(dest, flags);
        dest.writeString(this.parsedContent);
    }

    protected SystemMessage(Parcel in) {
        super(in);
        this.parsedContent = in.readString();
    }

    public static final Creator<SystemMessage> CREATOR = new Creator<SystemMessage>() {
        @NotNull
        @Contract("_ -> new")
        @Override
        public SystemMessage createFromParcel(Parcel source) {
            return new SystemMessage(source);
        }

        @NotNull
        @Contract(value = "_ -> new", pure = true)
        @Override
        public SystemMessage[] newArray(int size) {
            return new SystemMessage[size];
        }
    };

    @Override
    public String exportAsPlainText() {
        return String.format("[%s]\n%s", getType().getCaption(), getParsedContent());
    }
}
