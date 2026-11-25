package com.vstage.app;

import java.util.Random;

/**
 * Real-time voice changer (mono float samples, -1..1).
 * Presets:
 *   off, x (2011X-style demon), deep, chipmunk, robot, ghost, glitch, radio
 * Pure Java so it can run inside the recorder's audio thread with no extra libraries.
 */
public final class VoiceFx {

    public static final String[] PRESETS = {"off", "x", "deep", "chipmunk", "robot", "ghost", "glitch", "radio"};

    private final float sr;
    private final String preset;

    // building blocks (created only when a preset needs them)
    private PitchShifter shiftA, shiftB;
    private Reverb reverb;
    private Echo echo;
    private final Gate gate;
    private OnePole lowA, lowB, highA;
    private final Random rnd = new Random(2011);
    private double t = 0;                 // seconds
    private final double dt;

    // glitch state
    private float[] stutter;
    private int stutterLen = 0, stutterPos = 0, stutterLeft = 0, stutterWrite = 0;
    private float crushHold = 0; private int crushCount = 0;

    public VoiceFx(int sampleRate, String preset) {
        this.sr = sampleRate;
        this.preset = preset == null ? "off" : preset;
        this.dt = 1.0 / sampleRate;
        gate = new Gate(sr);
        switch (this.preset) {
            case "x":
                shiftA = new PitchShifter(sr, -5f, 0.060f);
                shiftB = new PitchShifter(sr, -12f, 0.070f);
                lowA = new OnePole(sr, 260f);
                highA = new OnePole(sr, 5500f);
                echo = new Echo(sr, 0.13f, 0.28f);
                reverb = new Reverb(sr, 0.80f);
                break;
            case "deep":
                shiftA = new PitchShifter(sr, -7f, 0.055f);
                reverb = new Reverb(sr, 0.55f);
                break;
            case "chipmunk":
                shiftA = new PitchShifter(sr, 8f, 0.035f);
                break;
            case "robot":
                echo = new Echo(sr, 0.009f, 0.55f);
                break;
            case "ghost":
                shiftA = new PitchShifter(sr, 3f, 0.045f);
                highA = new OnePole(sr, 350f);
                echo = new Echo(sr, 0.23f, 0.45f);
                reverb = new Reverb(sr, 0.92f);
                break;
            case "glitch":
                shiftA = new PitchShifter(sr, -3f, 0.045f);
                stutter = new float[(int) (sr * 0.25f)];
                break;
            case "radio":
                lowA = new OnePole(sr, 3200f);
                highA = new OnePole(sr, 500f);
                break;
            default:
                break;
        }
    }

    public boolean isOff() { return "off".equals(preset); }

    /** Process n samples in place. */
    public void process(float[] buf, int n) {
        if (isOff()) return;
        for (int i = 0; i < n; i++) {
            float x = gate.run(buf[i]);
            float y;
            switch (preset) {
                case "x": y = demon(x); break;
                case "deep": { float a = shiftA.run(x); y = a * 1.1f + reverb.run(a) * 0.22f; break; }
                case "chipmunk": y = shiftA.run(x); break;
                case "robot": {
                    float rm = x * (float) Math.sin(2 * Math.PI * 95 * t);
                    y = echo.run(rm * 1.6f) * 0.9f;
                    break;
                }
                case "ghost": {
                    float a = shiftA.run(x);
                    float hp = a - highA.run(a);                     // thin, airy
                    float trem = 0.75f + 0.25f * (float) Math.sin(2 * Math.PI * 5.5 * t);
                    y = (hp * 0.6f + echo.run(hp) * 0.5f + reverb.run(hp) * 0.7f) * trem;
                    break;
                }
                case "glitch": y = glitch(shiftA.run(x)); break;
                case "radio": {
                    float bp = lowA.run(x); bp = bp - highA.run(bp);
                    y = softClip(bp * 3.5f) * 0.8f;
                    break;
                }
                default: y = x;
            }
            buf[i] = softClip(y);
            t += dt;
        }
    }

    /** 2011X-style: pitched-down voice + octave-down growl layer, grit, echo and a dark room. */
    private float demon(float x) {
        float a = shiftA.run(x);                          // -5 semitones: main voice
        float b = shiftB.run(x);                          // -12 semitones: monster layer
        float growl = 0.62f + 0.38f * (float) Math.sin(2 * Math.PI * 31 * t);   // rough "throat" flutter
        b *= growl;
        float mix = a * 0.85f + b * 0.75f + x * 0.08f;
        mix += lowA.run(mix) * 0.55f;                     // heavy low end
        float dist = (float) Math.tanh(mix * 2.6f) * 0.62f; // grit
        dist = highA.run(dist);                           // tame fizz from the distortion
        float wet = echo.run(dist) * 0.32f + reverb.run(dist) * 0.30f;
        return (dist + wet) * 0.8f;
    }

    private float glitch(float x) {
        // record into ring buffer; sometimes freeze and repeat a tiny chunk (stutter), with bit-crush
        stutter[stutterWrite] = x;
        stutterWrite = (stutterWrite + 1) % stutter.length;
        float y = x;
        if (stutterLeft > 0) {
            int idx = (stutterWrite - stutterLen + stutterPos + stutter.length * 2) % stutter.length;
            y = stutter[idx];
            stutterPos = (stutterPos + 1) % stutterLen;
            stutterLeft--;
        } else if (rnd.nextFloat() < 1.6f / sr && Math.abs(x) > 0.02f) {
            stutterLen = (int) (sr * (0.03f + rnd.nextFloat() * 0.07f));
            stutterLeft = stutterLen * (2 + rnd.nextInt(4));
            stutterPos = 0;
        }
        if (crushCount-- <= 0) { crushHold = Math.round(y * 24f) / 24f; crushCount = 3; }
        return crushHold * 1.1f;
    }

    static float softClip(float x) {
        if (x > 1f) return 1f - 0.001f;
        if (x < -1f) return -1f + 0.001f;
        return x * (1.5f - 0.5f * x * x);
    }

    // ---------------------------------------------------------------- blocks

    /** Delay-line pitch shifter: two crossfaded read heads sliding through a short buffer. */
    static final class PitchShifter {
        private final float[] buf;
        private final int size;
        private int w = 0;
        private float phase = 0;
        private final float rate;     // phase increment per sample
        private final float win;      // window length in samples

        PitchShifter(float sr, float semitones, float windowSec) {
            win = sr * windowSec;
            size = (int) (win * 2 + 8);
            buf = new float[size];
            float ratio = (float) Math.pow(2, semitones / 12.0);
            rate = (1f - ratio) / win;
        }

        float run(float x) {
            buf[w] = x;
            phase += rate;
            if (phase >= 1f) phase -= 1f;
            if (phase < 0f) phase += 1f;
            float p2 = phase + 0.5f; if (p2 >= 1f) p2 -= 1f;
            float y1 = read(phase * win + 2), y2 = read(p2 * win + 2);
            float g1 = (float) Math.sin(Math.PI * phase); g1 *= g1;
            float g2 = (float) Math.sin(Math.PI * p2); g2 *= g2;
            w = (w + 1) % size;
            return y1 * g1 + y2 * g2;
        }

        private float read(float delay) {
            float pos = w - delay;
            while (pos < 0) pos += size;
            int i0 = (int) pos; float f = pos - i0;
            int i1 = (i0 + 1) % size;
            return buf[i0 % size] * (1 - f) + buf[i1] * f;
        }
    }

    /** One-pole low-pass (use x - lp(x) for high-pass). */
    static final class OnePole {
        private final float a; private float z = 0;
        OnePole(float sr, float hz) { a = (float) (1 - Math.exp(-2 * Math.PI * hz / sr)); }
        float run(float x) { z += a * (x - z); return z; }
    }

    static final class Echo {
        private final float[] buf; private int i = 0; private final float fb;
        Echo(float sr, float sec, float feedback) { buf = new float[Math.max(1, (int) (sr * sec))]; fb = feedback; }
        float run(float x) { float y = buf[i]; buf[i] = x + y * fb; i = (i + 1) % buf.length; return y; }
    }

    /** Small Schroeder reverb: 4 parallel combs + 2 allpasses. */
    static final class Reverb {
        private final float[][] comb; private final int[] ci; private final float[] cf, cz;
        private final float[][] ap; private final int[] ai;
        private final float damp = 0.3f;
        Reverb(float sr, float size) {
            float k = sr / 44100f;
            int[] cl = {1116, 1188, 1277, 1356}; int[] al = {556, 441};
            comb = new float[4][]; ci = new int[4]; cf = new float[4]; cz = new float[4];
            for (int j = 0; j < 4; j++) { comb[j] = new float[(int) (cl[j] * k)]; cf[j] = 0.7f + 0.28f * size; }
            ap = new float[2][]; ai = new int[2];
            for (int j = 0; j < 2; j++) ap[j] = new float[(int) (al[j] * k)];
        }
        float run(float x) {
            float s = 0;
            for (int j = 0; j < 4; j++) {
                float[] b = comb[j]; float y = b[ci[j]];
                cz[j] = y * (1 - damp) + cz[j] * damp;
                b[ci[j]] = x * 0.25f + cz[j] * cf[j];
                ci[j] = (ci[j] + 1) % b.length; s += y;
            }
            for (int j = 0; j < 2; j++) {
                float[] b = ap[j]; float bo = b[ai[j]];
                float y = -s + bo; b[ai[j]] = s + bo * 0.5f;
                ai[j] = (ai[j] + 1) % b.length; s = y;
            }
            return s;
        }
    }

    /** Quiet background noise is muted so the effects don't amplify hiss. */
    static final class Gate {
        private float env = 0, g = 0; private final float atk, rel, gAtk, gRel;
        Gate(float sr) { atk = c(sr, 0.002f); rel = c(sr, 0.08f); gAtk = c(sr, 0.004f); gRel = c(sr, 0.12f); }
        private static float c(float sr, float s) { return (float) (1 - Math.exp(-1 / (sr * s))); }
        float run(float x) {
            float a = Math.abs(x);
            env += (a > env ? atk : rel) * (a - env);
            float target = env > 0.012f ? 1f : env > 0.006f ? (env - 0.006f) / 0.006f : 0f;
            g += (target > g ? gAtk : gRel) * (target - g);
            return x * g;
        }
    }
}
