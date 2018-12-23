package com.studio4plus.homerplayer.ui.classic;

import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.text.Html;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import com.google.common.base.Preconditions;
import com.studio4plus.homerplayer.GlobalSettings;
import com.studio4plus.homerplayer.HomerPlayerApplication;
import com.studio4plus.homerplayer.R;
import com.studio4plus.homerplayer.model.AudioBook;
import com.studio4plus.homerplayer.model.AudioBookManager;
import com.studio4plus.homerplayer.ui.UiUtil;
import com.studio4plus.homerplayer.ui.UiControllerBookList;

import java.util.concurrent.TimeUnit;

import javax.inject.Inject;
import javax.inject.Named;

public class FragmentBookItem extends BookListChildFragment {

    public static FragmentBookItem newInstance(String bookId) {
        FragmentBookItem newFragment = new FragmentBookItem();
        Bundle args = new Bundle();
        args.putString(ARG_BOOK_ID, bookId);
        newFragment.setArguments(args);
        return newFragment;
    }

    @SuppressWarnings("WeakerAccess")
    @Inject public GlobalSettings globalSettings;
    @SuppressWarnings("WeakerAccess")
    @Inject public AudioBookManager audioBookManager;
    @SuppressWarnings("WeakerAccess")
    @Inject @Named("AUDIOBOOKS_DIRECTORY") public String audioBooksDirectoryName;

    private @Nullable UiControllerBookList controller;
    @SuppressWarnings({"unused", "FieldCanBeLocal"})
    private UiUtil.SnoozeDisplay snooze;

    @Override
    public View onCreateView(
            LayoutInflater inflater,
            @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {

        View view = inflater.inflate(R.layout.fragment_book_item, container, false);
        HomerPlayerApplication.getComponent(view.getContext()).inject(this);

        // This should be early so no buttons go live before this
        // TODO: determine if we want to skip snoozeDelay on initial startup
        snooze = new UiUtil.SnoozeDisplay(this, view, globalSettings);

        Bundle args = getArguments();
        final String bookId = args.getString(ARG_BOOK_ID);
        if (bookId != null) {
            AudioBook book = audioBookManager.getById(bookId);
            TextView textView = view.findViewById(R.id.title);
            textView.setText(book.getTitle());
            textView.setTextColor(book.getColourScheme().textColour);
            view.setBackgroundColor(book.getColourScheme().backgroundColour);

            if (book.isDemoSample()) {
                TextView copyBooksInstruction =
                        view.findViewById(R.id.copyBooksInstruction);
                String directoryMessage =
                        getString(R.string.copyBooksInstructionMessage, audioBooksDirectoryName);
                copyBooksInstruction.setText(Html.fromHtml(directoryMessage));
                copyBooksInstruction.setTextColor(book.getColourScheme().textColour);
                copyBooksInstruction.setVisibility(View.VISIBLE);
            }

            // Show the total length and how far along
            final TextView progress = view.findViewById(R.id.currentPosition);
            String progressMessage = thisBookProgress(book);
            progress.setText(progressMessage);

            // Try to show chapter information
            final TextView chapter = view.findViewById(R.id.chapterInfo);
            String chapterName = book.getChapter();
            chapter.setText(chapterName);

            final Button startButton = view.findViewById(R.id.startButton);
            startButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Preconditions.checkNotNull(controller);
                    controller.playCurrentAudiobook();
                    startButton.setEnabled(false);
                }
            });
        }

        UiUtil.connectToSettings(view, globalSettings);

        UiUtil.startBlinker(view, globalSettings);

        return view;
    }

    // Where we are in the current book
    private String thisBookProgress(AudioBook book) {
        long totalMs = book.getTotalDurationMs();
        AudioBook.Position position = book.getLastPosition();
        long currentMs = book.getLastPositionTime(position.seekPosition);

        long hours = TimeUnit.MILLISECONDS.toHours(totalMs);
        long minutes = TimeUnit.MILLISECONDS.toMinutes(totalMs) % 60;
        long seconds = TimeUnit.MILLISECONDS.toSeconds(totalMs) % 60;

        long progress = (currentMs * 100) / totalMs;

        return getString(R.string.playback_elapsed_time, hours, minutes, seconds, progress);
    }

    public String getAudioBookId() {
        return getArguments().getString(ARG_BOOK_ID);
    }

    void setController(@NonNull UiControllerBookList controller) {
        this.controller = controller;
    }

    private static final String ARG_BOOK_ID = "bookId";
}
