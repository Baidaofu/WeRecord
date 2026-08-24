/*
 * Copyright (c) 2021 xjunz. 保留所有权利
 */

package xjunz.tool.werecord.impl.repo;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Base64;
import android.util.LruCache;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.jaredrummler.android.shell.CommandResult;
import org.apaches.commons.codec.digest.DigestUtils;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;

import xjunz.tool.werecord.util.LogUtils;
import xjunz.tool.werecord.util.ShellUtils;

public class AvatarRepository extends LifecyclePerceptiveRepository {
    private static final int DEFAULT_CACHE_SIZE = 20 * 1024 * 1024;
    //Create a LruCache with 20MB of opacity
    private final LruCache<String, Bitmap> mAvatarCache;
    private static long sAvatarExpiredTime = 7 * 24 * 60 * 60 * 1000;

    AvatarRepository() {
        this.mAvatarCache = new LruCache<String, Bitmap>(DEFAULT_CACHE_SIZE) {
            @Override
            protected int sizeOf(String key, Bitmap value) {
                return value.getByteCount();
            }
        };
    }

    public void setAvatarExpiredTime(long timeInMills) {
        sAvatarExpiredTime = timeInMills;
    }

    /**
     * 从本地文件中解码指定微信ID的头像{@link Bitmap}
     *
     * @param id 微信ID
     * @return 该微信账号的头像，如果不存在则返回null
     */
    @Nullable
    private Bitmap decodeAvatar(@NonNull String id) {
        String idMd5 = DigestUtils.md5Hex(id);
        String backupAvatarPath = getEnvironment().getAvatarBackupPath() + File.separator + idMd5;
        String avatarDir = getCurrentUser().dirPath + File.separator + "avatar" + File.separator
                + idMd5.substring(0, 2) + File.separator
                + idMd5.substring(2, 4) + File.separator;
        try {
            File avatarFile = new File(backupAvatarPath);
            //如果头像不存在或已过期
            if (!avatarFile.exists() || (avatarFile.exists() && System.currentTimeMillis() - avatarFile.lastModified() > sAvatarExpiredTime)) {
                //微信头像文件名存在多种格式（user_<md5>.png为旧版，新版本可能去掉前缀或改扩展名），依次尝试
                String[] candidates = {
                        "user_" + idMd5 + ".png",
                        idMd5 + ".png",
                        idMd5 + ".jpg",
                        "th_" + idMd5 + ".png",
                        idMd5
                };
                boolean copied = false;
                for (String name : candidates) {
                    if (ShellUtils.isFileExists(avatarDir + name)) {
                        ShellUtils.cp2dataIfExists(avatarDir + name, backupAvatarPath, true);
                        copied = true;
                        break;
                    }
                }
                //候选格式均未命中时，列出头像目录查找包含md5的文件（兼容未知命名格式）
                if (!copied && (!avatarFile.exists() || avatarFile.length() == 0)) {
                    scanAvatarDir(avatarDir, idMd5, backupAvatarPath);
                }
            }
        } catch (ShellUtils.ShellException | IOException e) {
            LogUtils.error("Failed to load avatar of " + id + " :" + e.getMessage());
        }
        return BitmapFactory.decodeFile(backupAvatarPath);
    }

    /**
     * 列出头像目录，找到文件名包含指定md5的文件并复制到本地（兼容新版微信未知的命名格式）
     */
    private void scanAvatarDir(@NonNull String avatarDir, @NonNull String idMd5, @NonNull String backupAvatarPath)
            throws ShellUtils.ShellException, IOException {
        CommandResult result = ShellUtils.sudo("ls", "-a", avatarDir);
        String stdout = result.getStdout();
        if (stdout == null) {
            return;
        }
        for (String line : stdout.split("\n")) {
            String name = line.trim();
            if (name.length() > 0 && name.contains(idMd5)) {
                ShellUtils.cp2dataIfExists(avatarDir + name, backupAvatarPath, true);
                break;
            }
        }
    }


    /**
     * 将指定微信ID的头像纳入缓存
     *
     * @param id     指定微信ID
     * @param bitmap 欲缓存的头像
     */
    public void putAvatarOf(@NonNull String id, @NonNull Bitmap bitmap) {
        synchronized (mAvatarCache) {
            mAvatarCache.put(id, bitmap);
        }
    }

    /**
     * 获取指定微信ID的微信头像
     * <p>先从缓存中获取，如果存在返回缓存的头像，如果不存在，再尝试本地解码
     * 文件，如果本地头像文件解码成功，纳入缓存并返回此头像，如果不存在，返回null
     * </p>
     *
     * @param id 微信ID
     * @return 该微信账号的头像，如果不存在则返回null
     */
    @Nullable
    public Bitmap getAvatar(@NonNull String id) {
        Bitmap cache = mAvatarCache.get(id);
        if (cache == null) {
            Bitmap bitmap = decodeAvatar(id);
            if (bitmap != null) {
                putAvatarOf(id, bitmap);
                return bitmap;
            } else {
                return null;
            }
        } else {
            return cache;
        }
    }

    /**
     * 转码指定微信头像
     *
     * @param AvatarBitmap 头像图片
     * @return 该微信账号的头像的Base64
     */
    public String BitmapToBase64(@NonNull Bitmap AvatarBitmap) {
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        AvatarBitmap.compress(Bitmap.CompressFormat.PNG, 100, byteArrayOutputStream);
        byte[] byteArray = byteArrayOutputStream.toByteArray();
        return "data:image/png;base64,"+Base64.encodeToString(byteArray, Base64.NO_WRAP);
    }

}
