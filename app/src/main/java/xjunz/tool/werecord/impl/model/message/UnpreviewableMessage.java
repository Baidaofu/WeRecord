/*
 * Copyright (c) 2021 xjunz. 保留所有权利
 */
package xjunz.tool.werecord.impl.model.message;

import android.content.ContentValues;
import android.os.Parcel;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.text.HtmlCompat;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import xjunz.tool.werecord.App;
import xjunz.tool.werecord.R;
import xjunz.tool.werecord.impl.repo.AvatarRepository;
import xjunz.tool.werecord.impl.repo.RepositoryFactory;
import xjunz.tool.werecord.util.LogUtils;
import xjunz.tool.werecord.util.Utils;

/**
 * 暂不支持预览的消息类型，如推送消息和一些暂未识别的消息类型。
 */
public class UnpreviewableMessage extends ComplexMessage {

    public UnpreviewableMessage(ContentValues values, MessageFactory.Type type) {
        super(values, type);
    }

    @NonNull
    @Override
    public String getTitle() {
        if (getType() == MessageFactory.Type.VOICE) {
            //语音消息显示时长（从content解析voicelength，单位毫秒）
            String duration = parseVoiceDuration();
            if (duration != null) {
                return duration + "秒";
            }
        }
        return App.getStringOf(R.string.unpreviewable);
    }

    /**
     * 从语音消息content中解析时长（秒），解析失败返回null。
     * 支持两种格式：新版XML（&lt;voicemsg ... voicelength="2661" ...&gt;，毫秒）
     * 与冒号格式（wxid:14393:1，第2段为毫秒）。
     */
    @Nullable
    private String parseVoiceDuration() {
        String raw = getRawContent();
        LogUtils.debug("parseVoiceDuration raw: " + raw);
        if (raw == null) {
            return null;
        }
        Matcher matcher = Pattern.compile("voicelength\\s*=\\s*\"?(\\d+)\"?").matcher(raw);
        if (matcher.find()) {
            try {
                long ms = Long.parseLong(matcher.group(1));
                String result = String.valueOf(Math.max(1, Math.round(ms / 1000.0)));
                LogUtils.debug("voice duration(xml): " + result + "s");
                return result;
            } catch (NumberFormatException ignored) {
            }
        }
        //冒号格式：wxid:14393:1，第2段为毫秒
        String[] parts = raw.split(":");
        if (parts.length >= 2) {
            try {
                long ms = Long.parseLong(parts[1]);
                if (ms > 0 && ms < 600000) {
                    String result = String.valueOf(Math.max(1, Math.round(ms / 1000.0)));
                    LogUtils.debug("voice duration(colon): " + result + "s");
                    return result;
                }
            } catch (NumberFormatException ignored) {
            }
        }
        LogUtils.debug("voice duration: parse failed");
        return null;
    }

    @Override
    public String getDescription() {
        return null;
    }

    @Override
    public String getCaption() {
        return null;
    }

    @NonNull
    @Override
    public String getParsedContent() {
        return getType().getCaption() + "\n" + getTitle();
    }

    @NonNull
    @Override
    public CharSequence getSpannedContent() {
        return HtmlCompat.fromHtml("<i>&lt;" + getParsedContent() + "&gt;</i>", HtmlCompat.FROM_HTML_MODE_LEGACY);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NotNull Parcel dest, int flags) {
        super.writeToParcel(dest, flags);
    }

    protected UnpreviewableMessage(Parcel in) {
        super(in);
    }

    public static final Creator<UnpreviewableMessage> CREATOR = new Creator<UnpreviewableMessage>() {
        @NotNull
        @Contract("_ -> new")
        @Override
        public UnpreviewableMessage createFromParcel(Parcel source) {
            return new UnpreviewableMessage(source);
        }

        @NotNull
        @Contract(value = "_ -> new", pure = true)
        @Override
        public UnpreviewableMessage[] newArray(int size) {
            return new UnpreviewableMessage[size];
        }
    };

    /**
     * 格式为:
     * \@发送者名称 (时间)
     * [类型]
     * 如：
     * \@xjunz（2020.1.12 16:54:01:000）
     * [图片]
     */
    @Override
    public String exportAsPlainText() {
        return String.format("<%s> %s \n[%s]",
                requireSenderName(),
                Utils.formatDateLocally(getCreateTimeStamp()),
                getType().getCaption());
    }

    @Override
    public String exportAsHtml() {
        AvatarRepository repository =  RepositoryFactory.get(AvatarRepository.class);
        String EscapeContent = getParsedContent();
        EscapeContent = EscapeContent.replace("\"", "\\\"");
        EscapeContent = EscapeContent.replace("\n", "\\\\n");
        EscapeContent = EscapeContent.replace("\b", "\\\b");
        EscapeContent = EscapeContent.replace("\f", "\\\f");
        EscapeContent = EscapeContent.replace("\t", "\\\t");
        EscapeContent = EscapeContent.replace("\r", "\\\r");
        EscapeContent = EscapeContent.replace("\\u", "\\\\u");
        if (EscapeContent.isEmpty()) EscapeContent = getType().getCaption();
        return  String.format("{\\\"time\\\":\\\"%s\\\"," +
                        "\\\"sender\\\":\\\"%s\\\"," +
                        "\\\"faceImg\\\":\\\"%s\\\"," +
                        "\\\"msgType\\\":\\\"%s\\\"," +
                        "\\\"msgContent\\\":\\\"%s\\\"," +
                        "\\\"isMe\\\":%s," +
                        "\\\"imgUrl\\\":\\\"%s\\\"}",
                getCreateTimeStamp(),
                requireSenderName(),//sender
                repository.BitmapToBase64(Objects.requireNonNull(repository.getAvatar(getSenderId()))),//faceImgUrl
                getType(),//msgType
                EscapeContent,//msgContent
                isSend(),//isMe
                getLocalImagePath());//imgUrl
    }
}
