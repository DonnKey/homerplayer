package com.donnKey.aesopPlayer.ui;

import androidx.annotation.NonNull;

public interface PlaybackUi {

    enum SpeedLevel {
        STOP,
        REGULAR,
        FAST,
        FASTEST
    }

    void initWithController(@NonNull UiControllerPlayback controller);
    void onPlaybackProgressed(long playbackPositionMs);
    void onPlaybackStopping();
    void onChangeStopPause(int title);

    /**
     * Notify that fast-forward/rewind is taking place and at what speed level.
     * Must be called with SpeedLevel.STOP when ff/rewind is finished.
     */
    void onFFRewindSpeed(SpeedLevel speedLevel);
}