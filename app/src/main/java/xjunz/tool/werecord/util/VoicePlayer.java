/*
 * Copyright (c) 2021 xjunz. 保留所有权利
 */

package xjunz.tool.werecord.util;

import android.media.MediaPlayer;

/**
 * 语音播放器：播放/停止本地语音文件（amr等Android原生支持格式）。
 * 同一时间仅播放一条，再次点击同一路径停止。异步prepare避免主线程阻塞。
 */
public class VoicePlayer {
    private static MediaPlayer sPlayer;
    private static String sCurrentPath;

    /**
     * 切换播放状态：同一路径点击停止，不同路径先停止再播放
     */
    public static void toggle(String path) {
        if (sCurrentPath != null && sCurrentPath.equals(path) && sPlayer != null) {
            stop();
            return;
        }
        stop();
        try {
            MediaPlayer player = new MediaPlayer();
            player.setDataSource(path);
            player.setOnPreparedListener(mp -> {
                LogUtils.debug("voice prepared, starting: " + path);
                mp.start();
            });
            player.setOnCompletionListener(mp -> stop());
            player.setOnErrorListener((mp, what, extra) -> {
                LogUtils.error("voice play error: " + what + "/" + extra + " path=" + path);
                stop();
                return true;
            });
            player.prepareAsync();
            sPlayer = player;
            sCurrentPath = path;
        } catch (Exception e) {
            LogUtils.error("voice prepare exception: " + e);
            stop();
        }
    }

    public static boolean isPlaying(String path) {
        return sCurrentPath != null && sCurrentPath.equals(path) && sPlayer != null && sPlayer.isPlaying();
    }

    public static void stop() {
        if (sPlayer != null) {
            try {
                sPlayer.release();
            } catch (Exception ignored) {
            }
            sPlayer = null;
        }
        sCurrentPath = null;
    }
}
