/*
 * Copyright (c) 2021 xjunz. 保留所有权利
 */

package xjunz.tool.werecord.util;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import org.apaches.commons.codec.digest.DigestUtils;

import java.io.File;
import java.io.IOException;

import xjunz.tool.werecord.App;

/**
 * 通过root从微信缓存目录加载消息图片的工具类。
 * 微信图片缓存位于外部存储（sdcard/tencent/MicroMsg/...），原版应用不申请存储权限，
 * 因此借助ShellUtils以root权限将图片复制到应用私有目录后再解码。
 */
public class MessageImageLoader {
    private static final String CACHE_DIR_NAME = "msg_img_cache";

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
