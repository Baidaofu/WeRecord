/*
 * Copyright (c) 2021 xjunz. 保留所有权利
 */

package xjunz.tool.werecord.util;

import android.graphics.Bitmap;
import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaFormat;

import androidx.annotation.Nullable;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 解码微信wxgf私有图片格式（新版微信将原图以HEVC/H.265裸流封装）。
 * 结构：wxgf头(第5字节为头部长度) + NAL流(00 00 01/00 00 00 01 + 长度字段)。
 * 通过MediaCodec硬解HEVC输出YUV，再转Bitmap。
 */
public class WxgfDecoder {
    private static final byte[] MAGIC = {'w', 'x', 'g', 'f'};

    /**
     * 解码wxgf字节为Bitmap，失败返回null。
     */
    @Nullable
    public static Bitmap decodeToBitmap(@Nullable byte[] data) {
        if (data == null || data.length < 16) {
            return null;
        }
        if (data[0] != MAGIC[0] || data[1] != MAGIC[1] || data[2] != MAGIC[2] || data[3] != MAGIC[3]) {
            return null;
        }
        try {
            int headerLen = data[4] & 0xFF;
            if (headerLen >= data.length) {
                return null;
            }
            //收集所有有效分区（start code前4字节为长度，校验合法性），取最大分区（图片主体）
            List<int[]> parts = findPartitions(data, headerLen);
            if (parts.isEmpty()) {
                return null;
            }
            int maxIdx = 0;
            for (int i = 1; i < parts.size(); i++) {
                if (parts.get(i)[1] > parts.get(maxIdx)[1]) {
                    maxIdx = i;
                }
            }
            int off = parts.get(maxIdx)[0];
            int len = parts.get(maxIdx)[1];
            LogUtils.debug("wxgf parts=" + parts.size() + " pick off=" + off + " len=" + len + " total=" + data.length);
            byte[] nalStream = Arrays.copyOfRange(data, off, off + len);
            return decodeHevcToBitmap(nalStream);
        } catch (Exception e) {
            LogUtils.error("wxgf decode exception: " + e);
            return null;
        }
    }

    /**
     * 收集所有有效数据分区[offset, length]：从头部偏移起查找start code，
     * 校验其前4字节长度字段（0<len且不越界），跳过无效候选（如数据中误匹配的00000001）。
     */
    private static List<int[]> findPartitions(byte[] data, int from) {
        List<int[]> parts = new ArrayList<>();
        int n = data.length;
        int i = from;
        while (i + 3 < n) {
            if (data[i] == 0 && data[i + 1] == 0 && data[i + 2] == 0 && data[i + 3] == 1) {
                int len = ((data[i - 4] & 0xFF) << 24) | ((data[i - 3] & 0xFF) << 16)
                        | ((data[i - 2] & 0xFF) << 8) | (data[i - 1] & 0xFF);
                if (len > 0 && i + len <= n) {
                    parts.add(new int[]{i, len});
                    i += len;
                } else {
                    i += 4;
                }
            } else if (data[i] == 0 && data[i + 1] == 0 && data[i + 2] == 1) {
                int len = ((data[i - 4] & 0xFF) << 24) | ((data[i - 3] & 0xFF) << 16)
                        | ((data[i - 2] & 0xFF) << 8) | (data[i - 1] & 0xFF);
                if (len > 0 && i + len <= n) {
                    parts.add(new int[]{i, len});
                    i += len;
                } else {
                    i += 3;
                }
            } else {
                i++;
            }
        }
        return parts;
    }

    @Nullable
    private static Bitmap decodeHevcToBitmap(byte[] nalStream) {
        List<byte[]> nals = splitNals(nalStream);
        if (nals.isEmpty()) {
            LogUtils.debug("wxgf splitNals empty, stream len=" + nalStream.length);
            return null;
        }
        ByteBuffer csd = ByteBuffer.allocate(nalStream.length);
        List<byte[]> frameNals = new ArrayList<>();
        for (byte[] nal : nals) {
            if (nal.length < 5) {
                continue;
            }
            //HEVC：start code后前2字节，nal_unit_type = (b0>>1)&0x3F
            int hdrIdx = (nal[3] == 1) ? 3 : 4; //3字节或4字节start code
            int type = (nal[hdrIdx] & 0x7E) >> 1;
            if (type == 32 || type == 33 || type == 34) {
                //VPS/SPS/PPS → csd-0
                csd.put(nal);
            } else if (type >= 0 && type <= 31) {
                //VCL（含IDR关键帧）
                frameNals.add(nal);
            }
        }
        if (frameNals.isEmpty()) {
            LogUtils.debug("wxgf no VCL frames, nals=" + nals.size() + " csd=" + csd.position());
            return null;
        }
        byte[] csdBytes = new byte[csd.position()];
        System.arraycopy(csd.array(), 0, csdBytes, 0, csdBytes.length);
        LogUtils.debug("wxgf nals=" + nals.size() + " csd=" + csdBytes.length + " frames=" + frameNals.size());
        return decodeWithCodec(csdBytes, frameNals);
    }

    @Nullable
    private static Bitmap decodeWithCodec(byte[] csd, List<byte[]> frameNals) {
        MediaCodec codec = null;
        try {
            MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, 1080, 2400);
            if (csd.length > 0) {
                format.setByteBuffer("csd-0", ByteBuffer.wrap(csd));
            }
            codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_HEVC);
            codec.configure(format, null, null, 0);
            codec.start();

            //所有VCL帧合并为一个buffer喂入（AnnexB流）
            int inIndex = codec.dequeueInputBuffer(1_000_000);
            if (inIndex < 0) {
                LogUtils.debug("wxgf dequeueInputBuffer timeout, frames=" + frameNals.size());
                return null;
            }
            ByteBuffer inBuf = codec.getInputBuffer(inIndex);
            if (inBuf == null) {
                LogUtils.debug("wxgf inputBuffer null");
                return null;
            }
            inBuf.clear();
            int inOffset = 0;
            for (byte[] nal : frameNals) {
                if (inBuf.remaining() < nal.length) {
                    break;
                }
                inBuf.put(nal);
                inOffset += nal.length;
            }
            if (inOffset == 0) {
                LogUtils.debug("wxgf input empty");
                return null;
            }
            codec.queueInputBuffer(inIndex, 0, inOffset, 0, 0);
            LogUtils.debug("wxgf queued input offset=" + inOffset);
            //结束标记
            int eosIndex = codec.dequeueInputBuffer(10_000);
            if (eosIndex >= 0) {
                codec.queueInputBuffer(eosIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
            }

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            Bitmap result = null;
            long deadline = System.currentTimeMillis() + 10_000;
            while (System.currentTimeMillis() < deadline) {
                int outIndex = codec.dequeueOutputBuffer(info, 100_000);
                if (outIndex >= 0) {
                    boolean eos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    if (result == null && info.size > 0) {
                        try {
                            Image image = codec.getOutputImage(outIndex);
                            if (image != null) {
                                result = imageToBitmap(image);
                                image.close();
                            }
                        } catch (Exception e) {
                            LogUtils.debug("wxgf getOutputImage fail: " + e);
                        }
                    }
                    codec.releaseOutputBuffer(outIndex, false);
                    if (eos || result != null) {
                        break;
                    }
                } else if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    //不再解码，等待超时
                }
            }
            if (result == null) {
                LogUtils.debug("wxgf decode timeout, no output");
            }
            return result;
        } catch (Exception e) {
            LogUtils.error("wxgf codec exception: " + e);
            return null;
        } finally {
            try {
                if (codec != null) {
                    codec.stop();
                    codec.release();
                }
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * YUV_420_888 → ARGB_8888 Bitmap（处理行步幅与U/V像素步幅）
     */
    private static Bitmap imageToBitmap(Image image) {
        int w = image.getWidth();
        int h = image.getHeight();
        if (w <= 0 || h <= 0) {
            return null;
        }
        Image.Plane[] planes = image.getPlanes();
        ByteBuffer yBuf = planes[0].getBuffer();
        ByteBuffer uBuf = planes[1].getBuffer();
        ByteBuffer vBuf = planes[2].getBuffer();
        int yRowStride = planes[0].getRowStride();
        int uvRowStride = planes[1].getRowStride();
        int uvPixelStride = planes[1].getPixelStride();
        if (uvPixelStride == 0) {
            uvPixelStride = 1;
        }
        yBuf.rewind();
        uBuf.rewind();
        vBuf.rewind();
        byte[] y = new byte[yRowStride * (h - 1) + w];
        yBuf.get(y, 0, y.length);
        byte[] uvFull = new byte[uvRowStride * ((h + 1) / 2 - 1) + uvRowStride];
        //U、V单独读
        byte[] u = new byte[(h / 2) * uvRowStride];
        byte[] v = new byte[(h / 2) * uvRowStride];
        readPlane(uBuf, u, uvRowStride, h / 2);
        readPlane(vBuf, v, uvRowStride, h / 2);

        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        int[] pixels = new int[w * h];
        int idx = 0;
        for (int row = 0; row < h; row++) {
            int yRow = row * yRowStride;
            int uvRow = (row >> 1) * uvRowStride;
            for (int col = 0; col < w; col++) {
                int yv = y[yRow + col] & 0xFF;
                int uIdx = uvRow + (col >> 1) * uvPixelStride;
                int vIdx = uvRow + (col >> 1) * uvPixelStride;
                int uv = (uIdx < u.length && uIdx >= 0) ? u[uIdx] & 0xFF : 128;
                int vv = (vIdx < v.length && vIdx >= 0) ? v[vIdx] & 0xFF : 128;
                int c = yv - 16;
                int d = uv - 128;
                int e = vv - 128;
                int r = clamp((298 * c + 409 * e + 128) >> 8);
                int g = clamp((298 * c - 100 * d - 208 * e + 128) >> 8);
                int b = clamp((298 * c + 516 * d + 128) >> 8);
                pixels[idx++] = (0xFF << 24) | (r << 16) | (g << 8) | b;
            }
        }
        bmp.setPixels(pixels, 0, w, 0, 0, w, h);
        return bmp;
    }

    private static void readPlane(ByteBuffer buf, byte[] out, int rowStride, int rows) {
        buf.rewind();
        int total = rowStride * rows;
        if (buf.remaining() >= 0) {
            try {
                if (buf.remaining() >= total) {
                    buf.get(out, 0, total);
                } else {
                    buf.get(out, 0, buf.remaining());
                }
            } catch (Exception ignored) {
            }
        }
    }

    private static int clamp(int v) {
        return v < 0 ? 0 : (v > 255 ? 255 : v);
    }

    /**
     * 按AnnexB start code切分NAL单元（保留start code）
     */
    private static List<byte[]> splitNals(byte[] data) {
        List<byte[]> list = new ArrayList<>();
        int i = 0;
        int n = data.length;
        while (i + 3 <= n) {
            int start = -1;
            for (int j = i; j + 3 <= n; j++) {
                if (j + 4 <= n && data[j] == 0 && data[j + 1] == 0 && data[j + 2] == 0 && data[j + 3] == 1) {
                    start = j;
                    break;
                }
                if (data[j] == 0 && data[j + 1] == 0 && data[j + 2] == 1) {
                    start = j;
                    break;
                }
            }
            if (start < 0) {
                if (list.isEmpty()) {
                    break;
                }
                //剩余数据并入最后一个NAL
                byte[] tail = Arrays.copyOfRange(data, i, n);
                byte[] last = list.remove(list.size() - 1);
                byte[] merged = new byte[last.length + tail.length];
                System.arraycopy(last, 0, merged, 0, last.length);
                System.arraycopy(tail, 0, merged, last.length, tail.length);
                list.add(merged);
                break;
            }
            int end = -1;
            for (int j = start + 3; j + 3 <= n; j++) {
                if (j + 4 <= n && data[j] == 0 && data[j + 1] == 0 && data[j + 2] == 0 && data[j + 3] == 1) {
                    end = j;
                    break;
                }
                if (data[j] == 0 && data[j + 1] == 0 && data[j + 2] == 1) {
                    end = j;
                    break;
                }
            }
            int payloadEnd = (end >= 0) ? end : n;
            list.add(Arrays.copyOfRange(data, start, payloadEnd));
            i = payloadEnd;
        }
        return list;
    }
}