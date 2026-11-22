package com.magicv6.retrotv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.SurfaceTexture;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.GLUtils;
import android.os.SystemClock;
import android.util.Log;
import android.view.Surface;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * Draws the video (from ExoPlayer via a SurfaceTexture) inside the TV bezel, with the same
 * layout maths and CRT effects as build/shaders/adaptive-bezel.slang, plus TV static.
 */
final class BezelRenderer implements GLSurfaceView.Renderer, SurfaceTexture.OnFrameAvailableListener {
    interface Host { void onVideoSurface(Surface s); }

    private static final String TAG = "RetroTV";

    private static final String VS =
            "attribute vec2 aPos;\n" +
            "varying vec2 vUV;\n" +
            "void main() {\n" +
            "  vUV = vec2(aPos.x * 0.5 + 0.5, 0.5 - aPos.y * 0.5);\n" +   // y = 0 at the top
            "  gl_Position = vec4(aPos, 0.0, 1.0);\n" +
            "}\n";

    private static final String FS =
            "#extension GL_OES_EGL_image_external : require\n" +
            "precision highp float;\n" +
            "varying vec2 vUV;\n" +
            "uniform samplerExternalOES uVideo;\n" +
            "uniform sampler2D uBezel;\n" +
            "uniform sampler2D uText;\n" +
            "uniform float uTextOn, uTextMix;\n" +
            "uniform mat4 uSTM;\n" +
            "uniform vec2 uOut, uTexSz;\n" +
            "uniform vec4 uHole, uBody;\n" +
            "uniform float uAR, uSrcAR, uHasVideo, uScan, uLines, uCurve, uVig, uBright, uNoise, uTime;\n" +
            "float rnd(vec2 p) { return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453); }\n" +
            "void main() {\n" +
            "  vec2 px = vUV * uOut;\n" +
            "  vec4 bU = vec4(uBody.x * uAR, uBody.y, uBody.z * uAR, uBody.w);\n" +
            "  float s = min(uOut.x / bU.z, uOut.y / bU.w);\n" +
            "  float offX = uOut.x * 0.5 - (bU.x + bU.z * 0.5) * s;\n" +
            "  float spareY = uOut.y - bU.w * s;\n" +
            "  float alignY = (uOut.y > uOut.x * 1.02) ? 0.0 : 0.5;\n" +
            "  float offY = spareY * alignY - bU.y * s;\n" +
            "  vec2 iuv = vec2((px.x - offX) / (s * uAR), (px.y - offY) / s);\n" +
            "  vec4 bez = texture2D(uBezel, clamp(iuv, 0.5 / uTexSz, 1.0 - 0.5 / uTexSz));\n" +
            "  vec2 h0 = vec2(offX + uHole.x * uAR * s, offY + uHole.y * s);\n" +
            "  vec2 hs = vec2(uHole.z * uAR * s, uHole.w * s);\n" +
            "  vec2 g = (px - h0) / hs;\n" +
            "  vec3 col = vec3(0.0);\n" +
            "  if (bez.a < 0.999) {\n" +
            "    vec2 cc = g * 2.0 - 1.0;\n" +
            "    if (uCurve > 0.0) { cc = cc * (1.0 + cc.yx * cc.yx * uCurve); g = cc * 0.5 + 0.5; }\n" +
            "    if (g.x >= 0.0 && g.x <= 1.0 && g.y >= 0.0 && g.y <= 1.0) {\n" +
            "      vec2 gv = g;\n" +
            "      float r = uSrcAR / (hs.x / hs.y);\n" +
            "      if (r > 1.0) gv.y = (g.y - 0.5) * r + 0.5; else gv.x = (g.x - 0.5) / r + 0.5;\n" +
            "      if (uHasVideo > 0.5 && gv.x >= 0.0 && gv.x <= 1.0 && gv.y >= 0.0 && gv.y <= 1.0)\n" +
            "        col = texture2D(uVideo, (uSTM * vec4(gv.x, 1.0 - gv.y, 0.0, 1.0)).xy).rgb;\n" +
            // teletext page (4:3, fitted inside the tube), opaque unless MIX
            "      if (uTextOn > 0.5) {\n" +
            "        if (uTextMix < 0.5) col = vec3(0.0);\n" +
            "        vec2 tg = g;\n" +
            "        float r2 = (4.0 / 3.0) / (hs.x / hs.y);\n" +
            "        if (r2 > 1.0) tg.y = (g.y - 0.5) * r2 + 0.5; else tg.x = (g.x - 0.5) / r2 + 0.5;\n" +
            "        if (tg.x >= 0.0 && tg.x <= 1.0 && tg.y >= 0.0 && tg.y <= 1.0) {\n" +
            "          vec4 t = texture2D(uText, tg);\n" +
            "          col = col * (1.0 - t.a) + t.rgb;\n" +
            "        }\n" +
            "      }\n" +
            "      if (uNoise > 0.0) {\n" +
            "        float n = rnd(floor(g * vec2(360.0, 270.0)) + fract(uTime) * vec2(91.7, 37.3));\n" +
            "        float band = 0.85 + 0.15 * sin(g.y * 40.0 + uTime * 30.0);\n" +
            "        col = mix(col, vec3(n * band), uNoise);\n" +
            "      }\n" +
            "      if (uScan > 0.0) {\n" +
            "        float ph = fract(g.y * uLines) - 0.5;\n" +
            "        float lum = dot(col, vec3(0.299, 0.587, 0.114));\n" +
            "        float beam = exp(-ph * ph * mix(10.0, 4.0, lum));\n" +
            "        col *= mix(1.0, beam * 1.35, uScan);\n" +
            "      }\n" +
            "      if (uVig > 0.0) {\n" +
            "        vec2 v = g * (1.0 - g);\n" +
            "        col *= mix(1.0, clamp(pow(v.x * v.y * 16.0, 0.25), 0.0, 1.0), uVig);\n" +
            "      }\n" +
            "      col *= uBright;\n" +
            "    }\n" +
            "  }\n" +
            // bezel bitmap is premultiplied by GLUtils
            "  gl_FragColor = vec4(col * (1.0 - bez.a) + bez.rgb, 1.0);\n" +
            "}\n";

    private final Context ctx;
    private final GLSurfaceView view;
    private final Host host;

    private int prog, oesTex;
    private final int[] bezTex = new int[3];        // 0 = wide, 1 = square, 2 = tall
    private final float[][] bezSz = new float[3][2];
    private SurfaceTexture st;
    private final float[] stm = new float[16];
    private FloatBuffer quad;
    private int outW = 1, outH = 1;
    private final long t0 = SystemClock.uptimeMillis();

    private BezelSet loaded;
    private volatile BezelSet want;
    private volatile boolean frameAvail;
    private volatile float srcAR = 16f / 9f;
    private volatile boolean hasVideo;
    private volatile float noiseTarget = 1f;
    private float noise = 1f;

    private int textTex;
    private volatile Bitmap textFrame;       // latest teletext page (null = off)
    private volatile boolean textMix, textDirty;

    BezelRenderer(Context ctx, GLSurfaceView view, Host host, BezelSet initial) {
        this.ctx = ctx;
        this.view = view;
        this.host = host;
        this.want = initial;
        android.opengl.Matrix.setIdentityM(stm, 0);
    }

    void setBezelSet(BezelSet s) { want = s; view.requestRender(); }
    void setSourceAspect(float ar) { if (ar > 0.1f && ar < 10f) srcAR = ar; view.requestRender(); }
    void setHasVideo(boolean b) { hasVideo = b; view.requestRender(); }
    void setStatic(float amount) { noiseTarget = amount; view.requestRender(); }

    /** Shows a teletext page on the tube; null turns it off. The bitmap must not change afterwards. */
    void setText(Bitmap frame, boolean mix) {
        textFrame = frame;
        textMix = mix;
        textDirty = true;
        view.requestRender();
    }

    @Override
    public void onSurfaceCreated(GL10 unused, EGLConfig config) {
        prog = link(VS, FS);
        quad = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        quad.put(new float[]{-1, -1, 1, -1, -1, 1, 1, 1}).position(0);

        int[] t = new int[5];
        GLES20.glGenTextures(5, t, 0);
        oesTex = t[0];
        bezTex[0] = t[1];
        bezTex[1] = t[2];
        bezTex[2] = t[3];
        textTex = t[4];
        textDirty = true;
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex);
        texParams(GLES11Ext.GL_TEXTURE_EXTERNAL_OES);

        if (st != null) st.release();
        st = new SurfaceTexture(oesTex);
        st.setOnFrameAvailableListener(this);
        loaded = null;                 // new context: textures must be uploaded again
        host.onVideoSurface(new Surface(st));
    }

    @Override
    public void onSurfaceChanged(GL10 unused, int w, int h) {
        outW = Math.max(w, 1);
        outH = Math.max(h, 1);
        GLES20.glViewport(0, 0, outW, outH);
    }

    @Override
    public void onFrameAvailable(SurfaceTexture s) {
        frameAvail = true;
        view.requestRender();
    }

    @Override
    public void onDrawFrame(GL10 unused) {
        BezelSet set = want;
        if (set != loaded) {
            upload(bezTex[0], set.wide.file, bezSz[0]);
            upload(bezTex[1], set.square.file, bezSz[1]);
            if (set.tall != null) upload(bezTex[2], set.tall.file, bezSz[2]);
            loaded = set;
        }
        if (frameAvail) {
            frameAvail = false;
            try {
                st.updateTexImage();
                st.getTransformMatrix(stm);
            } catch (Exception e) {
                Log.w(TAG, "updateTexImage", e);
            }
        }

        BezelSet.Layout L = set.layoutFor(outW, outH);
        int bi = L == set.wide ? 0 : L == set.square ? 1 : 2;

        noise += (noiseTarget - noise) * 0.2f;
        if (Math.abs(noiseTarget - noise) < 0.01f) noise = noiseTarget;

        GLES20.glUseProgram(prog);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex);
        GLES20.glUniform1i(loc("uVideo"), 0);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, bezTex[bi]);
        GLES20.glUniform1i(loc("uBezel"), 1);
        Bitmap tf = textFrame;
        GLES20.glActiveTexture(GLES20.GL_TEXTURE2);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textTex);
        if (textDirty && tf != null) {
            textDirty = false;
            texParams(GLES20.GL_TEXTURE_2D);
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, tf, 0);
        }
        GLES20.glUniform1i(loc("uText"), 2);
        GLES20.glUniform1f(loc("uTextOn"), tf != null ? 1f : 0f);
        GLES20.glUniform1f(loc("uTextMix"), textMix ? 1f : 0f);

        GLES20.glUniformMatrix4fv(loc("uSTM"), 1, false, stm, 0);
        GLES20.glUniform2f(loc("uOut"), outW, outH);
        GLES20.glUniform2f(loc("uTexSz"), bezSz[bi][0], bezSz[bi][1]);
        GLES20.glUniform4f(loc("uHole"), L.hole[0], L.hole[1], L.hole[2], L.hole[3]);
        GLES20.glUniform4f(loc("uBody"), L.body[0], L.body[1], L.body[2], L.body[3]);
        GLES20.glUniform1f(loc("uAR"), L.ar);
        GLES20.glUniform1f(loc("uSrcAR"), srcAR);
        GLES20.glUniform1f(loc("uHasVideo"), hasVideo ? 1f : 0f);
        GLES20.glUniform1f(loc("uScan"), set.scan(L));
        GLES20.glUniform1f(loc("uLines"), set.lines);
        GLES20.glUniform1f(loc("uCurve"), set.curve(L));
        GLES20.glUniform1f(loc("uVig"), set.vignette(L));
        GLES20.glUniform1f(loc("uBright"), set.bright(L));
        GLES20.glUniform1f(loc("uNoise"), tf != null && !textMix ? 0f : noise);
        GLES20.glUniform1f(loc("uTime"), ((SystemClock.uptimeMillis() - t0) % 100000) / 1000f);

        int aPos = GLES20.glGetAttribLocation(prog, "aPos");
        GLES20.glEnableVertexAttribArray(aPos);
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, quad);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

        // keep animating while there is static on screen
        if (noise > 0f) view.requestRender();
    }

    private int loc(String n) { return GLES20.glGetUniformLocation(prog, n); }

    private void upload(int tex, String file, float[] sz) {
        try (InputStream in = ctx.getAssets().open("bezels/" + file)) {
            Bitmap bm = BitmapFactory.decodeStream(in);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex);
            texParams(GLES20.GL_TEXTURE_2D);
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bm, 0);
            sz[0] = bm.getWidth();
            sz[1] = bm.getHeight();
            bm.recycle();
        } catch (Exception e) {
            Log.e(TAG, "bezel " + file, e);
        }
    }

    private static void texParams(int target) {
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
    }

    private static int link(String vs, String fs) {
        int p = GLES20.glCreateProgram();
        GLES20.glAttachShader(p, compile(GLES20.GL_VERTEX_SHADER, vs));
        GLES20.glAttachShader(p, compile(GLES20.GL_FRAGMENT_SHADER, fs));
        GLES20.glLinkProgram(p);
        int[] ok = new int[1];
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0);
        if (ok[0] == 0) Log.e(TAG, "link: " + GLES20.glGetProgramInfoLog(p));
        return p;
    }

    private static int compile(int type, String src) {
        int s = GLES20.glCreateShader(type);
        GLES20.glShaderSource(s, src);
        GLES20.glCompileShader(s);
        int[] ok = new int[1];
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) Log.e(TAG, "compile: " + GLES20.glGetShaderInfoLog(s));
        return s;
    }
}
