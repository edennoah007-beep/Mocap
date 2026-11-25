package com.vstage.app;

import android.annotation.SuppressLint;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.MediaRecorder;
import android.util.Log;
import android.view.Surface;

import java.io.FileDescriptor;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * MP4 writer: H.264 video from a Surface (the screen) + AAC audio from the microphone,
 * with the microphone passed through {@link VoiceFx} first so the voice change is in the video.
 */
public final class Mp4Recorder {

    public interface Listener { void onError(String message); }

    private static final String TAG = "VStageRec";
    private static final int SR = 48000;
    private static final int CHUNK = 1024;

    private final MediaCodec video;
    private final Surface inputSurface;
    private MediaCodec audio;
    private AudioRecord mic;
    private final MediaMuxer muxer;
    private final VoiceFx fx;
    private final Listener listener;

    private final Object lock = new Object();
    private int videoTrack = -1, audioTrack = -1;
    private boolean muxing = false;
    private final List<Pending> pending = new ArrayList<>();
    private long videoSamples = 0;

    private volatile boolean running = false;
    private Thread videoThread, audioThread;

    private static final class Pending {
        final boolean isVideo; final ByteBuffer data; final MediaCodec.BufferInfo info;
        Pending(boolean v, ByteBuffer d, MediaCodec.BufferInfo i) { isVideo = v; data = d; info = i; }
    }

    @SuppressLint("MissingPermission")
    public Mp4Recorder(int width, int height, int fps, int bitrate, boolean withMic, String voicePreset,
                       FileDescriptor fd, Listener listener) throws IOException {
        this.listener = listener;
        this.fx = new VoiceFx(SR, voicePreset);

        MediaFormat vf = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height);
        vf.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        vf.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
        vf.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
        vf.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
        vf.setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 1_000_000L / fps);
        video = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
        try {
            video.configure(vf, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            inputSurface = video.createInputSurface();
        } catch (RuntimeException e) {
            video.release();
            throw e;
        }

        if (withMic) {
            try {
                int min = AudioRecord.getMinBufferSize(SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
                AudioRecord r = new AudioRecord(MediaRecorder.AudioSource.MIC, SR, AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT, Math.max(min, CHUNK * 8));
                if (r.getState() != AudioRecord.STATE_INITIALIZED) { r.release(); throw new IllegalStateException("mic busy"); }
                MediaFormat af = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SR, 1);
                af.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
                af.setInteger(MediaFormat.KEY_BIT_RATE, 160_000);
                af.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, CHUNK * 2 * 4);
                MediaCodec a = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
                a.configure(af, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                mic = r; audio = a;
            } catch (Exception e) {
                Log.w(TAG, "microphone unavailable, recording without sound", e);
                if (mic != null) { mic.release(); mic = null; }
                audio = null;
            }
        }
        muxer = new MediaMuxer(fd, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
    }

    public Surface getInputSurface() { return inputSurface; }

    public void start() {
        running = true;
        video.start();
        videoThread = new Thread(this::videoLoop, "vstage-video");
        videoThread.start();
        if (audio != null) {
            audio.start();
            audioThread = new Thread(this::audioLoop, "vstage-audio");
            audioThread.start();
        }
    }

    /** Stops and finalizes the file. Returns true if a playable MP4 was written. */
    public boolean stop() {
        running = false;
        try { video.signalEndOfInputStream(); } catch (Exception e) { Log.w(TAG, "eos", e); }
        join(audioThread);
        join(videoThread);
        boolean ok;
        synchronized (lock) {
            ok = muxing && videoSamples > 0;
            try { if (muxing) muxer.stop(); } catch (Exception e) { Log.w(TAG, "muxer stop", e); ok = false; }
            try { muxer.release(); } catch (Exception ignored) { }
            muxing = false;
        }
        try { video.stop(); } catch (Exception ignored) { }
        video.release();
        inputSurface.release();
        if (audio != null) { try { audio.stop(); } catch (Exception ignored) { } audio.release(); }
        if (mic != null) { try { mic.stop(); } catch (Exception ignored) { } mic.release(); }
        return ok;
    }

    private static void join(Thread t) {
        if (t == null) return;
        try { t.join(4000); } catch (InterruptedException ignored) { }
    }

    // ---------------------------------------------------------------- video

    private void videoLoop() {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        try {
            while (true) {
                int idx = video.dequeueOutputBuffer(info, 10_000);
                if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    addTrack(true, video.getOutputFormat());
                } else if (idx >= 0) {
                    ByteBuffer out = video.getOutputBuffer(idx);
                    if (out != null) write(true, out, info);
                    video.releaseOutputBuffer(idx, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break;
                } else if (!running && idx == MediaCodec.INFO_TRY_AGAIN_LATER && eosTimedOut()) {
                    break;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "video loop", e);
            if (running) listener.onError("Video encoder stopped: " + e.getMessage());
        }
    }

    private long stopAt = 0;
    private boolean eosTimedOut() {
        long now = System.currentTimeMillis();
        if (stopAt == 0) stopAt = now;
        return now - stopAt > 2500;
    }

    // ---------------------------------------------------------------- audio

    private void audioLoop() {
        short[] pcm = new short[CHUNK];
        float[] f = new float[CHUNK];
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        long samples = 0;
        long startUs;
        try {
            mic.startRecording();
            startUs = System.nanoTime() / 1000;
            while (running) {
                int n = mic.read(pcm, 0, CHUNK);
                if (n <= 0) continue;
                for (int i = 0; i < n; i++) f[i] = pcm[i] / 32768f;
                fx.process(f, n);
                for (int i = 0; i < n; i++) {
                    float v = f[i] * 32767f;
                    pcm[i] = (short) (v > 32767f ? 32767 : v < -32768f ? -32768 : v);
                }
                long pts = startUs + samples * 1_000_000L / SR;
                samples += n;
                queueAudio(pcm, n, pts, false);
                drainAudio(info, false);
            }
            queueAudio(pcm, 0, startUs + samples * 1_000_000L / SR, true);
            drainAudio(info, true);
        } catch (Exception e) {
            Log.e(TAG, "audio loop", e);
        }
    }

    private void queueAudio(short[] pcm, int n, long pts, boolean eos) {
        for (int tries = 0; tries < 50; tries++) {
            int idx = audio.dequeueInputBuffer(10_000);
            if (idx < 0) continue;
            ByteBuffer in = audio.getInputBuffer(idx);
            int bytes = 0;
            if (in != null && n > 0) {
                in.clear();
                in.order(ByteOrder.nativeOrder());
                for (int i = 0; i < n; i++) in.putShort(pcm[i]);
                bytes = n * 2;
            }
            audio.queueInputBuffer(idx, 0, bytes, pts, eos ? MediaCodec.BUFFER_FLAG_END_OF_STREAM : 0);
            return;
        }
    }

    private void drainAudio(MediaCodec.BufferInfo info, boolean untilEos) {
        long deadline = System.currentTimeMillis() + 2000;
        while (true) {
            int idx = audio.dequeueOutputBuffer(info, untilEos ? 10_000 : 0);
            if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                addTrack(false, audio.getOutputFormat());
            } else if (idx >= 0) {
                ByteBuffer out = audio.getOutputBuffer(idx);
                if (out != null) write(false, out, info);
                audio.releaseOutputBuffer(idx, false);
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return;
            } else if (!untilEos || System.currentTimeMillis() > deadline) {
                return;
            }
        }
    }

    // ---------------------------------------------------------------- muxing

    private void addTrack(boolean isVideo, MediaFormat format) {
        synchronized (lock) {
            if (isVideo && videoTrack < 0) videoTrack = muxer.addTrack(format);
            if (!isVideo && audioTrack < 0) audioTrack = muxer.addTrack(format);
            boolean ready = videoTrack >= 0 && (audio == null || audioTrack >= 0);
            if (ready && !muxing) {
                muxer.start();
                muxing = true;
                for (Pending p : pending) writeNow(p.isVideo, p.data, p.info);
                pending.clear();
            }
        }
    }

    private void write(boolean isVideo, ByteBuffer data, MediaCodec.BufferInfo info) {
        if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0 || info.size <= 0) return;
        synchronized (lock) {
            if (muxing) {
                writeNow(isVideo, data, info);
            } else if (pending.size() < 600) {   // keep early samples until both tracks are ready
                ByteBuffer copy = ByteBuffer.allocate(info.size);
                data.position(info.offset); data.limit(info.offset + info.size);
                copy.put(data); copy.flip();
                MediaCodec.BufferInfo ci = new MediaCodec.BufferInfo();
                ci.set(0, info.size, info.presentationTimeUs, info.flags);
                pending.add(new Pending(isVideo, copy, ci));
            }
        }
    }

    private long lastVideoPts = -1, lastAudioPts = -1;
    private void writeNow(boolean isVideo, ByteBuffer data, MediaCodec.BufferInfo info) {
        long pts = info.presentationTimeUs;
        if (isVideo) { if (pts <= lastVideoPts) return; lastVideoPts = pts; videoSamples++; }
        else { if (pts <= lastAudioPts) return; lastAudioPts = pts; }
        data.position(info.offset); data.limit(info.offset + info.size);
        muxer.writeSampleData(isVideo ? videoTrack : audioTrack, data, info);
    }
}
