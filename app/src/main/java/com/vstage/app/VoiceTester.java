package com.vstage.app;

import android.annotation.SuppressLint;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.util.Log;

/** "Test my voice": records a few seconds, applies the voice effect and plays it back. */
public final class VoiceTester {

    public interface Callback { void onState(String state); }

    private static final int SR = 48000;
    private static volatile Thread current;

    public static boolean busy() { return current != null && current.isAlive(); }

    public static void run(String preset, float seconds, Callback cb) {
        if (busy()) return;
        Thread t = new Thread(() -> work(preset, seconds, cb), "vstage-voicetest");
        current = t;
        t.start();
    }

    @SuppressLint("MissingPermission")
    private static void work(String preset, float seconds, Callback cb) {
        int total = (int) (SR * seconds);
        short[] pcm = new short[total];
        AudioRecord rec = null;
        AudioTrack track = null;
        try {
            int min = AudioRecord.getMinBufferSize(SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            rec = new AudioRecord(MediaRecorder.AudioSource.MIC, SR, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, Math.max(min, 8192));
            if (rec.getState() != AudioRecord.STATE_INITIALIZED) { cb.onState("error"); return; }
            cb.onState("listening");
            rec.startRecording();
            int got = 0;
            while (got < total) {
                int n = rec.read(pcm, got, Math.min(1024, total - got));
                if (n <= 0) break;
                got += n;
            }
            rec.stop();

            float[] f = new float[got];
            for (int i = 0; i < got; i++) f[i] = pcm[i] / 32768f;
            VoiceFx fx = new VoiceFx(SR, preset);
            for (int i = 0; i < got; i += 1024) {
                int n = Math.min(1024, got - i);
                float[] c = new float[n];
                System.arraycopy(f, i, c, 0, n);
                fx.process(c, n);
                System.arraycopy(c, 0, f, i, n);
            }
            for (int i = 0; i < got; i++) {
                float v = f[i] * 32767f;
                pcm[i] = (short) (v > 32767f ? 32767 : v < -32768f ? -32768 : v);
            }

            cb.onState("playing");
            track = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(SR)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setBufferSizeInBytes(Math.max(AudioTrack.getMinBufferSize(SR, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT), 16384))
                    .build();
            track.play();
            int off = 0;
            while (off < got) {
                int n = track.write(pcm, off, Math.min(4096, got - off));
                if (n <= 0) break;
                off += n;
            }
            Thread.sleep(300);
            track.stop();
            cb.onState("done");
        } catch (Exception e) {
            Log.e("VStageVoice", "voice test failed", e);
            cb.onState("error");
        } finally {
            if (rec != null) rec.release();
            if (track != null) track.release();
            current = null;
        }
    }
}
