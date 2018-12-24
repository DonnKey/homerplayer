package com.studio4plus.homerplayer.ui.classic;

import android.animation.Animator;
import android.animation.AnimatorInflater;
import android.annotation.SuppressLint;
import android.app.Fragment;
import android.graphics.Rect;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.RelativeLayout;
import android.widget.TextView;

import com.google.common.base.Preconditions;
import com.studio4plus.homerplayer.GlobalSettings;
import com.studio4plus.homerplayer.HomerPlayerApplication;
import com.studio4plus.homerplayer.R;
import com.studio4plus.homerplayer.ui.FFRewindTimer;
import com.studio4plus.homerplayer.ui.HintOverlay;
import com.studio4plus.homerplayer.ui.PressReleaseDetector;
import com.studio4plus.homerplayer.ui.SimpleAnimatorListener;
import com.studio4plus.homerplayer.ui.UiUtil;
import com.studio4plus.homerplayer.ui.UiControllerPlayback;
import com.studio4plus.homerplayer.util.ViewUtils;

import java.util.concurrent.TimeUnit;

import javax.inject.Inject;

import io.codetail.animation.ViewAnimationUtils;

@SuppressWarnings("deprecation") // of Fragment
public class FragmentPlayback extends Fragment implements FFRewindTimer.Observer {

    private View view;
    private Button stopButton;
    private ImageButton rewindButton;
    private ImageButton ffButton;
    private TextView elapsedTimeView;
    private TextView elapsedTimeRewindFFView;
    private TextView chapterInfoView;
    private RewindFFHandler rewindFFHandler;
    private Animator elapsedTimeRewindFFViewAnimation;
    @SuppressWarnings({"FieldCanBeLocal", "unused"})
    private UiUtil.SnoozeDisplay snooze;

    private @Nullable UiControllerPlayback controller;

    @SuppressWarnings("WeakerAccess")
    @Inject public GlobalSettings globalSettings;

    @Override
    public View onCreateView(
            LayoutInflater inflater,
            @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        view = inflater.inflate(R.layout.fragment_playback, container, false);
        HomerPlayerApplication.getComponent(view.getContext()).inject(this);

        // This should be early so no buttons go live before this
        snooze = new UiUtil.SnoozeDisplay(this, view, globalSettings);

        stopButton = view.findViewById(R.id.stopButton);
        stopButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Preconditions.checkNotNull(controller);
                controller.stopPlayback();
            }
        });

        elapsedTimeView = view.findViewById(R.id.elapsedTime);
        elapsedTimeRewindFFView = view.findViewById(R.id.elapsedTimeRewindFF);
        chapterInfoView = view.findViewById(R.id.chapterInfo);

        elapsedTimeView.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(
                    View v,
                    int left, int top, int right, int bottom,
                    int oldLeft, int oldTop, int oldRight, int oldBottom) {
                RelativeLayout.LayoutParams params =
                        (RelativeLayout.LayoutParams) elapsedTimeRewindFFView.getLayoutParams();
                params.leftMargin = left;
                params.topMargin = top;
                params.width = right - left;
                params.height = bottom - top;
                elapsedTimeRewindFFView.setLayoutParams(params);
            }
        });

        rewindButton = view.findViewById(R.id.rewindButton);
        ffButton = view.findViewById(R.id.fastForwardButton);

        View rewindFFOverlay = view.findViewById(R.id.rewindFFOverlay);
        rewindFFHandler = new RewindFFHandler(
                (View) rewindFFOverlay.getParent(), rewindFFOverlay);
        rewindButton.setEnabled(false);
        ffButton.setEnabled(false);

        rewindFFOverlay.setOnTouchListener(new View.OnTouchListener() {
            @SuppressLint("ClickableViewAccessibility") // We meant that.
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                // Don't let any events "through" the overlay.
                return true;
            }
        });

        elapsedTimeRewindFFViewAnimation =
                AnimatorInflater.loadAnimator(view.getContext(), R.animator.bounce);
        elapsedTimeRewindFFViewAnimation.setTarget(elapsedTimeRewindFFView);

        UiUtil.startBlinker(view, globalSettings);

        return view;
    }

    @SuppressLint("ClickableViewAccessibility") // This is press-and-hold, so click not meaningful
    // TODO: can press-and-hold be made accessible?
    @Override
    public void onResume() {
        super.onResume();
        rewindButton.setOnTouchListener(new PressReleaseDetector(rewindFFHandler));
        ffButton.setOnTouchListener(new PressReleaseDetector(rewindFFHandler));
        showHintIfNecessary();
    }

    @SuppressLint("ClickableViewAccessibility") // This is press-and-hold, so click not meaningful
    // TODO: can press-and-hold be made accessible?
    @Override
    public void onPause() {
        // Remove press-release detectors and tell rewindFFHandler directly that we're paused.
        rewindButton.setOnTouchListener(null);
        ffButton.setOnTouchListener(null);
        rewindFFHandler.onPause();
        super.onPause();
    }

    void onPlaybackStopping() {
        disableUiOnStopping();
        rewindFFHandler.onStopping();
    }

    void onPlaybackProgressed(long playbackPositionMs) {
        onTimerUpdated(playbackPositionMs);
        enableUiOnStart();
    }

    private void enableUiOnStart() {
        rewindButton.setEnabled(true);
        ffButton.setEnabled(true);
    }

    private void disableUiOnStopping() {
        rewindButton.setEnabled(false);
        stopButton.setEnabled(false);
        ffButton.setEnabled(false);
    }

    private String elapsedTime(long elapsedMs) {
        Preconditions.checkNotNull(controller);
        long hours = TimeUnit.MILLISECONDS.toHours(elapsedMs);
        long minutes = TimeUnit.MILLISECONDS.toMinutes(elapsedMs) % 60;
        long seconds = TimeUnit.MILLISECONDS.toSeconds(elapsedMs) % 60;

        long total = controller.getAudioBookBeingPlayed().getTotalDurationMs();
        long progress = (100*elapsedMs)/total;

        return getString(R.string.playback_elapsed_time, hours, minutes, seconds, progress);
    }

    private void showHintIfNecessary() {
        if (isResumed() && isVisible()) {
            if (!globalSettings.flipToStopHintShown()) {
                HintOverlay overlay = new HintOverlay(
                        view, R.id.flipToStopHintOverlayStub, R.string.hint_flip_to_stop, R.drawable.hint_flip_to_stop);
                overlay.show();
                globalSettings.setFlipToStopHintShown();
            }
        }
    }

    @Override
    public void onTimerUpdated(long displayTimeMs) {
        Preconditions.checkNotNull(controller);
        elapsedTimeView.setText(elapsedTime(displayTimeMs));
        chapterInfoView.setText(controller.getAudioBookBeingPlayed().getChapter());
        elapsedTimeRewindFFView.setText(elapsedTime(displayTimeMs));
    }

    @Override
    public void onTimerLimitReached() {
        if (elapsedTimeRewindFFView.getVisibility() == View.VISIBLE) {
            elapsedTimeRewindFFViewAnimation.start();
        }
    }

    void setController(@NonNull UiControllerPlayback controller) {
        this.controller = controller;
    }

    private class RewindFFHandler implements PressReleaseDetector.Listener {

        private final View commonParent;
        private final View rewindOverlay;
        private Animator currentAnimator;
        private boolean isRunning;

        private RewindFFHandler(@NonNull View commonParent, @NonNull View rewindOverlay) {
            this.commonParent = commonParent;
            this.rewindOverlay = rewindOverlay;
        }

        @Override
        public void onPressed(final View v, float x, float y) {
            Preconditions.checkNotNull(controller);
            if (currentAnimator != null) {
                currentAnimator.cancel();
            }

            final boolean isFF = (v == ffButton);
            rewindOverlay.setVisibility(View.VISIBLE);
            currentAnimator = createAnimation(v, x, y, true);
            currentAnimator.addListener(new SimpleAnimatorListener() {
                private boolean isCancelled = false;

                @Override
                public void onAnimationEnd(Animator animator) {

                    currentAnimator = null;
                    if (!isCancelled)
                        controller.startRewind(isFF, FragmentPlayback.this);
                }

                @Override
                public void onAnimationCancel(Animator animator) {
                    isCancelled = true;
                    resumeFromRewind();
                }
            });
            currentAnimator.start();

            controller.pauseForRewind();
            isRunning = true;
        }

        @Override
        public void onReleased(View v, float x, float y) {
            if (currentAnimator != null) {
                currentAnimator.cancel();
                rewindOverlay.setVisibility(View.GONE);
                currentAnimator = null;
            } else {
                currentAnimator = createAnimation(v, x, y, false);
                currentAnimator.addListener(new SimpleAnimatorListener() {
                    @Override
                    public void onAnimationEnd(Animator animator) {
                        rewindOverlay.setVisibility(View.GONE);
                        currentAnimator = null;
                    }
                });
                currentAnimator.start();
                resumeFromRewind();
            }
        }

        void onPause() {
            if (currentAnimator != null) {
                // Cancelling the animation calls resumeFromRewind.
                currentAnimator.cancel();
                currentAnimator = null;
            } else if (isRunning) {
                resumeFromRewind();
            }
        }

        void onStopping() {
            if (isRunning)
                stopRewind();
        }

        private void resumeFromRewind() {
            Preconditions.checkNotNull(controller);
            stopRewind();
            controller.resumeFromRewind();
        }

        private void stopRewind() {
            Preconditions.checkNotNull(controller);
            controller.stopRewind();
            isRunning = false;
        }

        private Animator createAnimation(View v, float x, float y, boolean reveal) {
            Rect viewRect = ViewUtils.getRelativeRect(commonParent, v);
            float startX = viewRect.left + x;
            float startY = viewRect.top + y;

            // Compute final radius
            float dx = Math.max(startX, commonParent.getWidth() - startX);
            float dy = Math.max(startY, commonParent.getHeight() - startY);
            float finalRadius = (float) Math.hypot(dx, dy);

            float initialRadius = reveal ? 0f : finalRadius;
            if (!reveal)
                finalRadius = 0f;

            final int durationResId = reveal
                    ? R.integer.ff_rewind_overlay_show_animation_time_ms
                    : R.integer.ff_rewind_overlay_hide_animation_time_ms;
            Animator animator = ViewAnimationUtils.createCircularReveal(
                    rewindOverlay, Math.round(startX), Math.round(startY), initialRadius, finalRadius);
            animator.setDuration(getResources().getInteger(durationResId));
            animator.setInterpolator(new AccelerateDecelerateInterpolator());

            return animator;
        }
    }
}
