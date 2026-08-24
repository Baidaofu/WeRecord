/*
 * Copyright (c) 2021 xjunz. 保留所有权利
 */

package xjunz.tool.werecord.impl.repo;

import net.sqlcipher.Cursor;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import xjunz.tool.werecord.impl.model.account.Group;

public class GroupRepository extends AccountRepository<Group> {
    private static final int CACHE_CAPACITY = 20;
    private static final Pattern MEMBER_XML_PATTERN =
            Pattern.compile("<member>.*?<username>(.*?)</username>.*?(?:<displayname>(.*?)</displayname>|<nickname>(.*?)</nickname>).*?</member>", Pattern.DOTALL);
    private static final Pattern MEMBER_ATTR_PATTERN =
            Pattern.compile("<member[^>]*username=\"(.*?)\"[^>]*(?:displayname=\"(.*?)\"|nickname=\"(.*?)\")", Pattern.DOTALL);

    GroupRepository() {
    }

    @Override
    public int getCacheCapacity() {
        return CACHE_CAPACITY;
    }

    @Override
    protected void queryAllInternal(@NotNull List<Group> all) {
        Cursor cursor = getDatabase().rawQuery("select chatroomname,memberList,displayname,roomowner,memberCount,roomdata from chatroom", null);
        while (cursor.moveToNext()) {
            Group group = new Group(cursor.getString(0));
            group.setMemberIdSerial(cursor.getString(1));
            group.memberDisplayName = cursor.getString(2);
            group.groupOwnerId = cursor.getString(3);
            group.memberCount = cursor.getInt(4);
            group.roomData = cursor.getString(5);
            all.add(group);
        }
        cursor.close();
    }

    @Override
    protected Group query(String id) {
        Cursor cursor = getDatabase().rawQuery("select memberList,displayname,roomowner,memberCount,roomdata from chatroom where chatroomname='" + id + "'", null);
        Group group = new Group(id);
        if (cursor.moveToNext()) {
            group.setMemberIdSerial(cursor.getString(0));
            group.memberDisplayName = cursor.getString(1);
            group.groupOwnerId = cursor.getString(2);
            group.memberCount = cursor.getInt(3);
            group.roomData = cursor.getString(4);
        }
        cursor.close();
        return group;
    }

    /**
     * 查询群成员在群内的昵称（显示名）。
     * 优先取roomdata中的displayname，其次nickname；查不到返回null。
     */
    @Nullable
    public String getMemberNickName(@NotNull String groupId, @NotNull String memberId) {
        Group group = get(groupId);
        if (group == null || group.roomData == null || group.roomData.length() == 0) {
            return null;
        }
        String roomData = group.roomData;
        //格式一：<member><username>id</username><displayname>群昵称</displayname></member>
        //或<member><username>id</username><nickname>群昵称</nickname></member>
        Matcher matcher = MEMBER_XML_PATTERN.matcher(roomData);
        while (matcher.find()) {
            if (memberId.equals(matcher.group(1))) {
                String displayName = matcher.group(2);
                String nickName = matcher.group(3);
                String name = displayName != null && displayName.length() > 0 ? displayName : nickName;
                if (name != null && name.length() > 0) {
                    return name;
                }
            }
        }
        //格式二：<member username="id" nickname="群昵称" /> 或 displayname属性
        matcher = MEMBER_ATTR_PATTERN.matcher(roomData);
        while (matcher.find()) {
            if (memberId.equals(matcher.group(1))) {
                String displayName = matcher.group(2);
                String nickName = matcher.group(3);
                String name = displayName != null && displayName.length() > 0 ? displayName : nickName;
                if (name != null && name.length() > 0) {
                    return name;
                }
            }
        }
        return null;
    }
}
