/*
 * Copyright (c) 2021 xjunz. 保留所有权利
 */

package xjunz.tool.werecord.util;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import androidx.annotation.Nullable;

import com.jaredrummler.android.shell.CommandResult;
import org.apaches.commons.codec.digest.DigestUtils;

import java.io.File;
import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import xjunz.tool.werecord.App;
import xjunz.tool.werecord.impl.Environment;
import xjunz.tool.werecord.impl.model.account.User;

/**
 * 通过root从微信缓存目录加载消息图片的工具类。
 * 微信图片缓存位于内部存储（/data/user/0/com.tencent.mm/MicroMsg/...），
 * 借助ShellUtils以root权限将图片复制到应用私有目录后再解码。
 */
public class MessageImageLoader {
    private static final String CACHE_DIR_NAME = "msg_img_cache";

    /**
     * 依次尝试候选路径，返回第一个成功加载的位图；全部失败时扫描emoji目录兜底
     */
    @Nullable
    public static Bitmap loadAny(@Nullable String[] paths) {
        if (paths == null) {
            return null;
        }
        for (String path : paths) {
            Bitmap bitmap = load(path);
            if (bitmap != null) {
                //成功命中，记录实际路径便于调试
                LogUtils.debug("Media loaded: " + path);
                return bitmap;
            }
        }
        //候选路径均失败：表情可能在emoji子目录（如表情包目录），按md5扫描
        String md5 = extractMd5FromPaths(paths);
        if (md5 != null) {
            String found = scanEmojiDir(md5);
            if (found != null) {
                Bitmap bitmap = load(found);
                if (bitmap != null) {
                    LogUtils.debug("Media loaded(emoji scan): " + found);
                    return bitmap;
                }
            }
        }
        //全部失败，记录尝试过的路径便于排查缓存目录结构
        LogUtils.debug("Media all failed, tried " + paths.length + " paths:");
        for (String path : paths) {
            LogUtils.debug("  - " + path);
        }
        return null;
    }

    private static final Pattern MD5_PATTERN = Pattern.compile("([0-9a-fA-F]{32})");

    @Nullable
    private static String extractMd5FromPaths(@Nullable String[] paths) {
        if (paths == null) {
            return null;
        }
        for (String path : paths) {
            if (path == null) {
                continue;
            }
            Matcher matcher = MD5_PATTERN.matcher(path);
            if (matcher.find()) {
                return matcher.group(1);
            }
        }
        return null;
    }

    /**
     * 扫描emoji目录，按md5查找表情文件（表情可能在表情包子目录中）
     */
    @Nullable
    private static String scanEmojiDir(String md5) {
        try {
            User user = Environment.getInstance().getCurrentUser();
            if (user == null) {
                return null;
            }
            CommandResult result = ShellUtils.sudo("find", user.emojiCachePath, "-name", "*" + md5 + "*", "-type", "f");
            String stdout = result.getStdout();
            if (stdout == null) {
                return null;
            }
            String[] lines = stdout.split("\n");
            for (String line : lines) {
                String path = line.trim();
                if (path.length() == 0) {
                    continue;
                }
                //优先匹配文件名就是md5或md5.扩展名的文件
                String fileName = path.substring(path.lastIndexOf('/') + 1);
                if (fileName.equals(md5) || fileName.startsWith(md5 + ".") || fileName.startsWith(md5 + "_")) {
                    return path;
                }
            }
            if (lines.length > 0 && lines[0].trim().length() > 0) {
                return lines[0].trim();
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /**
     * root find 兜底：按md5前缀在image2目录搜索原图文件（候选路径构造失败时使用）。
     * 返回第一个可解码文件的私有副本路径，未找到返回null。
     */
    @Nullable
    public static String findOriginalByMd5(@Nullable String[] md5s) {
        if (md5s == null) {
            return null;
        }
        User user = Environment.getInstance().getCurrentUser();
        if (user == null) {
            return null;
        }
        for (String md5 : md5s) {
            if (md5 == null || md5.length() < 4) {
                continue;
            }
            try {
                CommandResult result = ShellUtils.sudo("find", user.imageCachePath, "-name", md5 + "*", "-type", "f");
                String stdout = result.getStdout();
                if (stdout == null) {
                    continue;
                }
                for (String line : stdout.split("\n")) {
                    String path = line.trim();
                    if (path.length() == 0) {
                        continue;
                    }
                    String base = path.substring(path.lastIndexOf('/') + 1);
                    if (base.startsWith("th_")) {
                        continue; //缩略图，跳过
                    }
                    String local = copyToLocal(path);
                    if (local != null) {
                        Bitmap bitmap = BitmapFactory.decodeFile(local);
                        if (bitmap != null) {
                            LogUtils.debug("findOriginalByMd5 hit: " + path);
                            return local;
                        }
                    }
                }
            } catch (Exception ignored) {
            }
        }
        LogUtils.debug("findOriginalByMd5 all failed");
        return null;
    }

    /**
     * 将微信缓存中的媒体文件复制到应用私有目录，返回本地副本路径（供播放/查看使用）。
     * 复制失败返回null。
     */
    @Nullable
    public static String copyToLocal(@Nullable String path) {
        if (path == null || path.length() == 0) {
            return null;
        }
        File cacheDir = new File(App.getContext().getFilesDir(), CACHE_DIR_NAME);
        if (!cacheDir.exists() && !cacheDir.mkdirs()) {
            return null;
        }
        String backupPath = cacheDir + File.separator + DigestUtils.md5Hex(path);
        File backup = new File(backupPath);
        if (backup.exists() && backup.length() == 0) {
            //残留空文件（此前cp失败留下），删除以便重新复制，避免缓存毒化
            //noinspection ResultOfMethodCallIgnored
            backup.delete();
        }
        if (!backup.exists()) {
            try {
                ShellUtils.cp2dataIfExists(path, backupPath, false);
            } catch (IOException | ShellUtils.ShellException e) {
                LogUtils.debug("copyToLocal exception: " + path + " -> " + e);
                return null;
            }
        }
        if (!backup.exists() || backup.length() == 0) {
            LogUtils.debug("copyToLocal empty/missing: " + path);
            return null;
        }
        LogUtils.debug("copyToLocal: " + path + " -> " + backupPath + " size=" + backup.length());
        return backupPath;
    }

    /**
     * 依次尝试候选路径，复制第一个成功解码的图片到本地并返回其路径（视频/GIF等非图片跳过）。
     * 全部失败返回null。
     */
    @Nullable
    public static String copyFirstToLocal(@Nullable String[] paths) {
        if (paths == null) {
            return null;
        }
        for (String path : paths) {
            String local = copyToLocal(path);
            if (local != null) {
                //跳过无法解码的文件（如加密表情、视频文件本身）
                Bitmap bitmap = BitmapFactory.decodeFile(local);
                if (bitmap != null) {
                    LogUtils.debug("copyFirstToLocal hit: " + path);
                    return local;
                }
                LogUtils.debug("copyFirstToLocal undecodable: " + path);
            } else {
                LogUtils.debug("copyFirstToLocal missing: " + path);
            }
        }
        LogUtils.debug("copyFirstToLocal all failed, " + paths.length + " candidates");
        return null;
    }

    /**
     * 加载指定路径的图片，缓存于应用私有目录。
     *
     * @param path 微信缓存中的图片路径（如缩略图路径）
     * @return 解码后的位图，加载失败返回null
     */
    public static Bitmap load(String path) {
        if (path == null || path.length() == 0) {
            return null;
        }
        File cacheDir = new File(App.getContext().getFilesDir(), CACHE_DIR_NAME);
        if (!cacheDir.exists() && !cacheDir.mkdirs()) {
            return null;
        }
        String backupPath = cacheDir + File.separator + DigestUtils.md5Hex(path);
        File backup = new File(backupPath);
        if (!backup.exists()) {
            try {
                ShellUtils.cp2dataIfExists(path, backupPath, false);
            } catch (IOException | ShellUtils.ShellException e) {
                //root复制失败，可能是文件不存在或无权限
                return null;
            }
        }
        if (backup.exists()) {
            Bitmap bitmap = BitmapFactory.decodeFile(backupPath);
            if (bitmap == null) {
                //文件存在但无法解码，删除缓存避免反复失败
                //noinspection ResultOfMethodCallIgnored
                backup.delete();
            }
            return bitmap;
        }
        return null;
    }
}
