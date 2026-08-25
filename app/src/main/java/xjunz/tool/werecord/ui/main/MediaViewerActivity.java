/*
 * Copyright (c) 2021 xjunz. 保留所有权利
 */

package xjunz.tool.werecord.ui.main;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.content.Intent;
import android.os.Bundle;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.VideoView;

import androidx.annotation.Nullable;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.List;

import xjunz.tool.werecord.R;
import xjunz.tool.werecord.impl.Environment;
import xjunz.tool.werecord.impl.model.account.User;
import xjunz.tool.werecord.impl.model.message.Message;
import xjunz.tool.werecord.impl.model.message.MessageFactory;
import xjunz.tool.werecord.ui.base.RecycleAwareActivity;
import xjunz.tool.werecord.ui.customview.GifImageView;
import xjunz.tool.werecord.ui.customview.MasterToast;
import xjunz.tool.werecord.util.MessageImageLoader;
import xjunz.tool.werecord.util.RxJavaUtils;

/**
 * 媒体查看器：图片/表情放大预览（支持双指缩放）、视频播放、GIF动图播放。
 * 通过EXTRA_MEDIA_PATH传入已复制到应用私有目录的媒体文件路径。
 */
public class MediaViewerActivity extends RecycleAwareActivity {
    public static final String EXTRA_MEDIA_PATH = "MediaViewerActivity.extra.path";
    public static final String EXTRA_MSG = "MediaViewerActivity.extra.msg";
    public static final String EXTRA_THUMB = "MediaViewerActivity.extra.thumb";

    private ImageView mIvMedia;
    private VideoView mVvVideo;
    private GifImageView mGifView;
    private ScaleGestureDetector mScaleDetector;
    private float mScaleFactor = 1f;

    @Override
    protected void onCreateNormally(@Nullable Bundle savedInstanceState) {
        setContentView(R.layout.activity_media_viewer);
        mIvMedia = findViewById(R.id.iv_media);
        mVvVideo = findViewById(R.id.vv_video);
        mGifView = findViewById(R.id.gif_view);
        findViewById(R.id.pb_loading);

        Intent intent = getIntent();
        Message msg = intent.getParcelableExtra(EXTRA_MSG);
        String thumb = intent.getStringExtra(EXTRA_THUMB);

        //单击关闭（用GestureDetector区分点击与缩放，避免缩放后松手误关闭）
        GestureDetector tapDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onSingleTapUp(MotionEvent e) {
                finish();
                return true;
            }
        });

        //双指缩放（触摸事件在container上处理，Activity.onTouchEvent收不到）
        mScaleDetector = new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                mScaleFactor *= detector.getScaleFactor();
                mScaleFactor = Math.max(0.3f, Math.min(5f, mScaleFactor));
                mIvMedia.setScaleX(mScaleFactor);
                mIvMedia.setScaleY(mScaleFactor);
                return true;
            }
        });
        findViewById(R.id.container).setOnTouchListener((v, event) -> {
            mScaleDetector.onTouchEvent(event);
            tapDetector.onTouchEvent(event);
            //返回true消费事件，点击由GestureDetector处理，避免与缩放冲突
            return true;
        });

        //视频消息：尝试播放视频文件；否则显示缩略图（GIF则播放动画）
        if (msg != null && msg.getType() == MessageFactory.Type.VIDEO) {
            tryPlayVideo(msg);
            return;
        }
        if (thumb != null && new File(thumb).exists()) {
            if (isGifFile(thumb)) {
                playGif(thumb);
            } else {
                showImage(thumb);
                //图片：右上角提供"加载原图"按钮（当前显示的是缩略图）
                setupLoadOriginalButton(msg, thumb);
            }
        } else {
            finish();
        }
    }

    /**
     * 图片加载原图：current是缩略图，从中提取md5，构造原图候选路径（<md5>.jpg/.png）并加载
     */
    private void setupLoadOriginalButton(Message msg, String current) {
        String md5 = extractMd5FromThumb(current);
        if (md5 == null) {
            return;
        }
        Button btn = findViewById(R.id.btn_original);
        btn.setVisibility(android.view.View.VISIBLE);
        btn.setOnClickListener(v -> {
            User user = Environment.getInstance().getCurrentUser();
            if (user == null) {
                return;
            }
            String base = user.imageCachePath + File.separator + md5.substring(0, 2) + File.separator + md5.substring(2, 4);
            List<String> candidates = new ArrayList<>();
            candidates.add(base + File.separator + md5 + ".jpg");
            candidates.add(base + File.separator + md5 + ".png");
            candidates.add(base + File.separator + md5);
            //从消息图库路径的md5也构造一份候选（原图可能在另一组目录）
            String imgMd5 = extractMediaMd5FromContent(msg);
            if (imgMd5 != null && !imgMd5.equals(md5)) {
                String base2 = user.imageCachePath + File.separator + imgMd5.substring(0, 2) + File.separator + imgMd5.substring(2, 4);
                candidates.add(base2 + File.separator + imgMd5 + ".jpg");
                candidates.add(base2 + File.separator + imgMd5 + ".png");
                candidates.add(base2 + File.separator + imgMd5);
            }
            btn.setEnabled(false);
            String[] arr = candidates.toArray(new String[0]);
            RxJavaUtils.maybe(() -> MessageImageLoader.copyFirstToLocal(arr))
                    .subscribe(new RxJavaUtils.MaybeObserverAdapter<String>() {
                        @Override
                        public void onSuccess(@NotNull String original) {
                            Bitmap bmp = BitmapFactory.decodeFile(original);
                            if (bmp != null) {
                                mGifView.setVisibility(android.view.View.GONE);
                                mVvVideo.setVisibility(android.view.View.GONE);
                                mIvMedia.setVisibility(android.view.View.VISIBLE);
                                mIvMedia.setImageBitmap(bmp);
                                btn.setVisibility(android.view.View.GONE);
                            } else {
                                btn.setEnabled(true);
                                MasterToast.shortToast("原图未缓存，无法加载");
                            }
                        }

                        @Override
                        public void onComplete() {
                            btn.setEnabled(true);
                            MasterToast.shortToast("原图未缓存，无法加载");
                        }
                    });
        });
    }

    /**
     * 从缩略图路径提取md5（最后一段32hex），原图与缩略图共用同一md5（th_前缀区分）
     */
    @Nullable
    private String extractMd5FromThumb(String path) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("([0-9a-fA-F]{32})").matcher(path);
        String md5 = null;
        while (m.find()) {
            md5 = m.group(1);
        }
        return md5;
    }

    private String extractMediaMd5FromContent(Message msg) {
        if (msg == null) {
            return null;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^[a-zA-Z0-9]+:.*?([0-9a-fA-F]{32})").matcher(String.valueOf(msg.getContent()));
        if (m.find()) {
            return m.group(1);
        }
        return null;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (mScaleDetector != null) {
            mScaleDetector.onTouchEvent(event);
        }
        return super.onTouchEvent(event);
    }

    private void loadMedia(String path) {
        //按文件类型分流：视频 → VideoView；GIF → GifImageView；其他 → ImageView
        if (isVideoFile(path)) {
            playVideo(path);
        } else if (isGifFile(path)) {
            playGif(path);
        } else {
            showImage(path);
        }
    }

    private boolean isVideoFile(String path) {
        String lower = path.toLowerCase();
        return lower.endsWith(".mp4") || lower.endsWith(".3gp") || lower.endsWith(".mkv") || lower.endsWith(".mov");
    }

    private boolean isGifFile(String path) {
        try (FileInputStream fis = new FileInputStream(path)) {
            byte[] head = new byte[6];
            int read = fis.read(head);
            if (read >= 6) {
                String magic = new String(head);
                return magic.equals("GIF89a") || magic.equals("GIF87a");
            }
        } catch (Exception ignored) {
        }
        return path.toLowerCase().endsWith(".gif");
    }

    /**
     * 视频消息：尝试从video目录复制mp4并播放，找不到则显示缩略图
     */
    private void tryPlayVideo(Message msg) {
        String thumb = getIntent().getStringExtra(EXTRA_THUMB);
        RxJavaUtils.maybe(() -> {
            //视频候选：imgpath数字或content md5 → video目录的mp4
            User user = Environment.getInstance().getCurrentUser();
            List<String> candidates = new ArrayList<>();
            String imgPath = msg.getImgPath();
            if (imgPath != null && imgPath.matches("\\d+")) {
                candidates.add(user.videoCachePath + File.separator + imgPath + ".mp4");
            }
            //从消息候选路径里找mp4（可能已包含）
            String[] paths = msg.getImageCandidatePaths();
            if (paths != null) {
                for (String p : paths) {
                    if (p != null && p.toLowerCase().endsWith(".mp4")) {
                        candidates.add(p);
                    }
                }
            }
            for (String c : candidates) {
                String local = MessageImageLoader.copyToLocal(c);
                if (local != null) {
                    return local;
                }
            }
            return null;
        }).subscribe(new RxJavaUtils.MaybeObserverAdapter<String>() {
            @Override
            public void onSuccess(@NotNull String videoPath) {
                //找到视频文件，播放
                mVvVideo.setVisibility(android.view.View.VISIBLE);
                mVvVideo.setVideoPath(videoPath);
                mVvVideo.setOnPreparedListener(mp -> mVvVideo.start());
                mVvVideo.setOnErrorListener((mp, what, extra) -> {
                    //播放失败，回退显示缩略图
                    showThumbFallback(thumb);
                    return true;
                });
            }

            @Override
            public void onComplete() {
                //找不到视频文件，显示缩略图
                showThumbFallback(thumb);
            }
        });
    }

    private void showThumbFallback(@Nullable String thumb) {
        if (thumb != null && new File(thumb).exists()) {
            if (isGifFile(thumb)) {
                playGif(thumb);
            } else {
                showImage(thumb);
            }
        } else {
            finish();
        }
    }

    private void playVideo(String path) {
        mGifView.setVisibility(android.view.View.GONE);
        mIvMedia.setVisibility(android.view.View.GONE);
        mVvVideo.setVisibility(android.view.View.VISIBLE);
        mVvVideo.setVideoPath(path);
        mVvVideo.setOnPreparedListener(mp -> mVvVideo.start());
    }

    private void playGif(String path) {
        mGifView.setVisibility(android.view.View.VISIBLE);
        mIvMedia.setVisibility(android.view.View.GONE);
        mVvVideo.setVisibility(android.view.View.GONE);
        mGifView.setGifFile(path);
    }

    private void showImage(String path) {
        mGifView.setVisibility(android.view.View.GONE);
        mVvVideo.setVisibility(android.view.View.GONE);
        mIvMedia.setVisibility(android.view.View.VISIBLE);
        RxJavaUtils.maybe(() -> {
            //优先用已缓存的位图，否则解码文件
            Bitmap bitmap = BitmapFactory.decodeFile(path);
            return bitmap;
        }).subscribe(new RxJavaUtils.MaybeObserverAdapter<Bitmap>() {
            @Override
            public void onSuccess(Bitmap bitmap) {
                mIvMedia.setImageBitmap(bitmap);
            }

            @Override
            public void onComplete() {
                //解码失败
                finish();
            }
        });
    }
}
