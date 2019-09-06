/**
 * The MIT License (MIT)
 *
 * Copyright (c) 2018-2019 Donn S. Terry
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.donnKey.aesopPlayer.ui.classic;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import com.crashlytics.android.Crashlytics;
import com.donnKey.aesopPlayer.AesopPlayerApplication;
import com.donnKey.aesopPlayer.ApplicationComponent;
import com.donnKey.aesopPlayer.GlobalSettings;
import com.donnKey.aesopPlayer.R;
import com.donnKey.aesopPlayer.ui.InitUi;
import com.donnKey.aesopPlayer.ui.UiControllerInit;

import javax.inject.Inject;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;


@SuppressWarnings("FieldCanBeLocal")
public class ClassicInitUi extends Fragment implements InitUi {

    @SuppressWarnings("WeakerAccess")
    @Inject public GlobalSettings globalSettings;

    @SuppressWarnings("unused")
    private UiControllerInit controller;

    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater,
            @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        View view;

        // Trivial screen with our logo just to avoid a long black screen if there are
        // a lot of books.
        view = inflater.inflate(R.layout.fragment_init, container, false);
        ApplicationComponent component = AesopPlayerApplication.getComponent(view.getContext());
        component.inject(this);

        return view;
    }

    @Override
    public void initWithController(@NonNull UiControllerInit controller) {
        this.controller = controller;
    }

    @Override
    public void onResume() {
        super.onResume();
        Crashlytics.log("UI: ClassicInit fragment resumed");
    }
}
