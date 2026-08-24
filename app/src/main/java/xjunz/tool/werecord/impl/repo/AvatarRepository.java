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
                //候选格式均未命中时，列出头像目录查找small_/middle_/large_前缀的文件（新版微信命名：small_<头像文件md5>，文件名与用户id无关）
                if (!copied && (!avatarFile.exists() || avatarFile.length() == 0)) {
                    scanAvatarDir(avatarDir, backupAvatarPath);
                }
            }
        } catch (ShellUtils.ShellException | IOException e) {
            LogUtils.error("Failed to load avatar of " + id + " :" + e.getMessage());
        }
        return BitmapFactory.decodeFile(backupAvatarPath);
    }

    /**
     * 列出头像目录，查找新版微信的头像文件（small_/middle_/large_前缀，无扩展名，
     * 文件名中的md5是头像图片的md5与用户id无关），复制到本地。
     * 跳过占位文件small_avatar_no_url。
     */
    private void scanAvatarDir(@NonNull String avatarDir, @NonNull String backupAvatarPath)
            throws ShellUtils.ShellException, IOException {
        CommandResult result = ShellUtils.sudo("ls", "-a", avatarDir);
        String stdout = result.getStdout();
        if (stdout == null) {
            return;
        }
        String[] lines = stdout.split("\n");
        //优先清晰度更高的尺寸
        String[] prefixes = {"large_", "middle_", "small_"};
        for (String prefix : prefixes) {
            for (String line : lines) {
                String name = line.trim();
                if (name.startsWith(prefix) && !"small_avatar_no_url".equals(name)) {
                    ShellUtils.cp2dataIfExists(avatarDir + name, backupAvatarPath, true);
                    return;
                }
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
     * 清除全部头像缓存（内存缓存与本地备份文件），
     * 下次获取头像时将重新从微信缓存目录加载。
     */
    public void clearCache() {
        synchronized (mAvatarCache) {
            mAvatarCache.evictAll();
        }
        File backupDir = new File(getEnvironment().getAvatarBackupPath());
        File[] files = backupDir.listFiles();
        if (files != null) {
            for (File file : files) {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
            }
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
