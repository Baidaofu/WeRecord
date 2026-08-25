/*
 * Copyright (c) 2021 xjunz. 保留所有权利
 */

package xjunz.tool.werecord.ui.customview;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Movie;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;

import java.io.FileInputStream;

/**
 * 播放GIF动画的自定义View（使用已弃用但仍可用的android.graphics.Movie）
 */
public class GifImageView extends View {
    private Movie mMovie;
    private long mMovieStart;
    private int mWidth;
    private int mHeight;

    public GifImageView(Context context) {
        this(context, null);
    }

    public GifImageView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    /**
     * 加载GIF文件并开始播放
     */
    public void setGifFile(String path) {
        try {
            FileInputStream fis = new FileInputStream(path);
            mMovie = Movie.decodeStream(fis);
            fis.close();
            if (mMovie != null) {
                mWidth = mMovie.width();
                mHeight = mMovie.height();
                mMovieStart = 0;
                invalidate();
            }
        } catch (Exception e) {
            mMovie = null;
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (mMovie == null) {
            super.onDraw(canvas);
            return;
        }
        long now = SystemClock.uptimeMillis();
        if (mMovieStart == 0) {
            mMovieStart = now;
        }
        int dur = mMovie.duration();
        if (dur == 0) {
            dur = 1000;
        }
        mMovie.setTime((int) ((now - mMovieStart) % dur));
        //居中绘制，按View大小等比缩放
        float scale = Math.min((float) getWidth() / mWidth, (float) getHeight() / mHeight);
        canvas.save();
        canvas.scale(scale, scale);
        int drawX = (int) ((getWidth() - mWidth * scale) / 2 / scale);
        int drawY = (int) ((getHeight() - mHeight * scale) / 2 / scale);
        canvas.translate(drawX, drawY);
        mMovie.draw(canvas, 0, 0);
        canvas.restore();
        invalidate();
    }
}
