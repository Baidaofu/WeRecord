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
import xjunz.tool.werecord.util.LogUtils;
import xjunz.tool.werecord.util.MessageImageLoader;
import xjunz.tool.werecord.util.RxJavaUtils;
import xjunz.tool.werecord.util.WxgfDecoder;

/**
 * 媒体查看器：图片/表情放大预览（支持双指缩放）、视频播放、GIF动图播放。
 * 通过EXTRA_MEDIA_PATH传入已复制到应用私有目录的媒体文件路径。
 */
public class MediaViewerActivity extends RecycleAwareActivity {
    public static final String EXTRA_MEDIA_PATH = "MediaViewerActivity.extra.path";
    public static final String EXTRA_MSG = "MediaViewerActivity.extra.msg";
    public static final String EXTRA_THUMB = "MediaViewerActivity.extra.thumb";
    public static final String EXTRA_CANDIDATES = "MediaViewerActivity.extra.candidates";

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
        String[] rawCandidates = intent.getStringArrayExtra(EXTRA_CANDIDATES);
        final String[] candidates;
        if ((rawCandidates == null || rawCandidates.length == 0) && thumb != null) {
            candidates = new String[]{thumb};
        } else {
            candidates = rawCandidates;
        }

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
        //图片/表情消息：从候选路径复制缩略图显示，非GIF提供“加载原图”入口
        if (candidates != null && candidates.length > 0) {
            RxJavaUtils.maybe(() -> MessageImageLoader.copyFirstToLocal(candidates))
                    .subscribe(new RxJavaUtils.MaybeObserverAdapter<String>() {
                        @Override
                        public void onSuccess(@NotNull String local) {
                            if (isGifFile(local)) {
                                playGif(local);
                            } else {
                                showImage(local);
                                //图片：右上角提供"加载原图"按钮（当前显示的是缩略图）
                                setupLoadOriginalButton(msg);
                            }
                        }

                        @Override
                        public void onComplete() {
                            finish();
                        }
                    });
        } else {
            finish();
        }
    }

    /**
     * 加载原图：优先ImgInfo2.bigImgPath中的原图md5（真实原图文件名），
     * 其次缩略图md5与content md5；复制解码成功后替换显示，失败提示未缓存。
     */
    private void setupLoadOriginalButton(@Nullable Message msg) {
        if (msg == null) {
            return;
        }
        Button btn = findViewById(R.id.btn_original);
        btn.setVisibility(android.view.View.VISIBLE);
        btn.setOnClickListener(v -> {
            final String[] original = msg.getOriginalImageCandidatePaths();
            LogUtils.debug("load original clicked, candidates=" + original.length);
            if (original.length == 0) {
                MasterToast.shortToast("原图未缓存，无法加载");
                return;
            }
            btn.setEnabled(false);
            RxJavaUtils.maybe(() -> {
                //1. 明文图片候选（传统jpg/png）
                String local = MessageImageLoader.copyFirstToLocal(original);
                if (local != null) {
                    try {
                        Bitmap b = BitmapFactory.decodeFile(local);
                        if (b != null) {
                            return new Object[]{b, "plain"};
                        }
                    } catch (Exception ignored) {
                    }
                }
                //2. wxgf加密原图（微信新版私有HEVC封装，BitmapFactory无法解码）
                for (String path : original) {
                    String p = MessageImageLoader.copyToLocal(path);
                    if (p == null) {
                        continue;
                    }
                    byte[] bytes = readFile(new File(p));
                    if (bytes != null && bytes.length > 8 && bytes[0] == 'w' && bytes[1] == 'x' && bytes[2] == 'g' && bytes[3] == 'f') {
                        Bitmap b = WxgfDecoder.decodeToBitmap(bytes);
                        if (b != null) {
                            LogUtils.debug("wxgf decoded original: " + path);
                            return new Object[]{b, "wxgf"};
                        }
                    }
                }
                //3. find兜底（扫描image2目录）
                String found = MessageImageLoader.findOriginalByMd5(msg.getImageMd5Candidates());
                if (found != null) {
                    try {
                        Bitmap b = BitmapFactory.decodeFile(found);
                        if (b != null) {
                            return new Object[]{b, "find"};
                        }
                    } catch (Exception ignored) {
                    }
                    byte[] bytes = readFile(new File(found));
                    if (bytes != null && bytes.length > 8 && bytes[0] == 'w' && bytes[1] == 'x' && bytes[2] == 'g' && bytes[3] == 'f') {
                        Bitmap b = WxgfDecoder.decodeToBitmap(bytes);
                        if (b != null) {
                            LogUtils.debug("wxgf decoded(find): " + found);
                            return new Object[]{b, "wxgf-find"};
                        }
                    }
                }
                return null;
            }).subscribe(new RxJavaUtils.MaybeObserverAdapter<Object[]>() {
                        @Override
                        public void onSuccess(@NotNull Object[] result) {
                            Bitmap bmp = (Bitmap) result[0];
                            LogUtils.debug("load original success: " + result[1]);
                            mGifView.setVisibility(android.view.View.GONE);
                            mVvVideo.setVisibility(android.view.View.GONE);
                            mIvMedia.setVisibility(android.view.View.VISIBLE);
                            mIvMedia.setImageBitmap(bmp);
                            btn.setVisibility(android.view.View.GONE);
                        }

                        @Override
                        public void onComplete() {
                            LogUtils.debug("load original failed: no candidate");
                            btn.setEnabled(true);
                            MasterToast.shortToast("原图未缓存，无法加载");
                        }
                    });
        });
    }

    private static byte[] readFile(@Nullable File file) {
        if (file == null || !file.exists()) {
            return null;
        }
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] data = new byte[(int) file.length()];
            int off = 0;
            while (off < data.length) {
                int r = fis.read(data, off, data.length - off);
                if (r < 0) {
                    break;
                }
                off += r;
            }
            return off == data.length ? data : null;
        } catch (Exception e) {
            return null;
        }
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
