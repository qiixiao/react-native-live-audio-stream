package com.imxiqi.rnliveaudiostream;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder.AudioSource;
import android.util.Base64;
import android.util.Log;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.modules.core.DeviceEventManagerModule;

import java.lang.Math;

public class RNLiveAudioStreamModule extends ReactContextBaseJavaModule {

    private static final String TAG = "RNLiveAudioStream";

    private final ReactApplicationContext reactContext;
    private DeviceEventManagerModule.RCTDeviceEventEmitter eventEmitter;

    private int sampleRateInHz;
    private int channelConfig;
    private int audioFormat;
    private int audioSource;

    private AudioRecord recorder;
    private int bufferSize;
    private boolean isRecording;

    public RNLiveAudioStreamModule(ReactApplicationContext reactContext) {
        super(reactContext);
        this.reactContext = reactContext;
    }

    @Override
    public String getName() {
        return "RNLiveAudioStream";
    }

    // 释放旧 recorder（含 stop）并按当前参数重建；返回是否进入 STATE_INITIALIZED
    private boolean buildRecorder() {
        isRecording = false;
        if (recorder != null) {
            try {
                if (recorder.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                    recorder.stop();
                }
            } catch (Exception e) {
                Log.e(TAG, "stop old recorder failed", e);
            }
            recorder.release();
            recorder = null;
        }
        int recordingBufferSize = bufferSize * 3;
        recorder = new AudioRecord(audioSource, sampleRateInHz, channelConfig, audioFormat, recordingBufferSize);
        return recorder.getState() == AudioRecord.STATE_INITIALIZED;
    }

    // 向 JS 发 error 事件（不破坏现有 data 事件契约）
    private void emitError(String code, String message) {
        if (eventEmitter == null) {
            return;
        }
        WritableMap payload = Arguments.createMap();
        payload.putString("code", code);
        payload.putString("message", message != null ? message : "");
        eventEmitter.emit("error", payload);
    }

    @ReactMethod
    public void init(ReadableMap options) {
        sampleRateInHz = 44100;
        if (options.hasKey("sampleRate")) {
            sampleRateInHz = options.getInt("sampleRate");
        }

        channelConfig = AudioFormat.CHANNEL_IN_MONO;
        if (options.hasKey("channels")) {
            if (options.getInt("channels") == 2) {
                channelConfig = AudioFormat.CHANNEL_IN_STEREO;
            }
        }

        audioFormat = AudioFormat.ENCODING_PCM_16BIT;
        if (options.hasKey("bitsPerSample")) {
            if (options.getInt("bitsPerSample") == 8) {
                audioFormat = AudioFormat.ENCODING_PCM_8BIT;
            }
        }

        audioSource = AudioSource.VOICE_RECOGNITION;
        if (options.hasKey("audioSource")) {
            audioSource = options.getInt("audioSource");
        }

        isRecording = false;
        eventEmitter = reactContext.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class);

        bufferSize = AudioRecord.getMinBufferSize(sampleRateInHz, channelConfig, audioFormat);
        if (options.hasKey("bufferSize")) {
            bufferSize = Math.max(bufferSize, options.getInt("bufferSize"));
        }

        if (!buildRecorder()) {
            // 未授权 / 参数非法时 new AudioRecord() 不抛异常，仅处于 STATE_UNINITIALIZED。
            // 不在此处抛出，交由 start() 兜底重建；先通知 JS。
            Log.e(TAG, "init: AudioRecord STATE_UNINITIALIZED (check RECORD_AUDIO permission / params)");
            emitError("uninitialized", "AudioRecord 初始化失败：未授权或参数无效");
        }
    }

    @ReactMethod
    public void start() {
        if (bufferSize <= 0) {
            emitError("uninitialized", "start() called before init()");
            return;
        }
        // 兜底：recorder 为空或异常进入未初始化态（如 init 时未授权、recorder 死亡）则重建
        if (recorder == null || recorder.getState() != AudioRecord.STATE_INITIALIZED) {
            if (!buildRecorder()) {
                Log.e(TAG, "start: AudioRecord not initialized, rebuild failed");
                emitError("uninitialized", "AudioRecord 未初始化，无法开始录音");
                return;
            }
        }
        try {
            recorder.startRecording();
        } catch (IllegalStateException e) {
            Log.e(TAG, "startRecording failed", e);
            emitError("start_failed", e.getMessage());
            return;
        }
        isRecording = true;

        Thread recordingThread = new Thread(new Runnable() {
            public void run() {
                try {
                    int bytesRead;
                    int count = 0;
                    String base64Data;
                    byte[] buffer = new byte[bufferSize];

                    while (isRecording) {
                        bytesRead = recorder.read(buffer, 0, buffer.length);

                        // skip first 2 buffers to eliminate "click sound"
                        if (bytesRead > 0 && ++count > 2) {
                            base64Data = Base64.encodeToString(buffer, Base64.NO_WRAP);
                            eventEmitter.emit("data", base64Data);
                        }
                    }
                    recorder.stop();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });

        recordingThread.start();
    }

    @ReactMethod
    public void stop(Promise promise) {
        isRecording = false;
        promise.resolve(null); // 原实现未 resolve，致 JS await 永久挂起
    }

    @Override
    public void invalidate() {
        isRecording = false;
        if (recorder != null) {
            try {
                if (recorder.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                    recorder.stop();
                }
            } catch (Exception e) {
                Log.e(TAG, "invalidate stop failed", e);
            }
            recorder.release(); // 释放硬件资源，避免 mic 被占致后续构造 STATE_UNINITIALIZED
            recorder = null;
        }
        super.invalidate();
    }
}
